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

package com.hazelcast.cache.impl.record;

import com.hazelcast.cache.impl.CacheDataSerializerHook;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;

import javax.cache.expiry.ExpiryPolicy;
import java.io.IOException;

/**
 * Implementation of {@link com.hazelcast.cache.impl.record.CacheRecord} which has an internal object format.
 */
public class CacheObjectRecord extends AbstractCacheRecord<Object, ExpiryPolicy> {

    protected Object value;
    protected ExpiryPolicy expiryPolicy;

    public CacheObjectRecord() {
    }

    public CacheObjectRecord(Object value, long creationTime, long expiryTime) {
        super(creationTime, expiryTime);
        this.value = value;
    }

    @Override
    public Object getValue() {
        return value;
    }

    @Override
    public void setValue(Object value) {
        this.value = value;
    }

    @Override
    public void setExpiryPolicy(ExpiryPolicy expiryPolicy) {
        this.expiryPolicy = expiryPolicy;
    }

    @Override
    public ExpiryPolicy getExpiryPolicy() {
        return expiryPolicy;
    }

    @Override
    public void writeData(ObjectDataOutput out) throws IOException {
        super.writeData(out);
        out.writeObject(value);
        out.writeObject(expiryPolicy);
    }

    @Override
    public void readData(ObjectDataInput in) throws IOException {
        super.readData(in);
        value = in.readObject();
        expiryPolicy = in.readObject();
    }

    @Override
    public int getClassId() {
        return CacheDataSerializerHook.CACHE_OBJECT_RECORD;
    }
}
