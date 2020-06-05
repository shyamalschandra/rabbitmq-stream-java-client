// Copyright (c) 2020 VMware, Inc. or its affiliates.  All rights reserved.
//
// This software, the RabbitMQ Java client library, is dual-licensed under the
// Mozilla Public License 1.1 ("MPL"), and the Apache License version 2 ("ASL").
// For the MPL, please see LICENSE-MPL-RabbitMQ. For the ASL,
// please see LICENSE-APACHE2.
//
// This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY KIND,
// either express or implied. See the LICENSE file for specific language governing
// rights and limitations of this software.
//
// If you have any questions regarding licensing, please contact us at
// info@rabbitmq.com.

package com.rabbitmq.stream;

public interface Codec {

    EncodedMessage encode(Message message);

    Message decode(byte[] data);

    MessageBuilder messageBuilder();

    class EncodedMessage {

        private final int size;
        private final byte[] data;

        public EncodedMessage(int size, byte[] data) {
            this.size = size;
            this.data = data;
        }

        public byte[] getData() {
            return data;
        }

        public int getSize() {
            return size;
        }
    }

}