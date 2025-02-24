/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.mqttclient.spool.Spool;
import com.aws.greengrass.mqttclient.spool.SpoolMessage;
import com.aws.greengrass.mqttclient.spool.SpoolerStoreException;
import com.aws.greengrass.security.SecurityService;
import com.aws.greengrass.security.exceptions.MqttConnectionProviderException;
import com.aws.greengrass.util.BatchedSubscriber;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.LockScope;
import com.aws.greengrass.util.ProxyUtils;
import com.aws.greengrass.util.Utils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.AccessLevel;
import lombok.Getter;
import software.amazon.awssdk.crt.http.HttpProxyOptions;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.ClientTlsContext;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.HostResolver;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.crt.io.TlsContextOptions;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.io.Closeable;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_MQTT_NAMESPACE;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_AWS_REGION;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_CERTIFICATE_FILE_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_IOT_DATA_ENDPOINT;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_PRIVATE_KEY_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;
import static com.aws.greengrass.mqttclient.AwsIotMqttClient.TOPIC_KEY;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals"})
public class MqttClient implements Closeable {
    private static final Logger logger = LogManager.getLogger(MqttClient.class);
    private static final String MQTT_KEEP_ALIVE_TIMEOUT_KEY = "keepAliveTimeoutMs";
    private static final int DEFAULT_MQTT_KEEP_ALIVE_TIMEOUT = (int) Duration.ofSeconds(60).toMillis();
    private static final String MQTT_PING_TIMEOUT_KEY = "pingTimeoutMs";
    private static final int DEFAULT_MQTT_PING_TIMEOUT = (int) Duration.ofSeconds(30).toMillis();
    private static final String MQTT_THREAD_POOL_SIZE_KEY = "threadPoolSize";
    public static final int DEFAULT_MQTT_PORT = 8883;
    public static final String MQTT_PORT_KEY = "port";
    private static final String MQTT_SOCKET_TIMEOUT_KEY = "socketTimeoutMs";
    // Default taken from AWS SDK
    private static final int DEFAULT_MQTT_SOCKET_TIMEOUT = (int) Duration.ofSeconds(3).toMillis();
    static final String MQTT_OPERATION_TIMEOUT_KEY = "operationTimeoutMs";
    static final int DEFAULT_MQTT_OPERATION_TIMEOUT = (int) Duration.ofSeconds(30).toMillis();
    static final int DEFAULT_MQTT_CLOSE_TIMEOUT = (int) Duration.ofSeconds(2).toMillis();
    static final String MQTT_MAX_IN_FLIGHT_PUBLISHES_KEY = "maxInFlightPublishes";
    static final int DEFAULT_MAX_IN_FLIGHT_PUBLISHES = 5;
    public static final int MAX_SUBSCRIPTIONS_PER_CONNECTION = 50;
    public static final String CLIENT_ID_KEY = "clientId";
    static final int IOT_MAX_LIMIT_IN_FLIGHT_OF_QOS1_PUBLISHES = 100;
    static final String MQTT_MAX_OF_MESSAGE_SIZE_IN_BYTES_KEY = "maxMessageSizeInBytes";
    static final String MQTT_MAX_OF_PUBLISH_RETRY_COUNT_KEY = "maxPublishRetry";
    static final int DEFAULT_MQTT_MAX_OF_PUBLISH_RETRY_COUNT = 100;
    // http://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718023
    public static final int MQTT_MAX_LIMIT_OF_MESSAGE_SIZE_IN_BYTES = 256 * 1024 * 1024; // 256 MB
    // https://docs.aws.amazon.com/general/latest/gr/iot-core.html#limits_iot
    public static final int DEFAULT_MQTT_MAX_OF_MESSAGE_SIZE_IN_BYTES = 128 * 1024; // 128 kB
    public static final int MAX_NUMBER_OF_FORWARD_SLASHES = 7;
    public static final int MAX_LENGTH_OF_TOPIC = 256;

    public static final String CONNECT_LIMIT_PERMITS_FEATURE = "connectLimitPermits";

    // Use read lock for MQTT operations and write lock when changing the MQTT connection
    private final ReadWriteLock connectionLock = new ReentrantReadWriteLock(true);
    private final DeviceConfiguration deviceConfiguration;
    private final Topics mqttTopics;
    private final AtomicReference<Future<?>> reconfigureFuture = new AtomicReference<>();
    @SuppressWarnings("PMD.ImmutableField")
    private Function<ClientBootstrap, AwsIotMqttConnectionBuilder> builderProvider;
    @Getter(AccessLevel.PACKAGE)
    private final List<AwsIotMqttClient> connections = new CopyOnWriteArrayList<>();
    private final Map<SubscribeRequest, AwsIotMqttClient> subscriptions = new ConcurrentHashMap<>();
    private final Map<MqttTopic, AwsIotMqttClient> subscriptionTopics = new ConcurrentHashMap<>();
    private final AtomicInteger connectionRoundRobin = new AtomicInteger(0);
    @Getter
    private final AtomicBoolean mqttOnline = new AtomicBoolean(false);
    private final Object httpProxyLock = new Object();

