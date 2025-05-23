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

import com.hazelcast.nio.serialization.IdentifiedDataSerializable;

/**
 * A Factory for creating {@link Operation} instances.
 * <p>The operations that will be sent to all partitions causes redundant serialization and network overhead.
 * An {@link OperationFactory} instance is sent to each {@link com.hazelcast.cluster.Member} (node) instead to
 * improve the performance.
 * {@link OperationService} uses this factory to create {@link Operation}s for each partition by calling
 * {@link #createOperation()}
 * </p>
 */
public interface OperationFactory extends IdentifiedDataSerializable {

    /**
     * Creates the operation.
     *
     * @return the created operation.
     */
    Operation createOperation();

}
