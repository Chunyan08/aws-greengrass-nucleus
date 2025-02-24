/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.config.ChildChanged;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.mqttclient.spool.Spool;
import com.aws.greengrass.mqttclient.spool.SpoolMessage;
import com.aws.greengrass.mqttclient.spool.SpoolerConfig;
import com.aws.greengrass.mqttclient.spool.SpoolerStorageType;
import com.aws.greengrass.mqttclient.spool.SpoolerStoreException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.testing.TestFeatureParameterInterface;
import com.aws.greengrass.testing.TestFeatureParameters;
import com.aws.greengrass.util.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_MQTT_NAMESPACE;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_AWS_REGION;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_CERTIFICATE_FILE_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_IOT_DATA_ENDPOINT;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_PRIVATE_KEY_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;
import static com.aws.greengrass.mqttclient.MqttClient.CONNECT_LIMIT_PERMITS_FEATURE;
import static com.aws.greengrass.mqttclient.MqttClient.DEFAULT_MQTT_MAX_OF_PUBLISH_RETRY_COUNT;
import static com.aws.greengrass.mqttclient.MqttClient.MAX_LENGTH_OF_TOPIC;
import static com.aws.greengrass.mqttclient.MqttClient.MAX_NUMBER_OF_FORWARD_SLASHES;
import static com.aws.greengrass.mqttclient.MqttClient.MQTT_MAX_LIMIT_OF_MESSAGE_SIZE_IN_BYTES;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static com.aws.greengrass.testcommons.testutilities.TestUtils.asyncAssertOnConsumer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.either;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
@SuppressWarnings("PMD.CloseResource")
class MqttClientTest {
    @Mock
    AwsIotMqttConnectionBuilder builder;

    @Mock
    DeviceConfiguration deviceConfiguration;

    @Mock
    MqttClientConnection mockConnection;

    @Mock
    Spool spool;

    @Mock
    private TestFeatureParameterInterface DEFAULT_HANDLER;

    ScheduledExecutorService ses = new ScheduledThreadPoolExecutor(1);
    ExecutorService executorService = TestUtils.synchronousExecutorService();

    Configuration config = new Configuration(new Context());
    private final Consumer<MqttMessage> cb = (m) -> {
    };
    private final static String reservedTopicPrefix = "$AWS/rules/rule_name/";

    @BeforeEach
    void beforeEach() {
        lenient().when(DEFAULT_HANDLER.retrieveWithDefault(eq(Double.class), eq(CONNECT_LIMIT_PERMITS_FEATURE), any()))
                .thenReturn(Double.MAX_VALUE);
        TestFeatureParameters.internalEnableTestingFeatureParameters(DEFAULT_HANDLER);
        Topics mqttNamespace = config.lookupTopics("mqtt");
        Topics spoolerNamespace = config.lookupTopics("spooler");
        mqttNamespace.lookup(MqttClient.MQTT_OPERATION_TIMEOUT_KEY).withValue(0);
        mqttNamespace.lookup(MqttClient.MQTT_MAX_IN_FLIGHT_PUBLISHES_KEY)
                .withValue(MqttClient.IOT_MAX_LIMIT_IN_FLIGHT_OF_QOS1_PUBLISHES + 1);
        mqttNamespace.lookup(MqttClient.MQTT_MAX_OF_MESSAGE_SIZE_IN_BYTES_KEY)
                .withValue(MQTT_MAX_LIMIT_OF_MESSAGE_SIZE_IN_BYTES + 1);
        when(deviceConfiguration.getMQTTNamespace()).thenReturn(mqttNamespace);
        lenient().when(deviceConfiguration.isDeviceConfiguredToTalkToCloud()).thenReturn(true);
        lenient().when(deviceConfiguration.getSpoolerNamespace()).thenReturn(spoolerNamespace);
        lenient().when(builder.build()).thenReturn(mockConnection);
        lenient().when(mockConnection.connect()).thenReturn(CompletableFuture.completedFuture(false));
        lenient().when(mockConnection.disconnect()).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(mockConnection.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        lenient().when(mockConnection.unsubscribe(any())).thenReturn(CompletableFuture.completedFuture(0));
        lenient().when(mockConnection.publish(any(), any(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(0));
    }

    @AfterEach
    void afterEach() throws IOException {
        config.context.close();
        ses.shutdownNow();
        executorService.shutdownNow();
        TestFeatureParameters.internalDisableTestingFeatureParameters();
    }

    @Test
    void GIVEN_device_not_configured_to_talk_to_cloud_WHEN_publish_THEN_throws_exception()
            throws InterruptedException {
        lenient().when(deviceConfiguration.isDeviceConfiguredToTalkToCloud()).thenReturn(false);
        MqttClient client = new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService);
        PublishRequest testRequest =
                PublishRequest.builder().topic("test").qos(QualityOfService.AT_LEAST_ONCE).payload(new byte[0]).build();
        try {
            client.publish(testRequest).whenComplete((r, t) -> {
                assertNotNull(t);
                assertTrue(t.getCause() instanceof SpoolerStoreException);
            }).get();
        } catch (ExecutionException e) {
            // Ignore. Expected to throw and already handled
        }
    }

    @Test
    void GIVEN_multiple_subset_subscriptions_WHEN_subscribe_or_unsubscribe_THEN_only_subscribes_and_unsubscribes_once()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService);
        assertFalse(client.connected());

        client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback(cb).build());