    private final EventLoopGroup eventLoopGroup;
    private final HostResolver hostResolver;
    private final ClientBootstrap clientBootstrap;
    private final CallbackEventManager callbackEventManager = new CallbackEventManager();
    private final Spool spool;
    private final ExecutorService executorService;

    private TlsContextOptions proxyTlsOptions;
    private ClientTlsContext proxyTlsContext;
    private String rootCaPath;

    private ScheduledExecutorService ses;
    private final AtomicReference<Future<?>> spoolingFuture = new AtomicReference<>();
    private int maxInFlightPublishes;
    private static final String reservedTopicTemplate = "^\\$aws/rules/\\S+/\\S+";
    private static final String prefixOfReservedTopic = "^\\$aws/rules/\\S+?/";
    private int maxPublishRetryCount;
    private int maxPublishMessageSize;

    @Getter(AccessLevel.PROTECTED)
    private final MqttClientConnectionEvents callbacks = new MqttClientConnectionEvents() {
        @Override
        public void onConnectionInterrupted(int errorCode) {
            setMqttOnline(false);
            if (!spool.getSpoolConfig().isKeepQos0WhenOffline()) {
                spool.popOutMessagesWithQosZero();
            }
        }

        @Override
        public void onConnectionResumed(boolean sessionPresent) {
            setMqttOnline(true);
            triggerSpooler();
        }
    };

    private final CallbackEventManager.OnConnectCallback onConnect = callbacks::onConnectionResumed;

    //
    // TODO: [P41214930] Handle timeouts and retries
    //

    /**
     * Constructor for injection.
     *
     * @param deviceConfiguration device configuration
     * @param ses                 scheduled executor service
     * @param executorService     executor service
     * @param securityService     security service
     */
    @Inject
    @SuppressWarnings("PMD.PreserveStackTrace")
    public MqttClient(DeviceConfiguration deviceConfiguration, ScheduledExecutorService ses,
                      ExecutorService executorService, SecurityService securityService) {
        this(deviceConfiguration, null, ses, executorService);

        this.builderProvider = (clientBootstrap) -> {
            AwsIotMqttConnectionBuilder builder;
            try {
                builder = securityService.getDeviceIdentityMqttConnectionBuilder();
            } catch (MqttConnectionProviderException e) {
                throw new MqttException(e.getMessage());
            }
            builder.withCertificateAuthorityFromPath(null, Coerce.toString(deviceConfiguration.getRootCAFilePath()))
                    .withEndpoint(Coerce.toString(deviceConfiguration.getIotDataEndpoint()))
                    .withPort((short) Coerce.toInt(mqttTopics.findOrDefault(DEFAULT_MQTT_PORT, MQTT_PORT_KEY)))
                    .withCleanSession(false).withBootstrap(clientBootstrap).withKeepAliveMs(Coerce.toInt(
                            mqttTopics.findOrDefault(DEFAULT_MQTT_KEEP_ALIVE_TIMEOUT, MQTT_KEEP_ALIVE_TIMEOUT_KEY)))
                    .withProtocolOperationTimeoutMs(getMqttOperationTimeoutMillis())
                    .withPingTimeoutMs(
                            Coerce.toInt(mqttTopics.findOrDefault(DEFAULT_MQTT_PING_TIMEOUT, MQTT_PING_TIMEOUT_KEY)))
                    .withSocketOptions(new SocketOptions()).withTimeoutMs(Coerce.toInt(
                            mqttTopics.findOrDefault(DEFAULT_MQTT_SOCKET_TIMEOUT, MQTT_SOCKET_TIMEOUT_KEY)));
            synchronized (httpProxyLock) {
                HttpProxyOptions httpProxyOptions =
                        ProxyUtils.getHttpProxyOptions(deviceConfiguration, proxyTlsContext);
                if (httpProxyOptions != null) {
                    builder.withHttpProxyOptions(httpProxyOptions);
                }
            }
            return builder;
        };
    }

