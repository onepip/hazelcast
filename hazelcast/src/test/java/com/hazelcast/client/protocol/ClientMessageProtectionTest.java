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

package com.hazelcast.client.protocol;

import com.hazelcast.client.impl.clientside.ClientTestUtil;
import com.hazelcast.client.impl.protocol.AuthenticationStatus;
import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.impl.protocol.ClientMessage.Frame;
import com.hazelcast.client.impl.protocol.codec.ClientAuthenticationCodec;
import com.hazelcast.client.impl.protocol.util.ClientMessageSplitter;
import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.instance.EndpointQualifier;
import com.hazelcast.instance.impl.TestUtil;
import com.hazelcast.spi.properties.ClusterProperty;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.TestAwareInstanceFactory;
import com.hazelcast.test.annotation.QuickTest;

import org.assertj.core.api.Condition;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static com.hazelcast.client.impl.protocol.ClientMessage.SIZE_OF_FRAME_LENGTH_AND_FLAGS;
import static com.hazelcast.internal.nio.Protocols.CLIENT_BINARY;
import static com.hazelcast.test.Accessors.getNode;
import static com.hazelcast.test.HazelcastTestSupport.smallInstanceConfig;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * This class verifies that client protocol protection is able to filter large and fragmented messages for untrusted
 * connections.
 */
@RunWith(HazelcastParallelClassRunner.class)
@Category({QuickTest.class})
public class ClientMessageProtectionTest {

    private final TestAwareInstanceFactory factory = new TestAwareInstanceFactory();

    @After
    public void after() {
        factory.terminateAll();
    }

    @Test
    public void testLimitsRemovedAfterAValidAuthentication() throws IOException {
        Config config = smallInstanceConfig();
        HazelcastInstance hz = factory.newHazelcastInstance(config);
        ClientMessage clientMessage = createAuthenticationMessage(hz, createString(3));

        InetSocketAddress address = getNode(hz).getLocalMember().getSocketAddress(EndpointQualifier.CLIENT);
        try (Socket socket = new Socket(address.getAddress(), address.getPort())) {
            socket.setSoTimeout(5000);
            try (OutputStream os = socket.getOutputStream(); InputStream is = socket.getInputStream()) {
                os.write(CLIENT_BINARY.getBytes(StandardCharsets.UTF_8));
                ClientTestUtil.writeClientMessage(os, clientMessage);
                ClientMessage respMessage = ClientTestUtil.readResponse(is);
                assertEquals(ClientAuthenticationCodec.RESPONSE_MESSAGE_TYPE, respMessage.getMessageType());
                ClientAuthenticationCodec.ResponseParameters authnResponse = ClientAuthenticationCodec
                        .decodeResponse(respMessage);
                assertEquals(AuthenticationStatus.AUTHENTICATED, AuthenticationStatus.getById(authnResponse.status));

                // the connection is now trusted, lets try bigger and fragmented messages
                ClientMessage authenticationMessage = createAuthenticationMessage(hz, createString(1024));
                ClientTestUtil.writeClientMessage(os, authenticationMessage);
                respMessage = ClientTestUtil.readResponse(is);
                assertEquals(ClientAuthenticationCodec.RESPONSE_MESSAGE_TYPE, respMessage.getMessageType());
                authnResponse = ClientAuthenticationCodec.decodeResponse(respMessage);
                assertEquals(AuthenticationStatus.AUTHENTICATED, AuthenticationStatus.getById(authnResponse.status));

                List<ClientMessage> subFrames = ClientMessageSplitter.getFragments(50, clientMessage);
                assertTrue(subFrames.size() > 1);
                for (ClientMessage frame : subFrames) {
                    ClientTestUtil.writeClientMessage(os, frame);
                }
                respMessage = ClientTestUtil.readResponse(is);
                assertEquals(ClientAuthenticationCodec.RESPONSE_MESSAGE_TYPE, respMessage.getMessageType());
                authnResponse = ClientAuthenticationCodec.decodeResponse(respMessage);
                assertEquals(AuthenticationStatus.AUTHENTICATED, AuthenticationStatus.getById(authnResponse.status));
            }
        }
    }

    @Test
    public void testMessageFraming() throws IOException {
        Config config = smallInstanceConfig();
        HazelcastInstance hz = factory.newHazelcastInstance(config);
        ClientMessage clientMessage = createAuthenticationMessage(hz, createString(200));
        InetSocketAddress address = getNode(hz).getLocalMember().getSocketAddress(EndpointQualifier.CLIENT);
        try (Socket socket = new Socket(address.getAddress(), address.getPort())) {
            socket.setSoTimeout(5000);
            try (OutputStream os = socket.getOutputStream(); InputStream is = socket.getInputStream()) {
                os.write(CLIENT_BINARY.getBytes(StandardCharsets.UTF_8));
                List<ClientMessage> subFrames = ClientMessageSplitter.getFragments(50, clientMessage);
                assertTrue(subFrames.size() > 1);
                ClientTestUtil.writeClientMessage(os, subFrames.get(0));

                assertThatThrownBy(() -> ClientTestUtil.readResponse(is)).is(connectionClosedException());
            }
        }
    }

