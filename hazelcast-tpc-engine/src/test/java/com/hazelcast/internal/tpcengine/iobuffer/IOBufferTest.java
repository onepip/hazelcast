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

package com.hazelcast.internal.tpcengine.iobuffer;

import org.junit.Test;

import static com.hazelcast.internal.tpcengine.util.BitUtil.SIZEOF_INT;
import static org.junit.Assert.assertEquals;

public class IOBufferTest {


    @Test
    public void test() {
        IOBuffer buf = new IOBuffer(10);

        int items = 1000;

        for (int k = 0; k < items; k++) {
            buf.writeInt(k);
        }

        for (int k = 0; k < items; k++) {
            assertEquals(k, buf.getInt(k * SIZEOF_INT));
        }

        System.out.println(buf.byteBuffer().capacity());
    }
}