        verify(mockConnection, times(2)).connect();
        verify(mockConnection).subscribe(eq("A/B/+"), eq(QualityOfService.AT_LEAST_ONCE));

        // This subscription shouldn't actually subscribe through the cloud because it is a subset of the previous sub
        client.subscribe(SubscribeRequest.builder().topic("A/B/C").callback(cb).build());
        verify(mockConnection, times(0)).subscribe(eq("A/B/C"), eq(QualityOfService.AT_LEAST_ONCE));

        // Even though we unsub locally, it should keep the cloud sub because a different on-device client needs it
        client.unsubscribe(UnsubscribeRequest.builder().topic("A/B/+").callback(cb).build());
        verify(mockConnection, times(0)).unsubscribe(any());

        // Now that we've unsubbed on device it can unsub from the cloud
        client.unsubscribe(UnsubscribeRequest.builder().topic("A/B/C").callback(cb).build());
        verify(mockConnection, times(1)).unsubscribe(eq("A/B/+"));
    }

    @Test
    void GIVEN_connection_WHEN_subscribe_timesout_but_then_completes_THEN_subsequent_subscribe_calls_dont_call_cloud()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService);

        assertFalse(client.connected());
        CompletableFuture<Integer> cf = new CompletableFuture<>();
        lenient().when(mockConnection.subscribe(any(), any())).thenReturn(cf);

        assertThrows(TimeoutException.class,
                () -> client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback(cb).build()));
        cf.complete(0);

        // This subscribe call won't result in a cloud call because the previous subscribe succeeded _after_
        // the timeout
        client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback(cb).build());
        verify(mockConnection, times(2)).connect();
        verify(mockConnection).subscribe(eq("A/B/+"), eq(QualityOfService.AT_LEAST_ONCE));
    }

    @Test
    void GIVEN_connection_WHEN_settings_change_THEN_reconnects_on_valid_changes()
            throws InterruptedException, ExecutionException, TimeoutException {
        ArgumentCaptor<ChildChanged> cc = ArgumentCaptor.forClass(ChildChanged.class);
        doNothing().when(deviceConfiguration).onAnyChange(cc.capture());
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses, executorService));

        AwsIotMqttClient iClient1 = mock(AwsIotMqttClient.class);
        when(iClient1.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(client.getNewMqttClient()).thenReturn(iClient1);

        // no reconnect if no connections
        cc.getValue().childChanged(WhatHappened.childChanged, config.lookupTopics("test1"));
        verify(iClient1, never()).reconnect();

        client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback(cb).build());

        // no reconnect if unrelated node changes
        cc.getValue().childChanged(WhatHappened.childChanged, config.lookupTopics("test2"));
        verify(iClient1, never()).reconnect();

        // no reconnect if aws region changed but no proxy configured
        cc.getValue().childChanged(WhatHappened.childChanged, config.lookupTopics(DEVICE_PARAM_AWS_REGION));
        verify(iClient1, never()).reconnect();

        // do reconnect if changed node is relevant to client config and reconnect is required
        // this increases branch coverage
        List<String> topicsToTest = Arrays.asList(DEVICE_MQTT_NAMESPACE, DEVICE_PARAM_THING_NAME,
                DEVICE_PARAM_IOT_DATA_ENDPOINT, DEVICE_PARAM_PRIVATE_KEY_PATH, DEVICE_PARAM_CERTIFICATE_FILE_PATH,
                DEVICE_PARAM_ROOT_CA_PATH);
        int reconnectCount = 0;
        for (String topic : topicsToTest) {
            cc.getValue().childChanged(WhatHappened.childChanged, config.lookupTopics(topic, "test"));
            verify(iClient1, timeout(5000).times(++reconnectCount)).reconnect();
        }

        client.close();
        verify(iClient1).closeOnShutdown();
    }

    @Test
    void GIVEN_connection_has_50_subscriptions_THEN_new_connection_added_as_needed()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses, executorService));
        AwsIotMqttClient iClient1 = mock(AwsIotMqttClient.class);
        AwsIotMqttClient iClient2 = mock(AwsIotMqttClient.class);
        when(iClient1.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(iClient2.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(client.getNewMqttClient()).thenReturn(iClient1).thenReturn(iClient2);
        when(iClient1.canAddNewSubscription()).thenReturn(false);

        // Have the MQTT client load the client with 50 subscriptions
        client.subscribe(SubscribeRequest.builder().topic("A").callback(cb).build());
        client.subscribe(SubscribeRequest.builder().topic("B").callback(cb).build());

        verify(client, times(3)).getNewMqttClient();
    }

    @Test
    void GIVEN_connection_has_0_subscriptions_THEN_all_but_last_connection_will_be_closed()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses, executorService));
        AwsIotMqttClient iClient1 = mock(AwsIotMqttClient.class);
        AwsIotMqttClient iClient2 = mock(AwsIotMqttClient.class);
        when(iClient1.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(iClient2.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(client.getNewMqttClient()).thenReturn(iClient1).thenReturn(iClient2);
        when(iClient1.canAddNewSubscription()).thenReturn(false);

        client.subscribe(SubscribeRequest.builder().topic("A").callback(cb).build());
        client.subscribe(SubscribeRequest.builder().topic("B").callback(cb).build());

        verify(client, times(3)).getNewMqttClient();
        verify(iClient1, times(0)).close();
        verify(iClient2, times(0)).close();
    }

    @Test
    void GIVEN_incoming_message_WHEN_received_THEN_subscribers_are_called()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses, executorService));
        AwsIotMqttClient mockIndividual = mock(AwsIotMqttClient.class);
        when(mockIndividual.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(client.getNewMqttClient()).thenReturn(mockIndividual);
        assertFalse(client.connected());

        // Subscribe with wildcard first so that that is the active cloud subscription.
        // Then subscribe to 2 other topics which are included in the wildcard.
        // Then show that each subscription here is called only for the topic that it
        // subscribed to.

        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abPlus = asyncAssertOnConsumer((m) -> {
            assertThat(m.getTopic(), either(is("A/B/C")).or(is("A/B/D")));
        }, 2);
        client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback(abPlus.getRight()).build());
        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abc = asyncAssertOnConsumer((m) -> {
            assertEquals("A/B/C", m.getTopic());
        }, 2);
        client.subscribe(SubscribeRequest.builder().topic("A/B/C").callback(abc.getRight()).build());
        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abd = asyncAssertOnConsumer((m) -> {
            assertEquals("A/B/D", m.getTopic());
        });
        client.subscribe(SubscribeRequest.builder().topic("A/B/D").callback(abd.getRight()).build());

        Consumer<MqttMessage> handler = client.getMessageHandlerForClient(mockIndividual);

        handler.accept(new MqttMessage("A/B/C", new byte[0]));
        handler.accept(new MqttMessage("A/B/D", new byte[0]));
        handler.accept(new MqttMessage("A/X/Y", new byte[0])); // No subscribers for this one

        abPlus.getLeft().get(0, TimeUnit.SECONDS);
        abd.getLeft().get(0, TimeUnit.SECONDS);

        // Ensure, that even after removing the wildcard subscription, the other topics still get
        // messages
        client.unsubscribe(UnsubscribeRequest.builder().topic("A/B/+").callback(abPlus.getRight()).build());
        handler.accept(new MqttMessage("A/B/C", new byte[0]));
        abc.getLeft().get(0, TimeUnit.SECONDS);
    }

    @Test
    void GIVEN_incoming_message_on_wrong_client_WHEN_received_THEN_subscribers_are_still_called()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses, executorService));
        AwsIotMqttClient mockClient1 = mock(AwsIotMqttClient.class);
        AwsIotMqttClient mockClient2 = mock(AwsIotMqttClient.class);
        when(mockClient1.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        // All subscriptions will go through mockClient1, but we're going to send the messages via mockClient2
        when(client.getNewMqttClient()).thenReturn(mockClient1);
        assertFalse(client.connected());

        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abPlus = asyncAssertOnConsumer((m) -> {
            assertThat(m.getTopic(), either(is("A/B/C")).or(is("A/B/D")));
        }, 2);
        client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback(abPlus.getRight()).build());
        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abc = asyncAssertOnConsumer((m) -> {
            assertEquals("A/B/C", m.getTopic());
        }, 2);
        client.subscribe(SubscribeRequest.builder().topic("A/B/C").callback(abc.getRight()).build());
        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abd = asyncAssertOnConsumer((m) -> {
            assertEquals("A/B/D", m.getTopic());
        });
        client.subscribe(SubscribeRequest.builder().topic("A/B/D").callback(abd.getRight()).build());

        Consumer<MqttMessage> handlerForClient2 = client.getMessageHandlerForClient(mockClient2);

        handlerForClient2.accept(new MqttMessage("A/B/C", new byte[0]));
        handlerForClient2.accept(new MqttMessage("A/B/D", new byte[0]));
        handlerForClient2.accept(new MqttMessage("A/X/Y", new byte[0])); // No subscribers for this one

        abPlus.getLeft().get(0, TimeUnit.SECONDS);
        abd.getLeft().get(0, TimeUnit.SECONDS);

        // Ensure, that even after removing the wildcard subscription, the other topics still get
        // messages
        client.unsubscribe(UnsubscribeRequest.builder().topic("A/B/+").callback(abPlus.getRight()).build());
        handlerForClient2.accept(new MqttMessage("A/B/C", new byte[0]));
        abc.getLeft().get(0, TimeUnit.SECONDS);
    }

    @Test
    void GIVEN_3_connections_with_2_able_accept_new_WHEN_subscribe_THEN_closes_connection_with_no_subscribers()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses, executorService));
        assertFalse(client.connected());
        AwsIotMqttClient mockIndividual1 = mock(AwsIotMqttClient.class);
        AwsIotMqttClient mockIndividual2 = mock(AwsIotMqttClient.class);
        AwsIotMqttClient mockIndividual3 = mock(AwsIotMqttClient.class);
        when(mockIndividual2.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(mockIndividual1.canAddNewSubscription()).thenReturn(false);
        when(mockIndividual2.canAddNewSubscription()).thenReturn(true);
        when(mockIndividual3.canAddNewSubscription()).thenReturn(true);
        when(mockIndividual2.isConnectionClosable()).thenReturn(false);
        when(mockIndividual3.isConnectionClosable()).thenReturn(true);

        client.getConnections().add(mockIndividual1);
        client.getConnections().add(mockIndividual2);
        client.getConnections().add(mockIndividual3);

        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abc = asyncAssertOnConsumer((m) -> {
            assertEquals("A/B/C", m.getTopic());
        }, 1);
        client.subscribe(SubscribeRequest.builder().topic("A/B/C").callback(abc.getRight()).build());

        assertEquals(2, client.getConnections().size());
        verify(mockIndividual1, never()).close();
        verify(mockIndividual2, never()).close();
        verify(mockIndividual3, atMostOnce()).close();
        verify(mockIndividual2, atMostOnce()).subscribe(any(), any());
    }

    @Test
    void GIVEN_3_connections_with_2_able_accept_new_with_in_progress_WHEN_subscribe_THEN_does_not_close_any_connections()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses, executorService));
        assertFalse(client.connected());
        AwsIotMqttClient mockIndividual1 = mock(AwsIotMqttClient.class);
        AwsIotMqttClient mockIndividual2 = mock(AwsIotMqttClient.class);
        AwsIotMqttClient mockIndividual3 = mock(AwsIotMqttClient.class);
        when(mockIndividual2.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(mockIndividual1.canAddNewSubscription()).thenReturn(false);
        when(mockIndividual2.canAddNewSubscription()).thenReturn(true);
        when(mockIndividual3.canAddNewSubscription()).thenReturn(true);
        when(mockIndividual3.isConnectionClosable()).thenReturn(false);

        client.getConnections().add(mockIndividual1);
        client.getConnections().add(mockIndividual2);
        client.getConnections().add(mockIndividual3);

        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abc = asyncAssertOnConsumer((m) -> {
            assertEquals("A/B/C", m.getTopic());
        }, 1);
        client.subscribe(SubscribeRequest.builder().topic("A/B/C").callback(abc.getRight()).build());

        assertEquals(3, client.getConnections().size());
        verify(mockIndividual1, never()).close();
        verify(mockIndividual2, never()).close();
        verify(mockIndividual3, never()).close();
    }

    @Test
    void GIVEN_incoming_messages_to_2clients_WHEN_received_THEN_subscribers_are_called_without_duplication()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses, executorService));
        assertFalse(client.connected());
        AwsIotMqttClient mockIndividual1 = mock(AwsIotMqttClient.class);
        AwsIotMqttClient mockIndividual2 = mock(AwsIotMqttClient.class);
        when(mockIndividual1.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(mockIndividual2.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(client.getNewMqttClient()).thenReturn(mockIndividual1).thenReturn(mockIndividual2);
        when(mockIndividual1.canAddNewSubscription()).thenReturn(false);
        when(mockIndividual2.canAddNewSubscription()).thenReturn(true);

        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abc = asyncAssertOnConsumer((m) -> {
            assertEquals("A/B/C", m.getTopic());
        }, 1);
        client.subscribe(SubscribeRequest.builder().topic("A/B/C").callback(abc.getRight()).build());
        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abd = asyncAssertOnConsumer((m) -> {
            assertEquals("A/B/D", m.getTopic());
        }, 2);
        client.subscribe(SubscribeRequest.builder().topic("A/B/D").callback(abd.getRight()).build());
        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abPlus = asyncAssertOnConsumer((m) -> {
            assertThat(m.getTopic(), either(is("A/B/C")).or(is("A/B/D")).or(is("A/B/F")));
        }, 5);
        client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback(abPlus.getRight()).build());

        Consumer<MqttMessage> handler1 = client.getMessageHandlerForClient(mockIndividual1);
        Consumer<MqttMessage> handler2 = client.getMessageHandlerForClient(mockIndividual2);

        // Send messages to BOTH handler1 and handler2 to show that we appropriately route and don't duplicate
        // messages when multiple overlapping subscriptions exist across individual clients

        // Send all to handler1
        handler1.accept(new MqttMessage("A/B/C", new byte[0]));
        handler1.accept(new MqttMessage("A/B/D", new byte[0]));
        handler1.accept(new MqttMessage("A/B/F", new byte[0]));
        handler1.accept(new MqttMessage("A/X/Y", new byte[0]));

        // Send all the same messages to handler2
        handler2.accept(new MqttMessage("A/B/C", new byte[0]));
        handler2.accept(new MqttMessage("A/B/D", new byte[0]));
        handler2.accept(new MqttMessage("A/B/F", new byte[0]));
        handler2.accept(new MqttMessage("A/X/Y", new byte[0])); // No subscribers for this one

        abPlus.getLeft().get(0, TimeUnit.SECONDS);
        abd.getLeft().get(0, TimeUnit.SECONDS);
        abc.getLeft().get(0, TimeUnit.SECONDS);
    }

    @Test
    void GIVEN_incoming_message_WHEN_received_and_subscriber_throws_THEN_still_calls_remaining_subscriptions(
            ExtensionContext context) throws ExecutionException, InterruptedException, TimeoutException {
        ignoreExceptionWithMessage(context, "Uncaught!");
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses, executorService));
        AwsIotMqttClient mockIndividual = mock(AwsIotMqttClient.class);
        when(mockIndividual.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(client.getNewMqttClient()).thenReturn(mockIndividual);
        assertFalse(client.connected());

        client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback((m) -> {
            throw new RuntimeException("Uncaught!");
        }).build());
        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abc = asyncAssertOnConsumer((m) -> {
            assertEquals("A/B/C", m.getTopic());
        });
        client.subscribe(SubscribeRequest.builder().topic("A/B/C").callback(abc.getRight()).build());

        Consumer<MqttMessage> handler = client.getMessageHandlerForClient(mockIndividual);

        handler.accept(new MqttMessage("A/B/C", new byte[0]));
        abc.getLeft().get(0, TimeUnit.SECONDS);
    }

    @Test
    void GIVEN_keep_qos_0_when_offline_is_false_and_mqtt_is_offline_WHEN_publish_THEN_future_complete_exceptionally()
            throws InterruptedException, SpoolerStoreException {

        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        PublishRequest request = PublishRequest.builder().topic("spool").payload(new byte[0])
                .qos(QualityOfService.AT_MOST_ONCE).build();
        SpoolerConfig config = SpoolerConfig.builder().keepQos0WhenOffline(false)
                .spoolSizeInBytes(25L).storageType(SpoolerStorageType.Memory)
                .build();
        when(spool.getSpoolConfig()).thenReturn(config);

        CompletableFuture<Integer> future = client.publish(request);

        assertTrue(future.isCompletedExceptionally());
        verify(spool, never()).addMessage(request);
    }

    @Test
    void GIVEN_keep_qos_0_when_offline_is_false_and_mqtt_is_online_WHEN_publish_THEN_return_future_complete()
            throws ExecutionException, InterruptedException, SpoolerStoreException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, true, (c) -> builder, executorService));
        PublishRequest request = PublishRequest.builder().topic("spool").payload(new byte[0])
                .qos(QualityOfService.AT_MOST_ONCE).build();
        SpoolMessage message = SpoolMessage.builder().id(0L).request(request).build();

        when(spool.addMessage(request)).thenReturn(message);
        when(spool.popId()).thenThrow(InterruptedException.class);

        CompletableFuture<Integer> future = client.publish(request);

        assertEquals(0, future.get());
        verify(spool, times(1)).addMessage(request);
        verify(spool, never()).getSpoolConfig();
    }

    @Test
    void GIVEN_qos_is_1_and_mqtt_is_offline_WHEN_publish_THEN_return_future_complete()
            throws ExecutionException, InterruptedException, SpoolerStoreException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        PublishRequest request = PublishRequest.builder().topic("spool").payload(new byte[0])
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        SpoolMessage message = SpoolMessage.builder().id(0L).request(request).build();
        when(spool.addMessage(request)).thenReturn(message);

        CompletableFuture<Integer> future = client.publish(request);

        assertEquals(0, future.get());
        verify(spool, times(1)).addMessage(request);
        verify(spool, never()).getSpoolConfig();
    }

    @Test
    void GIVEN_add_message_to_spooler_throw_spooler_load_exception_WHEN_publish_THEN_return_future_complete_exceptionally(ExtensionContext context)
            throws SpoolerStoreException, InterruptedException {

        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        PublishRequest request = PublishRequest.builder().topic("spool").payload(new byte[10])
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        when(spool.addMessage(any())).thenThrow(new SpoolerStoreException("spooler is full"));

        ignoreExceptionOfType(context, SpoolerStoreException.class);
        CompletableFuture<Integer> future = client.publish(request);

        assertTrue(future.isCompletedExceptionally());
        verify(spool, times(1)).addMessage(request);
        verify(spool, never()).getSpoolConfig();
    }

    @Test
    void GIVEN_add_message_to_spooler_throw_interrupted_exception_WHEN_publish_THEN_return_future_complete_exceptionally(ExtensionContext context)
            throws InterruptedException, SpoolerStoreException {

        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        PublishRequest request = PublishRequest.builder().topic("spool").payload(new byte[0])
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        when(spool.addMessage(any())).thenThrow(InterruptedException.class);

        ignoreExceptionOfType(context, InterruptedException.class);
        CompletableFuture<Integer> future = spy(client.publish(request));

        verify(spool, times(1)).addMessage(request);
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void GIVEN_publish_request_successfully_WHEN_spool_single_message_THEN_remove_message_from_spooler_queue()
            throws InterruptedException {

        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, true, (c) -> builder, executorService));
        long id = 1L;
        when(spool.popId()).thenReturn(id);
        PublishRequest request = PublishRequest.builder().topic("spool")
                .payload("What's up".getBytes(StandardCharsets.UTF_8))
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        SpoolMessage message = SpoolMessage.builder().id(id).request(request).build();
        when(spool.getMessageById(id)).thenReturn(message);
        AwsIotMqttClient awsIotMqttClient = mock(AwsIotMqttClient.class);
        when(awsIotMqttClient.publish(any(), any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(0));

        client.publishSingleSpoolerMessage(awsIotMqttClient);

        verify(spool).removeMessageById(anyLong());
        verify(awsIotMqttClient).publish(any(), any(), anyBoolean());
        verify(spool, never()).addId(anyLong());
    }

    @Test
    void GIVEN_publish_request_unsuccessfully_WHEN_spool_single_message_THEN_add_id_back_to_spooler_if_will_retry(ExtensionContext context)
            throws InterruptedException {

        ignoreExceptionOfType(context, ExecutionException.class);

        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, true, (c) -> builder,
                executorService));

        long id = 1L;
        when(spool.popId()).thenReturn(id);
        PublishRequest request = PublishRequest.builder().topic("spool")
                .payload("What's up".getBytes(StandardCharsets.UTF_8))
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        SpoolMessage message = SpoolMessage.builder().id(id).request(request).build();
        when(spool.getMessageById(id)).thenReturn(message);
        AwsIotMqttClient awsIotMqttClient = mock(AwsIotMqttClient.class);
        CompletableFuture<Integer> future = new CompletableFuture<>();
        future.completeExceptionally(new ExecutionException("exception", new Throwable()));
        when(awsIotMqttClient.publish(any(), any(), anyBoolean())).thenReturn(future);

        client.publishSingleSpoolerMessage(awsIotMqttClient);

        verify(awsIotMqttClient).publish(any(), any(), anyBoolean());
        verify(spool, never()).removeMessageById(anyLong());
        verify(spool).addId(anyLong());
    }

    @Test
    void GIVEN_publish_request_unsuccessfully_WHEN_spool_single_message_THEN_not_retry_if_have_retried_max_times(ExtensionContext context)
            throws InterruptedException {

        ignoreExceptionOfType(context, ExecutionException.class);

        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, true, (c) -> builder,
                executorService));

        long id = 1L;
        when(spool.popId()).thenReturn(id);
        PublishRequest request = PublishRequest.builder().topic("spool")
                .payload("What's up".getBytes(StandardCharsets.UTF_8))
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        SpoolMessage message = SpoolMessage.builder().id(id).request(request).build();
        message.getRetried().set(DEFAULT_MQTT_MAX_OF_PUBLISH_RETRY_COUNT);
        when(spool.getMessageById(id)).thenReturn(message);
        AwsIotMqttClient awsIotMqttClient = mock(AwsIotMqttClient.class);
        CompletableFuture<Integer> future = new CompletableFuture<>();
        future.completeExceptionally(new ExecutionException("exception", new Throwable()));
        when(awsIotMqttClient.publish(any(), any(), anyBoolean())).thenReturn(future);

        client.publishSingleSpoolerMessage(awsIotMqttClient);

        verify(awsIotMqttClient).publish(any(), any(), anyBoolean());
        verify(spool, never()).removeMessageById(anyLong());
        verify(spool, never()).addId(anyLong());
    }

    @Test
    void GIVEN_spool_pop_id_interrupted_WHEN_spool_message_THEN_stop_spooling_message(ExtensionContext context)
            throws InterruptedException {

        ignoreExceptionOfType(context, InterruptedException.class);

        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, true, (c) -> builder, executorService));
        client.setMqttOnline(true);
        long id = 1L;
        when(spool.popId()).thenReturn(id).thenThrow(InterruptedException.class);
        PublishRequest request = PublishRequest.builder().topic("spool")
                .payload("What's up".getBytes(StandardCharsets.UTF_8))
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        SpoolMessage message = SpoolMessage.builder().id(id).request(request).build();
        when(spool.getMessageById(id)).thenReturn(message);

        AwsIotMqttClient awsIotMqttClient = mock(AwsIotMqttClient.class);
        when(client.getNewMqttClient()).thenReturn(awsIotMqttClient);
        when(awsIotMqttClient.connect()).thenReturn(CompletableFuture.completedFuture(true));
        when(client.getNewMqttClient()).thenReturn(awsIotMqttClient);
        when(awsIotMqttClient.publish(any(), any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(0));

        client.runSpooler();

        verify(client).runSpooler();
        verify(awsIotMqttClient).publish(any(), any(), anyBoolean());
        verify(spool).getMessageById(anyLong());
        verify(spool).removeMessageById(anyLong());
        // The 3rd call is to trigger Interrupted Exception and exit the loop
        verify(spool, times(2)).popId();
        verify(client, times(2)).publishSingleSpoolerMessage(awsIotMqttClient);
    }

    @Test
    void GIVEN_publish_request_execution_exception_WHEN_spool_message_THEN_continue_spooling_message(ExtensionContext context)
            throws InterruptedException {
        ignoreExceptionOfType(context, ExecutionException.class);
        ignoreExceptionOfType(context, InterruptedException.class);

        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, true, (c) -> builder, executorService));
        client.setMqttOnline(true);

        long id = 1L;
        when(spool.popId()).thenReturn(id).thenReturn(id).thenThrow(InterruptedException.class);
        PublishRequest request = PublishRequest.builder().topic("spool")
                .payload("What's up".getBytes(StandardCharsets.UTF_8))
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        SpoolMessage message = SpoolMessage.builder().id(id).request(request).build();
        when(spool.getMessageById(id)).thenReturn(message);

        AwsIotMqttClient awsIotMqttClient = mock(AwsIotMqttClient.class);
        when(client.getNewMqttClient()).thenReturn(awsIotMqttClient);
        when(awsIotMqttClient.connect()).thenReturn(CompletableFuture.completedFuture(true));
        CompletableFuture<Integer> future = new CompletableFuture<>();
        future.completeExceptionally(new ExecutionException("exception", new Throwable()));
        when(awsIotMqttClient.publish(any(), any(), anyBoolean())).thenReturn(future);

        client.runSpooler();

        verify(client).runSpooler();
        verify(awsIotMqttClient, times(2)).publish(any(), any(), anyBoolean());
        verify(spool, times(2)).getMessageById(anyLong());
        verify(spool, never()).removeMessageById(anyLong());
        // The 3rd call is to trigger Interrupted Exception and exit the loop
        verify(spool, times(3)).popId();
        verify(client, times(3)).publishSingleSpoolerMessage(awsIotMqttClient);
    }


    @Test
    void GIVEN_connection_resumed_WHEN_callback_THEN_start_spool_messages(ExtensionContext context)
            throws InterruptedException {

        ignoreExceptionOfType(context, InterruptedException.class);
        ignoreExceptionWithMessage(context, "interrupted");

        // The mqttClient is initiated when connectivity is offline
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false,
                (c) -> builder, executorService));
        Long id  = 1L;
        PublishRequest request = PublishRequest.builder().topic("spool")
                .payload("What's up".getBytes(StandardCharsets.UTF_8))
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        SpoolMessage message = SpoolMessage.builder().id(id).request(request).build();
        when(spool.getMessageById(id)).thenReturn(message);
        // Throw an InterruptedException to break the while loop in the client.spoolMessages()
        when(spool.popId()).thenReturn(id).thenThrow(new InterruptedException("interrupted"));

        client.getCallbacks().onConnectionResumed(false);

        // Confirm the spooler was working
        verify(spool, times(1)).getMessageById(anyLong());
        verify(spool, times(2)).popId();

        SpoolerConfig config = SpoolerConfig.builder().spoolSizeInBytes(10L)
                .storageType(SpoolerStorageType.Memory).keepQos0WhenOffline(false).build();
        when(spool.getSpoolConfig()).thenReturn(config);

        client.getCallbacks().onConnectionInterrupted(1);

        verify(spool).getSpoolConfig();
        verify(spool).popOutMessagesWithQosZero();
    }

    @Test
    void GIVEN_connection_interrupted_WHEN_callback_THEN_drop_messages_if_required() {
        // The mqttClient is initiated when connectivity is offline
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false,
                (c) -> builder, executorService));

        SpoolerConfig config = SpoolerConfig.builder().spoolSizeInBytes(10L)
                .storageType(SpoolerStorageType.Memory).keepQos0WhenOffline(false).build();
        when(spool.getSpoolConfig()).thenReturn(config);

        client.getCallbacks().onConnectionInterrupted(1);

        verify(spool).getSpoolConfig();
        verify(spool).popOutMessagesWithQosZero();
    }

    @Test
    void GIVEN_message_size_exceeds_max_limit_WHEN_publish_THEN_future_complete_exceptionally() throws SpoolerStoreException, InterruptedException, MqttRequestException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        PublishRequest request = PublishRequest.builder().topic("spool")
                .payload(new byte[MQTT_MAX_LIMIT_OF_MESSAGE_SIZE_IN_BYTES + 1])
                .qos(QualityOfService.AT_LEAST_ONCE).build();

        CompletableFuture<Integer> future = client.publish(request);

        assertTrue(future.isCompletedExceptionally());
        verify(spool, never()).addMessage(request);
        verify(client).isValidPublishRequest(request);
    }

    @Test
    void GIVEN_message_topic_have_wildcard_WHEN_publish_THEN_future_complete_exceptionally() throws SpoolerStoreException, InterruptedException, MqttRequestException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        PublishRequest request = PublishRequest.builder().topic("abc/+")
                .payload(new byte[1])
                .qos(QualityOfService.AT_LEAST_ONCE).build();

        CompletableFuture<Integer> future = client.publish(request);

        assertTrue(future.isCompletedExceptionally());
        verify(spool, never()).addMessage(request);
        verify(client).isValidPublishRequest(request);
    }

    @Test
    void GIVEN_unreserved_topic_have_8_forward_slashes_WHEN_publish_THEN_future_complete_exceptionally() throws SpoolerStoreException, InterruptedException, MqttRequestException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        String topic = String.join("/", Collections.nCopies(MAX_NUMBER_OF_FORWARD_SLASHES + 2, "a"));
        assertEquals(8, topic.chars().filter(num -> num == '/').count());
        PublishRequest request = PublishRequest.builder().topic(topic)
                .payload(new byte[1])
                .qos(QualityOfService.AT_LEAST_ONCE).build();

        CompletableFuture<Integer> future = client.publish(request);

        assertTrue(future.isCompletedExceptionally());
        verify(spool, never()).addMessage(request);
        verify(client).isValidPublishRequest(request);
    }

    @Test
    void GIVEN_reserved_topic_have_9_forward_slashes_WHEN_publish_THEN_future_complete() throws SpoolerStoreException, InterruptedException, ExecutionException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        String topic = reservedTopicPrefix + String.join("/", Collections.nCopies(MAX_NUMBER_OF_FORWARD_SLASHES, "a"));
        assertEquals(9, topic.chars().filter(num -> num == '/').count());
        PublishRequest request = PublishRequest.builder().topic(topic)
                .payload(new byte[1])
                .qos(QualityOfService.AT_LEAST_ONCE).build();

        SpoolMessage message = SpoolMessage.builder().id(0L).request(request).build();
        when(spool.addMessage(request)).thenReturn(message);

        CompletableFuture<Integer> future = client.publish(request);

        assertEquals(0, future.get());
        verify(spool, times(1)).addMessage(request);
        verify(spool, never()).getSpoolConfig();
    }

    @Test
    void GIVEN_reserved_topic_have_11_forward_slashes_WHEN_publish_THEN_future_complete_exceptionally() throws SpoolerStoreException, InterruptedException, MqttRequestException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        String topic = reservedTopicPrefix + String.join("/", Collections.nCopies(MAX_NUMBER_OF_FORWARD_SLASHES + 2, "a"));
        assertEquals(11, topic.chars().filter(num -> num == '/').count());
        PublishRequest request = PublishRequest.builder().topic(topic)
                .payload(new byte[1])
                .qos(QualityOfService.AT_LEAST_ONCE).build();

        CompletableFuture<Integer> future = client.publish(request);

        assertTrue(future.isCompletedExceptionally());
        verify(spool, never()).addMessage(request);
        verify(client).isValidPublishRequest(request);
    }

    @Test
    void GIVEN_unreserved_topic_exceeds_topic_size_limit_WHEN_publish_THEN_future_complete_exceptionally() throws SpoolerStoreException, InterruptedException, MqttRequestException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        String topic = String.join("", Collections.nCopies(MAX_LENGTH_OF_TOPIC + 1, "a"));
        PublishRequest request = PublishRequest.builder().topic(topic)
                .payload(new byte[1])
                .qos(QualityOfService.AT_LEAST_ONCE).build();

        CompletableFuture<Integer> future = client.publish(request);

        assertTrue(future.isCompletedExceptionally());
        verify(spool, never()).addMessage(request);
        verify(client).isValidPublishRequest(request);
    }

    @Test
    void GIVEN_reserved_topic_including_prefix_equal_to_topic_size_limit_WHEN_publish_THEN_future_complete() throws SpoolerStoreException, InterruptedException, ExecutionException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        String topic = String.join("", Collections.nCopies(MAX_LENGTH_OF_TOPIC, "a"));
        PublishRequest request = PublishRequest.builder().topic(reservedTopicPrefix + topic)
                .payload(new byte[1])
                .qos(QualityOfService.AT_LEAST_ONCE).build();

        SpoolMessage message = SpoolMessage.builder().id(0L).request(request).build();
        when(spool.addMessage(request)).thenReturn(message);

        CompletableFuture<Integer> future = client.publish(request);

        assertEquals(0, future.get());
        verify(spool, times(1)).addMessage(request);
        verify(spool, never()).getSpoolConfig();
    }

    @Test
    void GIVEN_reserved_topic_excluding_prefix_exceeds_topic_size_limit_WHEN_publish_THEN_future_complete_exceptionally() throws SpoolerStoreException, InterruptedException, MqttRequestException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        String topic = String.join("", Collections.nCopies(MAX_LENGTH_OF_TOPIC + 1, "a"));
        PublishRequest request = PublishRequest.builder().topic(reservedTopicPrefix + topic)
                .payload(new byte[1])
                .qos(QualityOfService.AT_LEAST_ONCE).build();

        CompletableFuture<Integer> future = client.publish(request);

        assertTrue(future.isCompletedExceptionally());
        verify(spool, never()).addMessage(request);
        verify(client).isValidPublishRequest(request);
    }

    @Test
    void unreserved_topic_have_8_forward_slashes_WHEN_subscribe_THEN_throw_exception() throws MqttRequestException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        String topic = String.join("/", Collections.nCopies(MAX_NUMBER_OF_FORWARD_SLASHES + 2, "a"));
        assertEquals(8, topic.chars().filter(num -> num == '/').count());
        SubscribeRequest request = SubscribeRequest.builder().topic(topic).callback(cb).build();

        assertThrows(ExecutionException.class, () -> client.subscribe(request));

        verify(client).isValidRequestTopic(topic);
        verify(mockConnection, never()).subscribe(any(), any());
    }
}