    protected MqttClient(DeviceConfiguration deviceConfiguration,
                         Function<ClientBootstrap, AwsIotMqttConnectionBuilder> builderProvider,
                         ScheduledExecutorService ses, ExecutorService executorService) {
        this.deviceConfiguration = deviceConfiguration;
        this.executorService = executorService;
        this.ses = ses;
        rootCaPath = Coerce.toString(deviceConfiguration.getRootCAFilePath());
        this.proxyTlsOptions = getTlsContextOptions(rootCaPath);
        this.proxyTlsContext = new ClientTlsContext(proxyTlsOptions);

        mqttTopics = this.deviceConfiguration.getMQTTNamespace();
        this.builderProvider = builderProvider;
        validateAndSetMqttPublishConfiguration();

        eventLoopGroup = new EventLoopGroup(Coerce.toInt(mqttTopics.findOrDefault(1, MQTT_THREAD_POOL_SIZE_KEY)));
        hostResolver = new HostResolver(eventLoopGroup);
        clientBootstrap = new ClientBootstrap(eventLoopGroup, hostResolver);
        spool = new Spool(deviceConfiguration);
        callbackEventManager.addToCallbackEvents(onConnect, callbacks);

        // Call getters for all of these topics prior to subscribing to changes so that these namespaces
        // are created and fully updated so that our reconnection doesn't get triggered falsely
        deviceConfiguration.getRootCAFilePath();
        deviceConfiguration.getCertificateFilePath();
        deviceConfiguration.getPrivateKeyFilePath();
        deviceConfiguration.getSpoolerNamespace();
        deviceConfiguration.getAWSRegion();

        // If anything in the device configuration changes, then we will need to reconnect to the cloud
        // using the new settings. We do this by calling reconnect() on all of our connections
        this.deviceConfiguration.onAnyChange(new BatchedSubscriber((what, node) -> {
            // Skip events that don't change anything
            if (WhatHappened.timestampUpdated.equals(what) || WhatHappened.interiorAdded.equals(what) || node == null) {
                return true;
            }

            // List of configuration nodes that we need to reconfigure for if they change
            if (!(node.childOf(DEVICE_MQTT_NAMESPACE) || node.childOf(DEVICE_PARAM_THING_NAME) || node.childOf(
                    DEVICE_PARAM_IOT_DATA_ENDPOINT) || node.childOf(DEVICE_PARAM_PRIVATE_KEY_PATH) || node.childOf(
                    DEVICE_PARAM_CERTIFICATE_FILE_PATH) || node.childOf(DEVICE_PARAM_ROOT_CA_PATH) || node.childOf(
                    DEVICE_PARAM_AWS_REGION))) {
                return true;
            }

            // Only reconnect when the region changed if the proxy exists
            if (node.childOf(DEVICE_PARAM_AWS_REGION) && !ProxyUtils.isProxyConfigured(deviceConfiguration)) {
                return true;
            }

            logger.atDebug().kv("modifiedNode", node.getFullName()).kv("changeType", what)
                    .log("Reconfiguring MQTT clients");
            return false;
        }, (what) -> {
            validateAndSetMqttPublishConfiguration();

            // Reconnect in separate thread to not block publish thread
            // Schedule the reconnection for slightly in the future to de-dupe multiple changes
            Future<?> oldFuture = reconfigureFuture.getAndSet(ses.schedule(() -> {
                // If the rootCa path changed, then we need to update the TLS options
                String newRootCaPath = Coerce.toString(deviceConfiguration.getRootCAFilePath());
                synchronized (httpProxyLock) {
                    if (!Objects.equals(rootCaPath, newRootCaPath)) {
                        if (proxyTlsOptions != null) {
                            proxyTlsOptions.close();
                        }
                        if (proxyTlsContext != null) {
                            proxyTlsContext.close();
                        }
                        rootCaPath = newRootCaPath;
                        proxyTlsOptions = getTlsContextOptions(rootCaPath);
                        proxyTlsContext = new ClientTlsContext(proxyTlsOptions);
                    }
                }

                // Continually try to reconnect until all the connections are reconnected
                Set<AwsIotMqttClient> brokenConnections = new CopyOnWriteArraySet<>(connections);
                do {
                    for (AwsIotMqttClient connection : brokenConnections) {
                        if (Thread.currentThread().isInterrupted()) {
                            return;
                        }

                        try {
                            connection.reconnect();
                            brokenConnections.remove(connection);
                        } catch (InterruptedException | ExecutionException | TimeoutException e) {
                            logger.atError().setCause(e).kv(CLIENT_ID_KEY, connection.getClientId())
                                    .log("Error while reconnecting MQTT client");
                        }
                    }
                } while (!brokenConnections.isEmpty());
            }, 1, TimeUnit.SECONDS));

            // If a reconfiguration task already existed, then kill it and create a new one
            if (oldFuture != null) {
                oldFuture.cancel(true);
            }
        }));
    }

