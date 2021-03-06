// Copyright (c) 2020 VMware, Inc. or its affiliates.  All rights reserved.
//
// This software, the RabbitMQ Stream Java client library, is dual-licensed under the
// Mozilla Public License 2.0 ("MPL"), and the Apache License version 2 ("ASL").
// For the MPL, please see LICENSE-MPL-RabbitMQ. For the ASL,
// please see LICENSE-APACHE2.
//
// This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY KIND,
// either express or implied. See the LICENSE file for specific language governing
// rights and limitations of this software.
//
// If you have any questions regarding licensing, please contact us at
// info@rabbitmq.com.

package com.rabbitmq.stream.impl;

import static java.util.concurrent.TimeUnit.SECONDS;

import com.rabbitmq.stream.*;
import com.rabbitmq.stream.MessageHandler.Context;
import com.rabbitmq.stream.impl.OffsetCommittingCoordinator.Registration;
import com.rabbitmq.stream.impl.StreamConsumerBuilder.CommitConfiguration;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class StreamEnvironment implements Environment {

  private static final Logger LOGGER = LoggerFactory.getLogger(StreamEnvironment.class);

  private final Random random = new Random();

  private final EventLoopGroup eventLoopGroup;
  private final ScheduledExecutorService scheduledExecutorService;
  private final boolean privateScheduleExecutorService;
  private final Client.ClientParameters clientParametersPrototype;
  private final List<Address> addresses;
  private final List<StreamProducer> producers = new CopyOnWriteArrayList<>();
  private final List<StreamConsumer> consumers = new CopyOnWriteArrayList<>();
  private final Codec codec;
  private final BackOffDelayPolicy recoveryBackOffDelayPolicy;
  private final BackOffDelayPolicy topologyUpdateBackOffDelayPolicy;
  private final ConsumersCoordinator consumersCoordinator;
  private final ProducersCoordinator producersCoordinator;
  private final OffsetCommittingCoordinator offsetCommittingCoordinator;
  private volatile Client locator;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  StreamEnvironment(
      ScheduledExecutorService scheduledExecutorService,
      Client.ClientParameters clientParametersPrototype,
      List<URI> uris,
      BackOffDelayPolicy recoveryBackOffDelayPolicy,
      BackOffDelayPolicy topologyBackOffDelayPolicy) {
    this(
        scheduledExecutorService,
        clientParametersPrototype,
        uris,
        recoveryBackOffDelayPolicy,
        topologyBackOffDelayPolicy,
        cp -> new Client(cp));
  }

  StreamEnvironment(
      ScheduledExecutorService scheduledExecutorService,
      Client.ClientParameters clientParametersPrototype,
      List<URI> uris,
      BackOffDelayPolicy recoveryBackOffDelayPolicy,
      BackOffDelayPolicy topologyBackOffDelayPolicy,
      Function<Client.ClientParameters, Client> clientFactory) {
    this.recoveryBackOffDelayPolicy = recoveryBackOffDelayPolicy;
    this.topologyUpdateBackOffDelayPolicy = topologyBackOffDelayPolicy;
    clientParametersPrototype = maybeSetUpClientParametersFromUris(uris, clientParametersPrototype);

    if (uris.isEmpty()) {
      this.addresses =
          Collections.singletonList(
              new Address(clientParametersPrototype.host, clientParametersPrototype.port));
    } else {
      this.addresses =
          uris.stream().map(uriItem -> new Address(uriItem)).collect(Collectors.toList());
    }

    if (clientParametersPrototype.eventLoopGroup == null) {
      this.eventLoopGroup = new NioEventLoopGroup();
      this.clientParametersPrototype =
          clientParametersPrototype.duplicate().eventLoopGroup(this.eventLoopGroup);
    } else {
      this.eventLoopGroup = null;
      this.clientParametersPrototype =
          clientParametersPrototype
              .duplicate()
              .eventLoopGroup(clientParametersPrototype.eventLoopGroup);
    }
    if (scheduledExecutorService == null) {
      this.scheduledExecutorService =
          Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors());
      this.privateScheduleExecutorService = true;
    } else {
      this.scheduledExecutorService = scheduledExecutorService;
      this.privateScheduleExecutorService = false;
    }

    this.producersCoordinator = new ProducersCoordinator(this);
    this.consumersCoordinator = new ConsumersCoordinator(this);
    this.offsetCommittingCoordinator = new OffsetCommittingCoordinator(this);

    AtomicReference<Client.ShutdownListener> shutdownListenerReference = new AtomicReference<>();
    Client.ShutdownListener shutdownListener =
        shutdownContext -> {
          if (shutdownContext.isShutdownUnexpected()) {
            this.locator = null;
            LOGGER.debug("Unexpected locator disconnection, trying to reconnect");
            Client.ClientParameters newLocatorParameters =
                this.clientParametersPrototype
                    .duplicate()
                    .shutdownListener(shutdownListenerReference.get());
            AsyncRetry.asyncRetry(
                    () -> {
                      Address address =
                          addresses.size() == 1
                              ? addresses.get(0)
                              : addresses.get(random.nextInt(addresses.size()));
                      LOGGER.debug("Trying to reconnect locator on {}", address);
                      Client newLocator =
                          clientFactory.apply(
                              newLocatorParameters
                                  .host(address.host)
                                  .port(address.port)
                                  .clientProperty("name", "rabbitmq-stream-locator"));
                      LOGGER.debug("Locator connected on {}", address);
                      return newLocator;
                    })
                .description("Locator recovery")
                .scheduler(this.scheduledExecutorService)
                .delayPolicy(recoveryBackOffDelayPolicy)
                .build()
                .thenAccept(newLocator -> this.locator = newLocator);
          }
        };
    shutdownListenerReference.set(shutdownListener);
    // FIXME try several URIs in case of failure
    RuntimeException lastException = null;
    for (Address address : addresses) {
      Client.ClientParameters locatorParameters =
          clientParametersPrototype
              .duplicate()
              .host(address.host)
              .port(address.port)
              .clientProperty("name", "rabbitmq-stream-locator")
              .shutdownListener(shutdownListenerReference.get());
      try {
        this.locator = clientFactory.apply(locatorParameters);
        LOGGER.debug("Locator connected to {}", address);
        break;
      } catch (RuntimeException e) {
        LOGGER.debug("Error while try to connect to {}: {}", address, e.getMessage());
        lastException = e;
      }
    }
    if (this.locator == null) {
      throw lastException;
    }
    this.codec = locator.codec();
  }

  private static String uriDecode(String s) {
    try {
      // URLDecode decodes '+' to a space, as for
      // form encoding.  So protect plus signs.
      return URLDecoder.decode(s.replace("+", "%2B"), "US-ASCII");
    } catch (IOException e) {
      throw new IllegalArgumentException(e);
    }
  }

  Client.ClientParameters maybeSetUpClientParametersFromUris(
      List<URI> uris, Client.ClientParameters clientParametersPrototype) {
    if (uris.isEmpty()) {
      return clientParametersPrototype;
    } else {
      URI uri = uris.get(0);
      clientParametersPrototype = clientParametersPrototype.duplicate();
      String host = uri.getHost();
      if (host != null) {
        clientParametersPrototype.host(host);
      }

      int port = uri.getPort();
      if (port != -1) {
        clientParametersPrototype.port(port);
      }

      String userInfo = uri.getRawUserInfo();
      if (userInfo != null) {
        String[] userPassword = userInfo.split(":");
        if (userPassword.length > 2) {
          throw new IllegalArgumentException("Bad user info in URI " + userInfo);
        }

        clientParametersPrototype.username(uriDecode(userPassword[0]));
        if (userPassword.length == 2) {
          clientParametersPrototype.password(uriDecode(userPassword[1]));
        }
      }

      String path = uri.getRawPath();
      if (path != null && path.length() > 0) {
        if (path.indexOf('/', 1) != -1) {
          throw new IllegalArgumentException("Multiple segments in path of URI: " + path);
        }
        clientParametersPrototype.virtualHost(uriDecode(uri.getPath().substring(1)));
      }
      return clientParametersPrototype;
    }
  }

  @Override
  public StreamCreator streamCreator() {
    return new StreamStreamCreator(this);
  }

  @Override
  public void deleteStream(String stream) {
    Client.Response response = this.locator().delete(stream);
    if (!response.isOk()) {
      throw new StreamException(
          "Error while deleting stream " + stream, response.getResponseCode());
    }
  }

  @Override
  public ProducerBuilder producerBuilder() {
    return new StreamProducerBuilder(this);
  }

  void addProducer(StreamProducer producer) {
    this.producers.add(producer);
  }

  void removeProducer(StreamProducer producer) {
    this.producers.remove(producer);
  }

  void addConsumer(StreamConsumer consumer) {
    this.consumers.add(consumer);
  }

  void removeConsumer(StreamConsumer consumer) {
    this.consumers.remove(consumer);
  }

  @Override
  public ConsumerBuilder consumerBuilder() {
    return new StreamConsumerBuilder(this);
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      for (StreamProducer producer : producers) {
        try {
          producer.closeFromEnvironment();
        } catch (Exception e) {
          LOGGER.warn("Error while closing producer, moving on to the next one", e);
        }
      }

      for (StreamConsumer consumer : consumers) {
        try {
          consumer.closeFromEnvironment();
        } catch (Exception e) {
          LOGGER.warn("Error while closing consumer, moving on to the next one", e);
        }
      }

      this.producersCoordinator.close();
      this.consumersCoordinator.close();
      this.offsetCommittingCoordinator.close();

      try {
        if (this.locator != null) {
          this.locator.close();
          this.locator = null;
        }

        if (privateScheduleExecutorService) {
          this.scheduledExecutorService.shutdownNow();
        }

      } catch (Exception e) {
        LOGGER.warn("Error while closing locator client", e);
      }

      try {
        if (this.eventLoopGroup != null
            && (!this.eventLoopGroup.isShuttingDown() || !this.eventLoopGroup.isShutdown())) {
          LOGGER.debug("Closing Netty event loop group");
          this.eventLoopGroup.shutdownGracefully(1, 10, SECONDS).get(10, SECONDS);
        }
      } catch (InterruptedException e) {
        LOGGER.info("Event loop group closing has been interrupted");
        Thread.currentThread().interrupt();
      } catch (ExecutionException e) {
        LOGGER.info("Event loop group closing failed", e);
      } catch (TimeoutException e) {
        LOGGER.info("Could not close event loop group in 10 seconds");
      }
    }
  }

  ScheduledExecutorService scheduledExecutorService() {
    return this.scheduledExecutorService;
  }

  BackOffDelayPolicy recoveryBackOffDelayPolicy() {
    return this.recoveryBackOffDelayPolicy;
  }

  BackOffDelayPolicy topologyUpdateBackOffDelayPolicy() {
    return this.topologyUpdateBackOffDelayPolicy;
  }

  Runnable registerConsumer(
      StreamConsumer consumer,
      String stream,
      OffsetSpecification offsetSpecification,
      String trackingReference,
      MessageHandler messageHandler) {
    Runnable closingCallback =
        this.consumersCoordinator.subscribe(
            consumer, stream, offsetSpecification, trackingReference, messageHandler);
    return closingCallback;
  }

  Runnable registerProducer(StreamProducer producer, String stream) {
    return producersCoordinator.registerProducer(producer, stream);
  }

  // FIXME make the locator available as a completable future (with retry)
  // this would make client code more robust
  Client locator() {
    if (this.locator == null) {
      throw new StreamException("No connection available");
    }
    return this.locator;
  }

  Codec codec() {
    return this.codec;
  }

  Client.ClientParameters clientParametersCopy() {
    return this.clientParametersPrototype.duplicate();
  }

  static class CommittingConsumerRegistration {

    private final Runnable closingCallback;
    private final Consumer<Context> postMessageProcessingCallback;
    private final LongConsumer commitCallback;

    CommittingConsumerRegistration(
        Runnable closingCallback,
        Consumer<Context> postMessageProcessingCallback,
        LongConsumer commitCallback) {
      this.closingCallback = closingCallback;
      this.postMessageProcessingCallback = postMessageProcessingCallback;
      this.commitCallback = commitCallback;
    }

    public Runnable closingCallback() {
      return closingCallback;
    }

    public LongConsumer commitCallback() {
      return commitCallback;
    }

    public Consumer<Context> postMessageProcessingCallback() {
      return postMessageProcessingCallback;
    }
  }

  CommittingConsumerRegistration registerCommittingConsumer(
      StreamConsumer streamConsumer, CommitConfiguration configuration) {
    Runnable closingCallable = this.producersCoordinator.registerCommittingConsumer(streamConsumer);
    Registration offsetCommittingRegistration = null;
    if (this.offsetCommittingCoordinator.needCommitRegistration(configuration)) {
      offsetCommittingRegistration =
          this.offsetCommittingCoordinator.registerCommittingConsumer(
              streamConsumer, configuration);
    }

    return new CommittingConsumerRegistration(
        closingCallable,
        offsetCommittingRegistration == null
            ? null
            : offsetCommittingRegistration.postMessageProcessingCallback(),
        offsetCommittingRegistration == null
            ? Utils.NO_OP_LONG_CONSUMER
            : offsetCommittingRegistration.commitCallback());
  }

  private static final class Address {

    private final String host;
    private final int port;

    private Address(URI uri) {
      this(
          uri.getHost() == null ? "localhost" : uri.getHost(),
          uri.getPort() == -1 ? Client.DEFAULT_PORT : uri.getPort());
    }

    private Address(String host, int port) {
      this.host = host;
      this.port = port;
    }

    @Override
    public String toString() {
      return "Address{" + "host='" + host + '\'' + ", port=" + port + '}';
    }
  }

  @Override
  public String toString() {
    Client locator = this.locator;
    return "{ locator : "
        + (locator == null ? "null" : ("'" + locator.getHost() + ":" + locator.getPort() + "'"))
        + ", "
        + "'producers' : "
        + this.producersCoordinator
        + ", 'consumers' : "
        + this.consumersCoordinator
        + "}";
  }
}
