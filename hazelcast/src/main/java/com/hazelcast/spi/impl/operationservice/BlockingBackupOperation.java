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

package com.hazelcast.spi.impl.operationservice;

import com.hazelcast.internal.partition.MigrationEndpoint;
import com.hazelcast.internal.partition.PartitionMigrationEvent;

/**
 * SPI for blocking backup operations
 * @see BlockingOperation
 * @see BackupOperation
 * @since 6.0
 */
public interface BlockingBackupOperation extends AsynchronouslyExecutingBackupOperation {

    /**
     * Informs {@link com.hazelcast.spi.impl.operationparker.OperationParker} if the operation is still
     * relevant after migration for given data structure. Usually this should check if the new replica
     * index contains backup data of the data structure.
     *
     * @param event migration event. This will always be event for {@link MigrationEndpoint#SOURCE}
     *             between 2 backup indexes
     * @return if the operation should be kept in the parking queue
     */
    boolean shouldKeepAfterMigration(PartitionMigrationEvent event);
}