    /**
     * constructor specific for unit and integration test with spooler.
     *
     * @param deviceConfiguration device configuration
     * @param spool               spooler
     * @param mqttOnline          indicator for whether mqtt is online or not
     * @param builderProvider     builder provider
     * @param executorService     executor service
     */
    public MqttClient(DeviceConfiguration deviceConfiguration, Spool spool, boolean mqttOnline,
                      Function<ClientBootstrap, AwsIotMqttConnectionBuilder> builderProvider,
                      ExecutorService executorService) {

        this.deviceConfiguration = deviceConfiguration;
        mqttTopics = this.deviceConfiguration.getMQTTNamespace();
        eventLoopGroup = new EventLoopGroup(Coerce.toInt(mqttTopics.findOrDefault(1, MQTT_THREAD_POOL_SIZE_KEY)));
        hostResolver = new HostResolver(eventLoopGroup);
        clientBootstrap = new ClientBootstrap(eventLoopGroup, hostResolver);
        this.builderProvider = builderProvider;
        this.spool = spool;
        this.mqttOnline.set(mqttOnline);
        this.executorService = executorService;
        rootCaPath = Coerce.toString(deviceConfiguration.getRootCAFilePath());
        this.proxyTlsOptions = getTlsContextOptions(rootCaPath);
        this.proxyTlsContext = new ClientTlsContext(proxyTlsOptions);
        validateAndSetMqttPublishConfiguration();
    }

    private TlsContextOptions getTlsContextOptions(String rootCaPath) {
        return Utils.isNotEmpty(rootCaPath)
                ? TlsContextOptions.createDefaultClient().withCertificateAuthorityFromPath(null, rootCaPath)
                : TlsContextOptions.createDefaultClient();
    }

    private void validateAndSetMqttPublishConfiguration() {
        maxInFlightPublishes = Coerce.toInt(mqttTopics
                .findOrDefault(DEFAULT_MAX_IN_FLIGHT_PUBLISHES,
                        MQTT_MAX_IN_FLIGHT_PUBLISHES_KEY));
        if (maxInFlightPublishes > IOT_MAX_LIMIT_IN_FLIGHT_OF_QOS1_PUBLISHES) {
            logger.atWarn()
                    .kv(MQTT_MAX_IN_FLIGHT_PUBLISHES_KEY, maxInFlightPublishes)
                    .kv("Max acceptable configuration", IOT_MAX_LIMIT_IN_FLIGHT_OF_QOS1_PUBLISHES)
                    .log("The configuration of {} may hit the AWS IoT Core restricting number of "
                                    + "unacknowledged QoS=1 publish requests per client. "
                                    + "Will change to the maximum allowed setting: {}",
                            MQTT_MAX_IN_FLIGHT_PUBLISHES_KEY, IOT_MAX_LIMIT_IN_FLIGHT_OF_QOS1_PUBLISHES);

            maxInFlightPublishes = IOT_MAX_LIMIT_IN_FLIGHT_OF_QOS1_PUBLISHES;
        }

        maxPublishMessageSize = Coerce.toInt(mqttTopics.findOrDefault(DEFAULT_MQTT_MAX_OF_MESSAGE_SIZE_IN_BYTES,
                MQTT_MAX_OF_MESSAGE_SIZE_IN_BYTES_KEY));
        if (maxPublishMessageSize > MQTT_MAX_LIMIT_OF_MESSAGE_SIZE_IN_BYTES) {
            logger.atWarn().kv(MQTT_MAX_OF_MESSAGE_SIZE_IN_BYTES_KEY, maxPublishMessageSize).kv("Max acceptable "
                    + "configuration", MQTT_MAX_LIMIT_OF_MESSAGE_SIZE_IN_BYTES).log("The configuration of {} "
                            + "exceeds the max limit and will change to the maximum allowed setting: {} bytes",
                    MQTT_MAX_OF_MESSAGE_SIZE_IN_BYTES_KEY, MQTT_MAX_LIMIT_OF_MESSAGE_SIZE_IN_BYTES);
            maxPublishMessageSize = MQTT_MAX_LIMIT_OF_MESSAGE_SIZE_IN_BYTES;
        }

        // if maxPublishRetryCount = -1, publish request would be retried with unlimited times.
        maxPublishRetryCount =  Coerce.toInt(mqttTopics.findOrDefault(DEFAULT_MQTT_MAX_OF_PUBLISH_RETRY_COUNT,
                MQTT_MAX_OF_PUBLISH_RETRY_COUNT_KEY));
    }

