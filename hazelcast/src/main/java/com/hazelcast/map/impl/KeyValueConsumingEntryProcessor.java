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

package com.hazelcast.map.impl;

import com.hazelcast.map.EntryProcessor;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Map;
import java.util.function.BiConsumer;

public class KeyValueConsumingEntryProcessor<K, V> implements EntryProcessor<K, V, V>, IdentifiedDataSerializable {

    BiConsumer<? super K, ? super V> action;

    public KeyValueConsumingEntryProcessor() {
    }

    public KeyValueConsumingEntryProcessor(BiConsumer<? super K, ? super V> action) {
        this.action = action;
    }

    @Override
    public V process(Map.Entry<K, V> entry) {
        action.accept(entry.getKey(), entry.getValue());
        return null;
    }

    @Override
    public int getFactoryId() {
        return MapDataSerializerHook.F_ID;
    }

    @Override
    public int getClassId() {
        return MapDataSerializerHook.KEY_VALUE_CONSUMING_PROCESSOR;
    }

    @Override
    public void writeData(ObjectDataOutput out) throws IOException {
        out.writeObject(action);
    }

    @Override
    public void readData(ObjectDataInput in) throws IOException {
        action = in.readObject();
    }

    @Nullable
    @Override
    public EntryProcessor<K, V, V> getBackupProcessor() {
        return null;
    }
}
