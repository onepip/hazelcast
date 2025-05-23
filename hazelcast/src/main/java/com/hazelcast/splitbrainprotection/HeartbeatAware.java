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

package com.hazelcast.splitbrainprotection;

import com.hazelcast.cluster.Member;

import java.util.Collection;

/**
 * {@link SplitBrainProtectionFunction}s which implement this interface will be notified of member heartbeats.
 */
@FunctionalInterface
public interface HeartbeatAware {

    /**
     * Notifies of a received heartbeat. This method is always invoked before
     * {@link SplitBrainProtectionFunction#apply(Collection)} so the {@code SplitBrainProtectionFunction} can update
     * its internal state before deciding on whether the minimum cluster size property
     * (for the purpose of split brain detection) is satisfied.
     *
     * @param member    member from which heartbeat was received
     * @param timestamp timestamp on which heartbeat was received
     */
    void onHeartbeat(Member member, long timestamp);
}
