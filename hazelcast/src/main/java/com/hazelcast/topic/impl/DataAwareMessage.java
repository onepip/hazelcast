/*
 * Copyright (c) 2008-2025, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.topic.impl;

import com.hazelcast.topic.Message;
import com.hazelcast.cluster.Member;
import com.hazelcast.internal.serialization.Data;
import com.hazelcast.internal.serialization.SerializationService;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.io.Serial;

public class DataAwareMessage extends Message<Object> {

    @Serial
    private static final long serialVersionUID = 1;

    private final transient Data messageData;
    private final transient SerializationService serializationService;

    public DataAwareMessage(String topicName, Data messageData, long publishTime, Member publishingMember,
                            SerializationService serializationService) {
        super(topicName, null, publishTime, publishingMember);
        this.serializationService = serializationService;
        this.messageData = messageData;
    }

    @Override
    public Object getMessageObject() {
        if (messageObject == null && messageData != null) {
            messageObject = serializationService.toObject(messageData);
        }
        return messageObject;
    }

    public Data getMessageData() {
        return messageData;
    }

    @Serial
    private void writeObject(ObjectOutputStream out) throws IOException {
        throw new NotSerializableException();
    }
}