    @Test
    public void testExceededMessageSize() throws IOException {
        Config config = smallInstanceConfig();
        int limit = 800;
        config.setProperty(ClusterProperty.CLIENT_PROTOCOL_UNVERIFIED_MESSAGE_BYTES.getName(), Integer.toString(limit));
        HazelcastInstance hz = factory.newHazelcastInstance(config);
        String str = createString(limit);
        ClientMessage clientMessage = createAuthenticationMessage(hz, str);
        InetSocketAddress address = getNode(hz).getLocalMember().getSocketAddress(EndpointQualifier.CLIENT);
        try (Socket socket = new Socket(address.getAddress(), address.getPort())) {
            socket.setSoTimeout(5000);
            try (OutputStream os = socket.getOutputStream(); InputStream is = socket.getInputStream()) {
                os.write(CLIENT_BINARY.getBytes(StandardCharsets.UTF_8));
                // The socket might be closed after we write the large string
                // frame and before the frames next to that. So, even the
                // write message call below could throw.
                assertThatThrownBy(() -> {
                    ClientTestUtil.writeClientMessage(os, clientMessage);
                    ClientTestUtil.readResponse(is);
                }).is(connectionClosedException());
            }
        }
    }

    @Test
    public void testNegativeFrameLength() throws IOException {
        Config config = smallInstanceConfig();
        HazelcastInstance hz = factory.newHazelcastInstance(config);
        ClientMessage clientMessage = createAuthenticationMessage(hz, "");
        InetSocketAddress address = getNode(hz).getLocalMember().getSocketAddress(EndpointQualifier.CLIENT);
        try (Socket socket = new Socket(address.getAddress(), address.getPort())) {
            socket.setSoTimeout(5000);
            try (OutputStream os = socket.getOutputStream(); InputStream is = socket.getInputStream()) {
                os.write(CLIENT_BINARY.getBytes(StandardCharsets.UTF_8));
                ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);
                buffer.order(ByteOrder.LITTLE_ENDIAN);
                // it should be enough to write just the first frame
                Frame frame = clientMessage.getStartFrame();
                buffer.putInt(Integer.MIN_VALUE);
                buffer.putShort((short) (frame.flags));
                buffer.put(frame.content);
                os.write(TestUtil.byteBufferToBytes(buffer));
                os.flush();
                assertThatThrownBy(() -> ClientTestUtil.readResponse(is)).is(connectionClosedException());
            }
        }
    }

    private String createString(int length) {
        return new String(new char[length]).replace('\0', 'a');
    }

    @Test
    public void testAccumulatedMessageSizeOverflow() throws IOException {
        Config config = smallInstanceConfig();
        HazelcastInstance hz = factory.newHazelcastInstance(config);
        ClientMessage clientMessage = createAuthenticationMessage(hz, "");
        InetSocketAddress address = getNode(hz).getLocalMember().getSocketAddress(EndpointQualifier.CLIENT);
        try (Socket socket = new Socket(address.getAddress(), address.getPort())) {
            try (OutputStream os = socket.getOutputStream(); InputStream is = socket.getInputStream()) {
                os.write(CLIENT_BINARY.getBytes(StandardCharsets.UTF_8));
                // it should be enough to write just the first frame
                byte[] firstFrameBytes = ClientTestUtil.frameAsBytes(clientMessage.getStartFrame(), false);
                os.write(firstFrameBytes);
                ByteBuffer buffer = ByteBuffer.allocateDirect(SIZE_OF_FRAME_LENGTH_AND_FLAGS);
                buffer.order(ByteOrder.LITTLE_ENDIAN);
                // try to cause the size accumulator overflow
                buffer.putInt(Integer.MAX_VALUE - firstFrameBytes.length + 1);
                ClientMessage.ForwardFrameIterator iterator = clientMessage.frameIterator();
                // skip start frame
                iterator.next();
                Frame frame = iterator.next();
                buffer.putShort((short) frame.flags);
                os.write(TestUtil.byteBufferToBytes(buffer));
                os.flush();
                assertThatThrownBy(() -> ClientTestUtil.readResponse(is)).is(connectionClosedException());
            }
        }
    }

    private ClientMessage createAuthenticationMessage(HazelcastInstance hz, String clientName) {
        return ClientAuthenticationCodec.encodeRequest(hz.getConfig().getClusterName(), null, null, UUID.randomUUID(), "FOO",
                (byte) 1, clientName, "xxx", emptyList(), (byte) 1, false);
    }

    private <T> Condition<T> connectionClosedException() {
        Predicate<T> predicate = e -> e instanceof SocketException || e instanceof EOFException;
        return new Condition<>(predicate, "is connection closed exception");
    }
}