    /**
     * Subscribe to a MQTT topic.
     *
     * @param request subscribe request
     * @throws ExecutionException   if an error occurs
     * @throws InterruptedException if the thread is interrupted while subscribing
     * @throws TimeoutException     if the request times out
     */
    @SuppressWarnings("PMD.CloseResource")
    public synchronized void subscribe(SubscribeRequest request)
            throws ExecutionException, InterruptedException, TimeoutException {

        try {
            isValidRequestTopic(request.getTopic());
        } catch (MqttRequestException e) {
            throw new ExecutionException(e);
        }

        if (!deviceConfiguration.isDeviceConfiguredToTalkToCloud()) {
            logger.atError().kv(TOPIC_KEY, request.getTopic())
                    .log("Cannot subscribe because device is configured to run offline");
            return;
        }

        AwsIotMqttClient connection = null;
        // Use the write scope when identifying the subscriptionTopics that exist
        try (LockScope scope = LockScope.lock(connectionLock.writeLock())) {
            // TODO: [P41214973] Handle subscriptions with differing QoS (Upgrade 0->1->2)

            // If none of our existing subscriptions include (through wildcards) the new topic, then
            // go ahead and subscribe to it
            Optional<Map.Entry<MqttTopic, AwsIotMqttClient>> existingConnection =
                    findExistingSubscriberForTopic(request.getTopic());
            if (existingConnection.isPresent()) {
                subscriptions.put(request, existingConnection.get().getValue());
            } else {
                connection = getConnection(true);
                subscriptions.put(request, connection);
            }
        }

        try (LockScope scope = LockScope.lock(connectionLock.readLock())) {
            // Connection isn't null, so we should subscribe to the topic
            if (connection != null) {
                AwsIotMqttClient finalConnection = connection;
                connection.subscribe(request.getTopic(), request.getQos()).whenComplete((i, t) -> {
                    if (t == null) {
                        subscriptionTopics.put(new MqttTopic(request.getTopic()), finalConnection);
                    } else {
                        subscriptions.remove(request);
                        logger.atError().kv(TOPIC_KEY, request.getTopic()).log("Error subscribing", t);
                    }
                }).get(connection.getTimeout(), TimeUnit.MILLISECONDS);
            }
        }
    }

    private Optional<Map.Entry<MqttTopic, AwsIotMqttClient>> findExistingSubscriberForTopic(String topic) {
        return subscriptionTopics.entrySet().stream().filter(s -> s.getKey().isSupersetOf(new MqttTopic(topic)))
                .findAny();
    }

    @SuppressFBWarnings("JLM_JSR166_UTILCONCURRENT_MONITORENTER")
    private void triggerSpooler() {
        // Do not synchronize on MqttClient because that causes a dead lock
        synchronized (spoolingFuture) {
            if (spoolingFuture.get() == null || spoolingFuture.get().isDone()
                    && !spoolingFuture.get().isCancelled()) {
                try {
                    spoolingFuture.set(executorService.submit(this::runSpooler));
                } catch (RejectedExecutionException e) {
                    logger.atWarn().log("Failed to run MQTT spooler", e);
                }
            }
        }
    }

