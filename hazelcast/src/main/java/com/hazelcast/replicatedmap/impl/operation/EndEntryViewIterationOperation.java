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

package com.hazelcast.replicatedmap.impl.operation;

import com.hazelcast.internal.util.UUIDSerializationUtil;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.replicatedmap.impl.ReplicatedMapService;
import com.hazelcast.replicatedmap.impl.iterator.ReplicatedMapIterationService;
import com.hazelcast.spi.impl.operationservice.ReadonlyOperation;

import java.io.IOException;
import java.util.UUID;

public class EndEntryViewIterationOperation extends AbstractNamedSerializableOperation implements ReadonlyOperation {
    private UUID cursorId;
    private String name;

    public EndEntryViewIterationOperation() {
    }

    public EndEntryViewIterationOperation(String name, UUID cursorId) {
        this.name = name;
        this.cursorId = cursorId;
    }

    @Override
    public int getClassId() {
        return ReplicatedMapDataSerializerHook.END_ENTRYVIEW_ITERATION;
    }

    @Override
    public void run() throws Exception {
        ReplicatedMapService service = getService();
        ReplicatedMapIterationService iterationService = service.getIterationService();
        iterationService.getIteratorManager().cleanupIterator(cursorId);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getServiceName() {
        return ReplicatedMapService.SERVICE_NAME;
    }

    @Override
    protected void writeInternal(ObjectDataOutput out) throws IOException {
        out.writeString(name);
        UUIDSerializationUtil.writeUUID(out, cursorId);
    }

    @Override
    protected void readInternal(ObjectDataInput in) throws IOException {
        name = in.readString();
        cursorId = UUIDSerializationUtil.readUUID(in);
    }
}
