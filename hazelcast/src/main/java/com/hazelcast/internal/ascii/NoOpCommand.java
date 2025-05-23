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

package com.hazelcast.internal.ascii;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class NoOpCommand extends AbstractTextCommand {

    private final ByteBuffer response;

    public NoOpCommand(byte[] response) {
        super(TextCommandConstants.TextCommandType.NO_OP);
        this.response = ByteBuffer.wrap(response);
    }

    @Override
    public boolean readFrom(ByteBuffer src) {
        return true;
    }

    @Override
    public boolean writeTo(ByteBuffer dst) {
        while (dst.hasRemaining() && response.hasRemaining()) {
            dst.put(response.get());
        }
        return !response.hasRemaining();
    }

    @Override
    public String toString() {
        return "NoOpCommand {" + new String(response.array(), StandardCharsets.UTF_8) + "}";
    }
}
