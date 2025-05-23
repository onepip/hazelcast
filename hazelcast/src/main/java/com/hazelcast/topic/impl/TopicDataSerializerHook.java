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

import com.hazelcast.internal.serialization.DataSerializerHook;
import com.hazelcast.internal.serialization.impl.FactoryIdHelper;
import com.hazelcast.nio.serialization.DataSerializableFactory;
import com.hazelcast.topic.impl.reliable.ReliableTopicMessage;

import static com.hazelcast.internal.serialization.impl.FactoryIdHelper.TOPIC_DS_FACTORY;
import static com.hazelcast.internal.serialization.impl.FactoryIdHelper.TOPIC_DS_FACTORY_ID;

public final class TopicDataSerializerHook implements DataSerializerHook {

    public static final int F_ID = FactoryIdHelper.getFactoryId(TOPIC_DS_FACTORY, TOPIC_DS_FACTORY_ID);

    public static final int PUBLISH = 0;
    public static final int TOPIC_EVENT = 1;
    public static final int RELIABLE_TOPIC_MESSAGE = 2;
    public static final int PUBLISH_ALL = 3;

    @Override
    public int getFactoryId() {
        return F_ID;
    }

    @Override
    public DataSerializableFactory createFactory() {
        return typeId -> switch (typeId) {
            case PUBLISH -> new PublishOperation();
            case TOPIC_EVENT -> new TopicEvent();
            case RELIABLE_TOPIC_MESSAGE -> new ReliableTopicMessage();
            case PUBLISH_ALL -> new PublishAllOperation();
            default -> null;
        };
    }
}
