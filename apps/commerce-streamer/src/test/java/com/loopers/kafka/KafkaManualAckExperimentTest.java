package com.loopers.kafka;

import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.infrastructure.purchase.PurchaseActivityRepositoryImpl;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 업무용 구독 전, 선택한 ack와 기존 MANUAL의 실제 commit 경계를 비교한다. */
class KafkaManualAckExperimentTest extends KafkaPurchaseExperimentSupport {
    @MockitoSpyBean private PurchaseActivityRepositoryImpl repository;

    @ParameterizedTest(name = "ack commit boundary: {0}")
    @EnumSource(value = AckMode.class, names = {"MANUAL", "MANUAL_IMMEDIATE"})
    @Timeout(80)
    void observesCommittedOffsetBeforeAckAfterAckAndBeforeListenerReturn(AckMode mode) throws Exception {
        String topic = "manual-ack-boundary-" + UUID.randomUUID();
        String group = "manual-ack-boundary-" + UUID.randomUUID();
        TopicPartition partition = new TopicPartition(topic, 0);
        var beforeAck = new CountDownLatch(1);
        var permitAck = new CountDownLatch(1);
        var afterAck = new CountDownLatch(1);
        var permitReturn = new CountDownLatch(1);
        var activeTransactionAtAck = new AtomicBoolean(true);
        var ackCalls = new AtomicInteger();
        AcknowledgingMessageListener<String, byte[]> listener = (record, acknowledgment) ->
            adapter.handle(record, () -> {
                activeTransactionAtAck.set(TransactionSynchronizationManager.isActualTransactionActive());
                beforeAck.countDown();
                releaseAfterInspection(permitAck);
                acknowledgment.acknowledge();
                ackCalls.incrementAndGet();
                afterAck.countDown();
                releaseAfterInspection(permitReturn);
            });

        try (var admin = admin(); var producer = producer()) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            seedOffset(admin, group, partition);
            assertThat(sendOrder(producer, topic, 42L).offset()).isZero();
            var container = container(topic, group, listener, new CountDownLatch(1));
            container.getContainerProperties().setAckMode(mode);
            try {
                container.start();
                assertThat(beforeAck.await(20, TimeUnit.SECONDS)).isTrue();
                assertThat(activeTransactionAtAck.get()).isFalse();
                assertOrders(42L);
                assertThat(committed(admin, group, partition)).isZero();
                assertThat(ackCalls.get()).isZero();

                permitAck.countDown();
                assertThat(afterAck.await(20, TimeUnit.SECONDS)).isTrue();
                long offsetBeforeReturn = committed(admin, group, partition);
                assertThat(offsetBeforeReturn).isEqualTo(mode == AckMode.MANUAL_IMMEDIATE ? 1L : 0L);
                assertThat(ackCalls.get()).isEqualTo(1);
                assertOrders(42L);

                permitReturn.countDown();
                await(() -> committed(admin, group, partition) == 1L);
                report("MANUAL_ACK_BOUNDARY_EXPERIMENT", Map.of("mode", mode.name(),
                    "afterDbBeforeAck", 0, "afterAckBeforeReturn", offsetBeforeReturn,
                    "afterListenerReturn", committed(admin, group, partition),
                    "dbTransactionActiveAtAck", activeTransactionAtAck.get(),
                    "ackCalls", ackCalls.get(), "confirmationRows", confirmations.count(),
                    "activityRows", activities.count()));
            } finally {
                permitAck.countDown();
                permitReturn.countDown();
                container.stop();
            }
        }
    }

    @ParameterizedTest(name = "DB retry exhaustion before ack: {0}")
    @EnumSource(value = AckMode.class, names = {"MANUAL", "MANUAL_IMMEDIATE"})
    @Timeout(90)
    void exhaustedDbRetriesNeverAckAndSameGroupResumesAfterRecovery(AckMode mode) throws Exception {
        String topic = "manual-ack-db-failure-" + UUID.randomUUID();
        String group = "manual-ack-db-failure-" + UUID.randomUUID();
        TopicPartition partition = new TopicPartition(topic, 0);
        var faultActive = new AtomicBoolean(true);
        var ackCalls = new AtomicInteger();
        var exhausted = new AtomicInteger();
        var stopped = new CountDownLatch(1);
        List<String> deliveries = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            ProductPurchaseActivity activity = invocation.getArgument(0);
            if (faultActive.get() && activity.getOrderId().equals(42L) && activity.getProductId().equals(8L)) {
                throw new TransientDataAccessResourceException("experiment: fail before DB commit and ack");
            }
            return invocation.callRealMethod();
        }).when(repository).save(any());
        AcknowledgingMessageListener<String, byte[]> listener = (record, acknowledgment) -> {
            deliveries.add(record.key() + ":" + record.offset());
            adapter.handle(record, () -> {
                acknowledgment.acknowledge();
                ackCalls.incrementAndGet();
            });
        };

        try (var admin = admin(); var producer = producer()) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            seedOffset(admin, group, partition);
            assertThat(sendOrder(producer, topic, 42L).offset()).isZero();
            assertThat(sendOrder(producer, topic, 43L).offset()).isEqualTo(1);
            var container = container(topic, group, listener, stopped);
            container.getContainerProperties().setAckMode(mode);
            var waiting = new ExponentialBackOffWithMaxRetries(2);
            waiting.setInitialInterval(1_000L);
            waiting.setMultiplier(2.0);
            waiting.setMaxInterval(2_000L);
            var stopping = new CommonContainerStoppingErrorHandler();
            ConsumerAwareRecordRecoverer stopAfterRetries = (record, consumer, exception) -> {
                exhausted.incrementAndGet();
                stopping.handleRemaining(exception, List.of(record), consumer, container);
            };
            container.setCommonErrorHandler(new DefaultErrorHandler(stopAfterRetries, waiting));
            try {
                container.start();
                assertThat(stopped.await(30, TimeUnit.SECONDS)).isTrue();
                assertThat(container.isRunning()).isFalse();
                assertThat(deliveries).containsExactly("42:0", "42:0", "42:0");
                assertThat(exhausted.get()).isEqualTo(1);
                assertThat(ackCalls.get()).isZero();
                assertThat(committed(admin, group, partition)).isZero();
                assertThat(confirmations.count()).isZero();
                assertThat(activities.count()).isZero();

                faultActive.set(false);
                container.start();
                await(() -> committed(admin, group, partition) == 2L && ackCalls.get() == 2);
                assertOrders(42L, 43L);
                assertThat(deliveries).containsExactly("42:0", "42:0", "42:0", "42:0", "43:1");
                report("MANUAL_ACK_DB_FAILURE_EXPERIMENT", Map.of("mode", mode.name(),
                    "ackCallsAtStop", 0, "committedOffsetAtStop", 0, "stopsOnExhaustion", exhausted.get(),
                    "deliveries", deliveries, "ackCallsAfterRestart", ackCalls.get(),
                    "finalCommittedOffset", committed(admin, group, partition),
                    "confirmationRows", confirmations.count(), "activityRows", activities.count()));
            } finally {
                container.stop();
            }
        }
    }

    @Test
    @Timeout(85)
    void manualImmediateReplaysDbCommitWithoutAckThenAcknowledgesTheNoOp() throws Exception {
        String topic = "manual-immediate-replay-" + UUID.randomUUID();
        String group = "manual-immediate-replay-" + UUID.randomUUID();
        TopicPartition partition = new TopicPartition(topic, 0);
        var beforeAck = new CountDownLatch(1);
        var permitFailure = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        List<String> deliveries = new CopyOnWriteArrayList<>();
        AcknowledgingMessageListener<String, byte[]> firstListener = (record, acknowledgment) -> {
            deliveries.add(record.key() + ":" + record.offset());
            adapter.handle(record, () -> {
                beforeAck.countDown();
                releaseAfterInspection(permitFailure);
                throw new IllegalStateException("experiment: stop after DB commit before manual ack");
            });
        };
        AcknowledgingMessageListener<String, byte[]> resumedListener = (record, acknowledgment) -> {
            deliveries.add(record.key() + ":" + record.offset());
            adapter.handle(record, acknowledgment);
        };

        try (var admin = admin(); var producer = producer()) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            seedOffset(admin, group, partition);
            assertThat(sendOrder(producer, topic, 42L).offset()).isZero();
            var first = container(topic, group, firstListener, stopped);
            first.getContainerProperties().setAckMode(AckMode.MANUAL_IMMEDIATE);
            List<Long> rowIds;
            Long confirmationId;
            try {
                first.start();
                assertThat(beforeAck.await(20, TimeUnit.SECONDS)).isTrue();
                assertOrders(42L);
                assertThat(committed(admin, group, partition)).isZero();
                rowIds = activities.findAll().stream().map(ProductPurchaseActivity::getId).sorted().toList();
                confirmationId = confirmations.findByEventId("order-confirmed:v1:42").orElseThrow().getId();
                permitFailure.countDown();
                assertThat(stopped.await(20, TimeUnit.SECONDS)).isTrue();
                assertThat(committed(admin, group, partition)).isZero();
            } finally {
                permitFailure.countDown();
                first.stop();
            }

            var resumed = container(topic, group, resumedListener, new CountDownLatch(1));
            resumed.getContainerProperties().setAckMode(AckMode.MANUAL_IMMEDIATE);
            try {
                resumed.start();
                await(() -> committed(admin, group, partition) == 1L);
                assertOrders(42L);
                assertThat(deliveries).containsExactly("42:0", "42:0");
                assertThat(activities.findAll().stream().map(ProductPurchaseActivity::getId).sorted().toList())
                    .isEqualTo(rowIds);
                assertThat(confirmations.findByEventId("order-confirmed:v1:42").orElseThrow().getId())
                    .isEqualTo(confirmationId);
                verify(repository, times(2)).save(any());
                report("MANUAL_IMMEDIATE_REPLAY_EXPERIMENT", Map.of("deliveries", deliveries,
                    "committedOffsets", List.of(0, 0, committed(admin, group, partition)),
                    "confirmationRows", confirmations.count(), "activityRows", activities.count(),
                    "existingRowIdsUnchanged", true, "activitySaveCalls", 2));
            } finally {
                resumed.stop();
            }
        }
    }
}
