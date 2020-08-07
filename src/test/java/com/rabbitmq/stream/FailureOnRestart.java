package com.rabbitmq.stream;

import com.rabbitmq.stream.impl.Client;

import java.util.UUID;

public class FailureOnRestart {

    public static void main(String[] args) throws Exception {
        while (true) {
            Client client = new Client(new Client.ClientParameters()
                .shutdownListener(shutdownContext -> System.out.println("client disconnected")));
            String s = UUID.randomUUID().toString();
            System.out.println("creating " + s);
            Client.Response response = client.create(s);
            System.out.println("creation of " + s + " OK? " + response.isOk());

            System.out.println("broker stop_app");
            Host.rabbitmqctl("stop_app");
            Thread.sleep(1000L);
            System.out.println("broker start_app");
            Host.rabbitmqctl("start_app");

            Thread.sleep(5000L);

            client = new Client();
            System.out.println("deleting " + s);
            response = client.delete(s);
            System.out.println("deletion of " + s + " OK? " + response.isOk());

            client.close();
        }
    }

}
