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

package com.hazelcast.internal.management.operation;

import com.hazelcast.spi.impl.NodeEngine;
import com.hazelcast.spi.impl.operationservice.AbstractLocalOperation;
import com.hazelcast.wan.impl.WanReplicationService;

/**
 * Clear WAN replication queues for the given wan replication schema and publisher
 */
public class ClearWanQueuesOperation extends AbstractLocalOperation {

    private String schemeName;
    private String publisherName;

    public ClearWanQueuesOperation(String schemeName, String publisherName) {
        this.schemeName = schemeName;
        this.publisherName = publisherName;
    }

    @Override
    public void run() throws Exception {
        NodeEngine nodeEngine = getNodeEngine();
        WanReplicationService wanReplicationService = nodeEngine.getWanReplicationService();
        wanReplicationService.removeWanEvents(schemeName, publisherName);
    }
}
