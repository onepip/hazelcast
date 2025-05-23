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

package com.hazelcast.client.impl.spi.impl;

import com.hazelcast.client.HazelcastClientNotActiveException;
import com.hazelcast.client.impl.clientside.HazelcastClientInstanceImpl;
import com.hazelcast.client.impl.connection.ClientConnection;
import com.hazelcast.client.impl.connection.ClientConnectionManager;
import com.hazelcast.client.config.RoutingMode;
import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.impl.protocol.codec.ClientLocalBackupListenerCodec;
import com.hazelcast.client.impl.spi.ClientInvocationService;
import com.hazelcast.client.impl.spi.ClientListenerService;
import com.hazelcast.client.impl.spi.ClientPartitionService;
import com.hazelcast.client.impl.spi.EventHandler;
import com.hazelcast.internal.metrics.Probe;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.exception.TargetDisconnectedException;
import com.hazelcast.spi.impl.executionservice.TaskScheduler;
import com.hazelcast.spi.impl.sequence.CallIdFactory;
import com.hazelcast.spi.impl.sequence.CallIdSequence;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.spi.properties.HazelcastProperty;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

import static com.hazelcast.client.properties.ClientProperty.BACKPRESSURE_BACKOFF_TIMEOUT_MILLIS;
import static com.hazelcast.client.properties.ClientProperty.FAIL_ON_INDETERMINATE_OPERATION_STATE;
import static com.hazelcast.client.properties.ClientProperty.INVOCATION_RETRY_PAUSE_MILLIS;
import static com.hazelcast.client.properties.ClientProperty.INVOCATION_TIMEOUT_SECONDS;
import static com.hazelcast.client.properties.ClientProperty.MAX_CONCURRENT_INVOCATIONS;
import static com.hazelcast.client.properties.ClientProperty.OPERATION_BACKUP_TIMEOUT_MILLIS;
import static com.hazelcast.internal.metrics.MetricDescriptorConstants.CLIENT_METRIC_INVOCATIONS_MAX_CURRENT_INVOCATIONS;
import static com.hazelcast.internal.metrics.MetricDescriptorConstants.CLIENT_METRIC_INVOCATIONS_PENDING_CALLS;
import static com.hazelcast.internal.metrics.MetricDescriptorConstants.CLIENT_METRIC_INVOCATIONS_STARTED_INVOCATIONS;
import static com.hazelcast.internal.metrics.MetricDescriptorConstants.CLIENT_PREFIX_INVOCATIONS;
import static com.hazelcast.internal.metrics.ProbeLevel.MANDATORY;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class ClientInvocationServiceImpl implements ClientInvocationServiceInternal {

    private static final ListenerMessageCodec BACKUP_LISTENER = new ListenerMessageCodec() {
        @Override
        public ClientMessage encodeAddRequest(boolean localOnly) {
            return ClientLocalBackupListenerCodec.encodeRequest();
        }

        @Override
        public UUID decodeAddResponse(ClientMessage clientMessage) {
            return ClientLocalBackupListenerCodec.decodeResponse(clientMessage);
        }

        @Override
        public ClientMessage encodeRemoveRequest(UUID realRegistrationId) {
            return null;
        }

        @Override
        public boolean decodeRemoveResponse(ClientMessage clientMessage) {
            return false;
        }
    };

    private static final HazelcastProperty CLEAN_RESOURCES_MILLIS
            = new HazelcastProperty("hazelcast.client.internal.clean.resources.millis", 100, MILLISECONDS);

    final HazelcastClientInstanceImpl client;

    final ILogger invocationLogger;
    private volatile boolean isShutdown;

    @Probe(name = CLIENT_METRIC_INVOCATIONS_PENDING_CALLS, level = MANDATORY)
    private final ConcurrentMap<Long, ClientInvocation> invocations = new ConcurrentHashMap<>();
    private final ClientResponseHandlerSupplier responseHandlerSupplier;
    private final long invocationTimeoutMillis;
    private final long invocationRetryPauseMillis;
    private final CallIdSequence callIdSequence;
    private final boolean shouldFailOnIndeterminateOperationState;
    private final int operationBackupTimeoutMillis;
    private final boolean isBackupAckToClientEnabled;
    private final ClientConnectionManager connectionManager;
    private final ClientPartitionService partitionService;
    private final RoutingMode routingMode;

    public ClientInvocationServiceImpl(HazelcastClientInstanceImpl client) {
        this.client = client;
        this.invocationLogger = client.getLoggingService().getLogger(ClientInvocationService.class);
        this.invocationTimeoutMillis = initInvocationTimeoutMillis();
        this.invocationRetryPauseMillis = initInvocationRetryPauseMillis();
        this.responseHandlerSupplier = new ClientResponseHandlerSupplier(this, client.getConcurrencyDetection());
        HazelcastProperties properties = client.getProperties();
        this.callIdSequence = CallIdFactory.newCallIdSequence(
                properties.getInteger(MAX_CONCURRENT_INVOCATIONS),
                properties.getLong(BACKPRESSURE_BACKOFF_TIMEOUT_MILLIS),
                client.getConcurrencyDetection());

        this.operationBackupTimeoutMillis = properties.getInteger(OPERATION_BACKUP_TIMEOUT_MILLIS);
        this.shouldFailOnIndeterminateOperationState = properties.getBoolean(FAIL_ON_INDETERMINATE_OPERATION_STATE);
        client.getMetricsRegistry().registerStaticMetrics(this, CLIENT_PREFIX_INVOCATIONS);
        this.connectionManager = client.getConnectionManager();
        this.partitionService = client.getClientPartitionService();
        this.routingMode = connectionManager.getRoutingMode();
        this.isBackupAckToClientEnabled = routingMode == RoutingMode.ALL_MEMBERS
                && client.getClientConfig().isBackupAckToClientEnabled();
    }

    @Override
    public ILogger getInvocationLogger() {
        return invocationLogger;
    }

    private long initInvocationRetryPauseMillis() {
        return client.getProperties().getPositiveMillisOrDefault(INVOCATION_RETRY_PAUSE_MILLIS);
    }

    private long initInvocationTimeoutMillis() {
        return client.getProperties().getPositiveMillisOrDefault(INVOCATION_TIMEOUT_SECONDS);
    }

    @Probe(name = CLIENT_METRIC_INVOCATIONS_STARTED_INVOCATIONS, level = MANDATORY)
    private long startedInvocations() {
        return callIdSequence.getLastCallId();
    }

    @Probe(name = CLIENT_METRIC_INVOCATIONS_MAX_CURRENT_INVOCATIONS, level = MANDATORY)
    private long maxCurrentInvocations() {
        return callIdSequence.getMaxConcurrentInvocations();
    }

    @Override
    public long getInvocationTimeoutMillis() {
        return invocationTimeoutMillis;
    }

    @Override
    public long getInvocationRetryPauseMillis() {
        return invocationRetryPauseMillis;
    }

    @Override
    public CallIdSequence getCallIdSequence() {
        return callIdSequence;
    }

    public void addBackupListener() {
        if (isBackupAckToClientEnabled) {
            ClientListenerService listenerService = client.getListenerService();
            listenerService.registerListener(BACKUP_LISTENER, new BackupEventHandler());
        }
    }

    public void start() {
        responseHandlerSupplier.start();
        if (isBackupAckToClientEnabled) {
            TaskScheduler executionService = client.getTaskScheduler();
            long cleanResourcesMillis = client.getProperties().getPositiveMillisOrDefault(CLEAN_RESOURCES_MILLIS);
            executionService.scheduleWithRepetition(new BackupTimeoutTask(), cleanResourcesMillis,
                    cleanResourcesMillis, MILLISECONDS);
        }
    }

    @Override
    public boolean invokeOnPartitionOwner(ClientInvocation invocation,
                                          int partitionId) {
        UUID partitionOwner = partitionService.getPartitionOwner(partitionId);
        if (partitionOwner == null) {
            if (invocationLogger.isFinestEnabled()) {
                invocationLogger.finest("Partition owner is not assigned yet");
            }
            return false;
        }

        return invokeOnTarget(invocation, partitionOwner);
    }

    @Override
    public boolean invoke(ClientInvocation invocation) {
        ClientConnection connection = connectionManager.getRandomConnection();
        if (connection == null) {
            if (invocationLogger.isFinestEnabled()) {
                invocationLogger.finest("No connection found to invoke");
            }
            return false;
        }
        return send(invocation, connection);
    }

    @Override
    public boolean invokeOnTarget(ClientInvocation invocation,
                                  UUID uuid) {
        assert (uuid != null);

        ClientConnection connection = connectionManager.getActiveConnection(uuid);
        if (connection == null) {
            if (invocationLogger.isFinestEnabled()) {
                invocationLogger.finest("Client is not connected to target : " + uuid);
            }
            return false;
        }
        return send(invocation, connection);
    }

    @Override
    public boolean invokeOnConnection(ClientInvocation invocation, ClientConnection connection) {
        return send(invocation, connection);
    }

    @Override
    public Consumer<ClientMessage> getResponseHandler() {
        return responseHandlerSupplier.get();
    }

    @Override
    public void onConnectionClose(ClientConnection connection) {
        for (ClientInvocation invocation : invocations.values()) {
            if (invocation.getPermissionToNotifyForDeadConnection(connection)) {
                Exception ex = new TargetDisconnectedException(connection.getCloseReason(), connection.getCloseCause());
                invocation.notifyExceptionWithOwnedPermission(ex);
            }
        }
    }

    @Override
    public boolean isConnectionInUse(@Nonnull ClientConnection connection) {
        for (ClientInvocation invocation : invocations.values()) {
            ClientConnection sentConnection = invocation.getSentConnection();
            if (sentConnection == null) {
                // not expecting this case and deemed as indeterminate
                // state, best to return true as if the connection is
                // in use and leave the decision to the next checks
                return true;
            }

            if (sentConnection.equals(connection)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean isRedoOperation() {
        return client.getClientConfig().getNetworkConfig().isRedoOperation();
    }

    @Override
    public boolean isBackupAckToClientEnabled() {
        return isBackupAckToClientEnabled;
    }

    private boolean send(ClientInvocation invocation, ClientConnection connection) {
        if (isShutdown) {
            throw new HazelcastClientNotActiveException();
        }

        ClientMessage clientMessage = invocation.getClientMessage();
        if (isBackupAckToClientEnabled) {
            clientMessage.getStartFrame().flags |= ClientMessage.BACKUP_AWARE_FLAG;
        }

        registerInvocation(invocation, connection);

        //After this is set, a second thread can notify this invocation
        //Connection could be closed. From this point on, we need to reacquire the permission to notify if needed.
        invocation.setSentConnection(connection);

        if (!connection.write(clientMessage)) {
            if (invocation.getPermissionToNotifyForDeadConnection(connection)) {
                IOException exception = new IOException("Packet not sent to " + connection.getRemoteAddress() + " "
                        + clientMessage);
                invocation.notifyExceptionWithOwnedPermission(exception);
            }
        } else {
            invocation.invoked();
        }

        return true;
    }

    // package-visible for tests
    void registerInvocation(ClientInvocation clientInvocation, ClientConnection connection) {
        ClientMessage clientMessage = clientInvocation.getClientMessage();
        long correlationId = clientMessage.getCorrelationId();
        invocations.put(correlationId, clientInvocation);
        EventHandler handler = clientInvocation.getEventHandler();
        if (handler != null) {
            connection.addEventHandler(correlationId, handler);
        }
    }

    @Override
    public void deRegisterInvocation(long callId) {
        invocations.remove(callId);
    }

    ClientInvocation getInvocation(long callId) {
        return invocations.get(callId);
    }

    public boolean isShutdown() {
        return isShutdown;
    }

    public void shutdown() {
        isShutdown = true;
        responseHandlerSupplier.shutdown();

        for (ClientInvocation invocation : invocations.values()) {
            //connection manager and response handler threads are closed at this point.
            invocation.notifyExceptionWithOwnedPermission(new HazelcastClientNotActiveException());
        }
    }

    @Override
    public void checkInvocationAllowed() throws IOException {
        connectionManager.checkInvocationAllowed();
    }

    @Override
    public void checkUrgentInvocationAllowed(ClientInvocation invocation) {
        if (connectionManager.clientInitializedOnCluster()) {
            // If the client is initialized on the cluster, that means we
            // have sent all the schemas to the cluster, even if we are
            // reconnected to it
            return;
        }

        if (!client.shouldCheckUrgentInvocations()) {
            // If there were no Compact schemas to begin with, we don't need
            // to perform the check below. If the client didn't send a Compact
            // schema up until this point, the retries or listener registrations
            // could not send a schema, because if they were, we wouldn't hit
            // this line.
            return;
        }

        // We are not yet initialized on cluster, so the Compact schemas might
        // not be sent yet. This message contains some serialized classes,
        // and it is possible that it can also contain Compact serialized data.
        // In that case, allowing this invocation to go through now could
        // violate the invariant that the schema must come to cluster before
        // the data. We will retry this invocation and wait until the client
        // is initialized on the cluster, which means schemas are replicated
        // in the cluster.
        if (invocation.getClientMessage().isContainsSerializedDataInRequest()) {
            throw new InvocationMightContainCompactDataException(invocation);
        }
    }

    @Override
    public boolean shouldFailOnIndeterminateOperationState() {
        return shouldFailOnIndeterminateOperationState;
    }

    @Override
    public RoutingMode getRoutingMode() {
        return routingMode;
    }

    private class BackupTimeoutTask implements Runnable {
        @Override
        public void run() {
            for (ClientInvocation invocation : invocations.values()) {
                invocation.detectAndHandleBackupTimeout(operationBackupTimeoutMillis);
            }
        }
    }

    public class BackupEventHandler extends ClientLocalBackupListenerCodec.AbstractEventHandler
            implements EventHandler<ClientMessage> {

        @Override
        public void handleBackupEvent(long sourceInvocationCorrelationId) {
            ClientInvocation invocation = getInvocation(sourceInvocationCorrelationId);
            if (invocation == null) {
                if (invocationLogger.isFinestEnabled()) {
                    invocationLogger.finest("Invocation not found for backup event, invocation id "
                            + sourceInvocationCorrelationId);
                }
                return;
            }
            invocation.notifyBackupComplete();
        }
    }
}