    /**
     * Unsubscribe from a MQTT topic.
     *
     * @param request unsubscribe request
     * @throws ExecutionException   if an error occurs
     * @throws InterruptedException if the thread is interrupted while unsubscribing
     * @throws TimeoutException     if the request times out
     */
    public synchronized void unsubscribe(UnsubscribeRequest request)
            throws ExecutionException, InterruptedException, TimeoutException {
        // Use the write lock because we're modifying the subscriptions and trying to consolidate them
        try (LockScope scope = LockScope.lock(connectionLock.writeLock())) {
            Set<Map.Entry<MqttTopic, AwsIotMqttClient>> deadSubscriptionTopics;
            for (Map.Entry<SubscribeRequest, AwsIotMqttClient> sub : subscriptions.entrySet()) {
                if (sub.getKey().getCallback() == request.getCallback() && sub.getKey().getTopic()
                        .equals(request.getTopic())) {
                    subscriptions.remove(sub.getKey());
                }

            }
            // If we have no remaining subscriptions for a topic, then unsubscribe from it in the cloud
            deadSubscriptionTopics = subscriptionTopics.entrySet().stream().filter(s -> subscriptions.keySet().stream()
                    .noneMatch(sub -> s.getKey().isSupersetOf(new MqttTopic(sub.getTopic()))))
                    .collect(Collectors.toSet());
            if (!deadSubscriptionTopics.isEmpty()) {
                for (Map.Entry<MqttTopic, AwsIotMqttClient> sub : deadSubscriptionTopics) {
                    sub.getValue().unsubscribe(sub.getKey().getTopic()).whenComplete((i, t) -> {
                        if (t == null) {
                            subscriptionTopics.remove(sub.getKey());

                            // Since we changed the cloud subscriptions, we need to recalculate the client to use
                            // for each subscription, since it may have changed
                            subscriptions.entrySet().stream()
                                    // if the cloud clients are the same, and the removed topic covered the topic
                                    // that we're looking at, then recalculate that topic's client
                                    .filter(s -> s.getValue() == sub.getValue() && sub.getKey()
                                            .isSupersetOf(new MqttTopic(s.getKey().getTopic()))).forEach(e -> {
                                // recalculate and replace the client
                                Optional<Map.Entry<MqttTopic, AwsIotMqttClient>> subscriberForTopic =
                                        findExistingSubscriberForTopic(e.getKey().getTopic());
                                if (subscriberForTopic.isPresent()) {
                                    subscriptions.put(e.getKey(), subscriberForTopic.get().getValue());
                                }
                            });
                        } else {
                            logger.atError().kv(TOPIC_KEY, sub.getKey().getTopic()).log("Error unsubscribing", t);
                        }
                    }).get(sub.getValue().getTimeout(), TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    /**
     * Publish to a MQTT topic.
     *
     * @param request publish request
     */
    public CompletableFuture<Integer> publish(PublishRequest request) {
        CompletableFuture<Integer> future = new CompletableFuture<>();

        if (!deviceConfiguration.isDeviceConfiguredToTalkToCloud()) {
            SpoolerStoreException e =
                    new SpoolerStoreException("Cannot publish because device is configured to run offline");
            logger.atDebug().kv("topic", request.getTopic()).log(e.getMessage());
            future.completeExceptionally(e);
            return future;
        }

        try {
            isValidPublishRequest(request);
        } catch (MqttRequestException e) {
            logger.atError().kv("topic", request.getTopic()).log("Invalid publish request: {}", e.getMessage());
            future.completeExceptionally(e);
            return future;
        }

        boolean willDropTheRequest = !mqttOnline.get() && request.getQos().getValue() == 0 && !spool.getSpoolConfig()
                .isKeepQos0WhenOffline();

        if (willDropTheRequest) {
            SpoolerStoreException e = new SpoolerStoreException("Device is offline. Dropping QoS 0 message.");
            logger.atDebug().kv("topic", request.getTopic()).log(e.getMessage());
            future.completeExceptionally(e);
            return future;
        }

        try {
            spool.addMessage(request);
            triggerSpooler();
        } catch (InterruptedException | SpoolerStoreException e) {
            logger.atDebug().log("Fail to add publish request to spooler queue", e);
            future.completeExceptionally(e);
            return future;
        }
        return CompletableFuture.completedFuture(0);
    }

    protected void isValidPublishRequest(PublishRequest request) throws MqttRequestException {
        // Payload size should be smaller than MQTT maximum message size
        int messageSize = request.getPayload().length;
        if (messageSize > maxPublishMessageSize) {
            throw new MqttRequestException(String.format("The publishing message size %d bytes exceeds the "
                    + "configured limit of %d bytes", messageSize, maxPublishMessageSize));
        }

        String topic = request.getTopic();
        // Topic should not contain wildcard characters
        if (topic.contains("#") || topic.contains("+")) {
            throw new MqttRequestException("The topic of publish request should not contain wildcard "
                    + "characters of '#' or '+'");
        }

        isValidRequestTopic(topic);
    }

    protected void isValidRequestTopic(String topic) throws MqttRequestException {
        if (Pattern.matches(reservedTopicTemplate, topic.toLowerCase())) {
            // remove the prefix of "$aws/rules/rule-name/"
            topic = topic.toLowerCase().split(prefixOfReservedTopic, 2)[1];
        }

        // Topic should not have no more than maximum number of forward slashes (/)
        if (topic.chars().filter(num -> num == '/').count() > MAX_NUMBER_OF_FORWARD_SLASHES) {
            String errMsg = String.format("The topic of request must have no "
                    + "more than %d forward slashes (/). This excludes the first 3 slashes in the mandatory segments "
                    + "for Basic Ingest topics ($AWS/rules/rule-name/).", MAX_NUMBER_OF_FORWARD_SLASHES);
            throw new MqttRequestException(errMsg);
        }

        // Check the topic size
        if (topic.length() > MAX_LENGTH_OF_TOPIC) {
            String errMsg = String.format("The topic size of request must be no "
                            + "larger than %d bytes of UTF-8 encoded characters. This excludes the first "
                            + "3 mandatory segments for Basic Ingest topics ($AWS/rules/rule-name/).",
                    MAX_LENGTH_OF_TOPIC);
            throw new MqttRequestException(errMsg);
        }
    }

    @SuppressWarnings({"PMD.AvoidCatchingThrowable", "PMD.PreserveStackTrace"})
    protected CompletableFuture<Integer> publishSingleSpoolerMessage(AwsIotMqttClient connection)
            throws InterruptedException {
        long id = -1L;
        try {
            id = spool.popId();
            SpoolMessage spooledMessage = spool.getMessageById(id);
            PublishRequest request = spooledMessage.getRequest();
            MqttMessage m = new MqttMessage(request.getTopic(), request.getPayload());

            long finalId = id;
            return connection.publish(m, request.getQos(), request.isRetain())
                    .whenComplete((packetId, throwable) -> {
                        // packetId is the SDK assigned ID. Ignore this and instead use the spooler ID
                        if (throwable == null) {
                            spool.removeMessageById(finalId);
                            logger.atTrace().kv("id", finalId).kv("topic", request.getTopic())
                                    .log("Successfully published message");
                        } else {
                            if (maxPublishRetryCount == -1 || spooledMessage.getRetried().getAndIncrement()
                                    < maxPublishRetryCount) {
                                spool.addId(finalId);
                                logger.atError().log("Failed to publish the message via Spooler and will retry",
                                        throwable);
                            } else {
                                logger.atError().log("Failed to publish the message via Spooler"
                                                + " after retried {} times and will drop the message",
                                        maxPublishRetryCount, throwable);
                            }

                        }
                    });
        } catch (Throwable t) {
            // valid id is starting from 0
            if (id >= 0) {
                spool.addId(id);
            }

            if (Utils.getUltimateCause(t) instanceof InterruptedException) {
                throw new InterruptedException("Interrupted while publishing from spooler");
            }

            CompletableFuture<Integer> fut = new CompletableFuture<>();
            fut.completeExceptionally(t);
            return fut;
        }
    }

    /**
     * Iterate the spooler queue to publish all the spooled message.
     */
    @SuppressWarnings({"PMD.AvoidCatchingThrowable", "PMD.CloseResource"})
    @SuppressFBWarnings("JLM_JSR166_UTILCONCURRENT_MONITORENTER")
    protected void runSpooler() {
        // Do not use CompletableFuture.anyOf to wait for this set to have space
        // due to https://bugs.openjdk.java.net/browse/JDK-8160402 this will cause
        // a memory leak. See PR #881 for additional context.
        Set<CompletableFuture<?>> publishRequests = ConcurrentHashMap.newKeySet();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                getConnection(false).connect().get();
                while (mqttOnline.get()) {
                    synchronized (publishRequests) {
                        // Wait for number of outstanding requests to decrease
                        while (publishRequests.size() >= maxInFlightPublishes) {
                            publishRequests.wait();
                        }
                    }

                    // Select connection with minimum time to wait before publishing the next message
                    AwsIotMqttClient connection = getConnection(false);
                    long minimumWaitTimeMicros = connection.getThrottlingWaitTimeMicros();
                    for (AwsIotMqttClient client : connections) {
                        long waitTime = client.getThrottlingWaitTimeMicros();
                        if (waitTime < minimumWaitTimeMicros) {
                            connection = client;
                            minimumWaitTimeMicros = waitTime;
                        }
                    }
                    // Wait here in this thread so that we do not block the AWS CRT's event loop
                    // which could delay the processing of other requests.
                    // After this sleep time we will call acquire to take the tokens from the bucket
                    // since we haven't taken them out yet; we've only queried when we'd be able to take
                    // them without blocking. Since we have done the sleeping here, the acquire
                    // is guaranteed to not block.
                    TimeUnit.MICROSECONDS.sleep(minimumWaitTimeMicros);

                    CompletableFuture<Integer> future = publishSingleSpoolerMessage(connection);
                    publishRequests.add(future);
                    future.whenComplete((i, t) -> {
                        // Notify the possible waiter that the size has changed
                        synchronized (publishRequests) {
                            publishRequests.remove(future);
                            publishRequests.notifyAll();
                        }
                    });
                }
                break;
            } catch (ExecutionException e) {
                logger.atError().log("Error when publishing from spooler", e);
                if (Utils.getUltimateCause(e) instanceof InterruptedException) {
                    logger.atWarn().log("Shutting down spooler task");
                    break;
                }
            } catch (InterruptedException e) {
                logger.atWarn().log("Shutting down spooler task");
                break;
            } catch (Throwable ex) {
                logger.atError().log("Unchecked error when publishing from spooler", ex);
                throw ex;
            }
        }
    }

    @SuppressWarnings("PMD.CloseResource")
    private synchronized AwsIotMqttClient getConnection(boolean forSubscription) {
        // If we have no connections, or our connections are over-subscribed, create a new connection
        if (connections.isEmpty() || forSubscription && connections.stream()
                .noneMatch(AwsIotMqttClient::canAddNewSubscription)) {
            AwsIotMqttClient conn = getNewMqttClient();
            connections.add(conn);
            return conn;
        } else {
            // Check if there are more than 1 connections which can accept new subscriptions.
            if (connections.stream().filter(AwsIotMqttClient::canAddNewSubscription).count() > 1) {
                // Check for, and then close and remove any connection that has no subscriptions or any in progress
                // subscriptions.
                Set<AwsIotMqttClient> closableConnections =
                        connections.stream()
                                .filter(AwsIotMqttClient::isConnectionClosable)
                                .collect(Collectors.toSet());
                for (AwsIotMqttClient closableConnection : closableConnections) {
                    // Leave the last connection alive to use for publishing
                    if (connections.size() == 1) {
                        break;
                    }
                    closableConnection.close();
                    connections.remove(closableConnection);
                }
            } else {
                logger.atTrace().log("Number of connections that can add subscriptions is 1");
            }
        }

        // If this connection is to add a new subscription, then don't provide a connection
        // which is already maxed out on subscriptions
        if (forSubscription) {
            return connections.stream().filter(AwsIotMqttClient::canAddNewSubscription).findAny().get();
        }
        // Get a somewhat random, somewhat round robin connection
        return connections.get(connectionRoundRobin.getAndIncrement() % connections.size());
    }

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    Consumer<MqttMessage> getMessageHandlerForClient(AwsIotMqttClient client) {
        return (message) -> {
            logger.atTrace().kv(CLIENT_ID_KEY, client.getClientId()).kv(TOPIC_KEY, message.getTopic())
                    .log("Received MQTT message");

            // Each subscription is associated with a single AWSIotMqttClient even if this
            // on-device subscription did not cause the cloud connection to be made.
            // By checking that the client matches the client for the subscription, we will
            // prevent duplicate messages occurring due to overlapping subscriptions between
            // multiple clients such as A/B and A/#. Without this, an update to A/B would
            // trigger twice if those 2 subscriptions were in different clients because
            // both will receive the message from the cloud and call this handler.
            Predicate<Map.Entry<SubscribeRequest, AwsIotMqttClient>> subscriptionsMatchingTopic =
                    s -> MqttTopic.topicIsSupersetOf(s.getKey().getTopic(), message.getTopic());

            Set<SubscribeRequest> exactlyMatchingSubs = subscriptions.entrySet().stream()
                    .filter(s -> s.getValue() == client)
                    .filter(subscriptionsMatchingTopic)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            Set<SubscribeRequest> subs = exactlyMatchingSubs;
            if (exactlyMatchingSubs.isEmpty()) {
                // We found no exact matches which means that we received a message on the wrong client, or
                // we had no subscribers at all for the topic. We will now check if there is some subscriber
                // which was in a different client. This can happen for IoT Jobs because they send the update/accepted
                // message back to the same client which sent the update request, and not to the client that has
                // subscribed to the update/accepted topic.

                subs = subscriptions.entrySet().stream()
                        .filter(subscriptionsMatchingTopic)
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toSet());

                if (subs.isEmpty()) {
                    // We found no subscribers at all, so we'll log out an error and exit.
                    logger.atError().kv(TOPIC_KEY, message.getTopic()).kv(CLIENT_ID_KEY, client.getClientId())
                            .log("Somehow got message from topic that no one subscribed to");
                    return;
                } else {
                    // We did find at least one subscriber matching the topic, but it didn't match the client
                    // that we subscribed on. This is weird, but it can be expected for IoT Jobs as explained above.
                    logger.atWarn().kv(TOPIC_KEY, message.getTopic()).kv(CLIENT_ID_KEY, client.getClientId())
                            .log("Got a message from a topic on a different client than what we subscribed with."
                                    + " This is odd, but it isn't a problem");
                }
            }
            subs.forEach((h) -> {
                try {
                    h.getCallback().accept(message);
                } catch (Throwable t) {
                    logger.atError().kv("message", message).kv(CLIENT_ID_KEY, client.getClientId())
                            .log("Unhandled error in MQTT message callback", t);
                }
            });
        };
    }

    protected AwsIotMqttClient getNewMqttClient() {
        // Name client by thingName#<number> except for the first connection which will just be thingName
        String clientId = Coerce.toString(deviceConfiguration.getThingName()) + (connections.isEmpty() ? ""
                : "#" + (connections.size() + 1));
        logger.atDebug().kv("clientId", clientId).log("Getting new MQTT connection");
        return new AwsIotMqttClient(() -> builderProvider.apply(clientBootstrap), this::getMessageHandlerForClient,
                clientId, mqttTopics, callbackEventManager, executorService, ses);
    }

    public boolean connected() {
        return !connections.isEmpty() && connections.stream().anyMatch(AwsIotMqttClient::connected);
    }

    @Override
    public synchronized void close() {
        // Shut down spooler and then no more message will be published
        if (spoolingFuture.get() != null) {
            spoolingFuture.get().cancel(true);
        }

        connections.forEach(AwsIotMqttClient::closeOnShutdown);
        if (proxyTlsOptions != null) {
            proxyTlsOptions.close();
        }
        if (proxyTlsContext != null) {
            proxyTlsContext.close();
        }
        clientBootstrap.close();
        hostResolver.close();
        eventLoopGroup.close();
    }

    public void addToCallbackEvents(MqttClientConnectionEvents callbacks) {
        callbackEventManager.addToCallbackEvents(callbacks);
    }

    public void addToCallbackEvents(CallbackEventManager.OnConnectCallback onConnect,
                                    MqttClientConnectionEvents callbacks) {
        callbackEventManager.addToCallbackEvents(onConnect, callbacks);
    }

    protected void setMqttOnline(boolean networkStatus) {
        mqttOnline.set(networkStatus);
    }

    public int getMqttOperationTimeoutMillis() {
        return Coerce.toInt(mqttTopics.findOrDefault(DEFAULT_MQTT_OPERATION_TIMEOUT, MQTT_OPERATION_TIMEOUT_KEY));
    }
}
