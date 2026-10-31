package com.loopers.kafka;

import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.infrastructure.purchase.ProcessedOrderConfirmationRepositoryImpl;
import com.loopers.infrastructure.purchase.PurchaseActivityRepositoryImpl;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.event.ConsumerStoppedEvent;
import org.springframework.kafka.listener.AcknowledgingConsumerAwareMessageListener;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ConsumerAwareMessageListener;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** record listener의 commit 방식만 비교한다. AckMode.BATCH는 batch listener로 바꾸는 설정이 아니다. */
class KafkaBatchAckExperimentTest extends KafkaPurchaseExperimentSupport {
    @MockitoSpyBean private PurchaseActivityRepositoryImpl activityRepository;
    @MockitoSpyBean private ProcessedOrderConfirmationRepositoryImpl confirmationRepository;

    @ParameterizedTest(name = "partial poll completion: {0}")
    @EnumSource(value = AckMode.class, names = {"BATCH", "MANUAL_IMMEDIATE"})
    @Timeout(100)
    void replayRangeDiffersButCommittedPurchaseEffectsRemainTheSame(AckMode mode) throws Exception {
        String topic = "batch-ack-" + UUID.randomUUID();
        String group = "batch-ack-" + UUID.randomUUID();
        var partition = new TopicPartition(topic, 0);
        var afterFirstOrder = new CountDownLatch(1);
        var permitStop = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var firstPosition = new AtomicLong(-1L);
        var transactionActive = new AtomicBoolean(true);
        List<Long> firstDeliveries = new CopyOnWriteArrayList<>();
        List<Long> replayedOffsets = new CopyOnWriteArrayList<>();
        long expectedCheckpoint = mode == AckMode.BATCH ? 0L : 1L;
        List<Long> expectedReplays = mode == AckMode.BATCH ? List.of(0L, 1L, 2L) : List.of(1L, 2L);

        try (var admin = admin(); var producer = producer()) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            seedOffset(admin, group, partition);
            assertThat(sendOrder(producer, topic, 42L).offset()).isZero();
            assertThat(sendOrder(producer, topic, 43L).offset()).isEqualTo(1L);
            assertThat(sendOrder(producer, topic, 44L).offset()).isEqualTo(2L);

            var first = container(topic, group, mode, stopped, (record, consumer) -> {
                firstDeliveries.add(record.offset());
                firstPosition.set(consumer.position(partition));
                transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
                afterFirstOrder.countDown();
                releaseAfterInspection(permitStop);
                // DB commit 뒤 같은 경계다. OS 종료 대신 listener의 정상 반환을 막는 hook을 쓴다.
                throw new IllegalStateException("experiment: stop after first order before listener return");
            });

            OrderSnapshot beforeRestart;
            try {
                first.start();
                assertThat(afterFirstOrder.await(25, TimeUnit.SECONDS)).isTrue();
                // poll 한 번에 3건을 받은 것을 position으로 확인한다. max.poll.records=3만으로 단정하지 않는다.
                assertThat(firstPosition).hasValue(3L);
                assertThat(firstDeliveries).containsExactly(0L);
                assertThat(transactionActive).isFalse();
                assertOrders(42L);
                assertThat(committed(admin, group, partition)).isEqualTo(expectedCheckpoint);
                beforeRestart = snapshotOfOrder42();
                verify(activityRepository, times(2)).save(any(ProductPurchaseActivity.class));
                verify(confirmationRepository, times(1)).save(any(ProcessedOrderConfirmation.class));

                permitStop.countDown();
                assertThat(stopped.await(20, TimeUnit.SECONDS)).isTrue();
                assertThat(first.isRunning()).isFalse();
                assertThat(committed(admin, group, partition)).isEqualTo(expectedCheckpoint);
                assertOrders(42L);
                assertThat(snapshotOfOrder42()).isEqualTo(beforeRestart);
            } finally {
                permitStop.countDown();
                first.stop();
            }

            // 새 consumer와 같은 group이다. seek·offset 재설정·추가 발행은 하지 않는다.
            var resumed = container(topic, group, mode, new CountDownLatch(1),
                (record, consumer) -> replayedOffsets.add(record.offset()));
            try {
                resumed.start();
                // IMMEDIATE commit은 관찰 callback보다 먼저 보일 수 있으므로 두 조건을 함께 기다린다.
                await(() -> committed(admin, group, partition) == 3L
                    && replayedOffsets.size() == expectedReplays.size());
                assertThat(resumed.isRunning()).isTrue();
                assertThat(replayedOffsets).containsExactlyElementsOf(expectedReplays);
                assertOrders(42L, 43L, 44L);
                assertThat(snapshotOfOrder42()).isEqualTo(beforeRestart);
                // BATCH 재처리도 최초 주문 3건의 저장만 한다. 42의 재수신은 기존 handler의 no-op이다.
                verify(activityRepository, times(6)).save(any(ProductPurchaseActivity.class));
                verify(confirmationRepository, times(3)).save(any(ProcessedOrderConfirmation.class));
                long endOffset = admin.listOffsets(Map.of(partition, OffsetSpec.latest()))
                    .all().get(10, TimeUnit.SECONDS).get(partition).offset();
                assertThat(endOffset).isEqualTo(3L);

                report("BATCH_ACK_EXPERIMENT", Map.ofEntries(
                    Map.entry("mode", mode.name()), Map.entry("firstPollPosition", firstPosition.get()),
                    Map.entry("firstDeliveryOffsets", firstDeliveries),
                    Map.entry("committedAfterFirstDb", expectedCheckpoint),
                    Map.entry("committedAfterStop", expectedCheckpoint),
                    Map.entry("replayedOffsets", replayedOffsets),
                    Map.entry("committedAfterRestart", committed(admin, group, partition)),
                    Map.entry("confirmationRows", confirmations.count()),
                    Map.entry("activityRows", activities.count()), Map.entry("activitySaveCalls", 6),
                    Map.entry("firstOrderUnchanged", snapshotOfOrder42().equals(beforeRestart)),
                    Map.entry("kafkaEndOffset", endOffset)));
            } finally {
                resumed.stop();
            }
            assertThat(committed(admin, group, partition)).isEqualTo(3L);
        }
    }

    private KafkaMessageListenerContainer<String, byte[]> container(
        String topic, String group, AckMode mode, CountDownLatch stopped,
        BiConsumer<ConsumerRecord<String, byte[]>, Consumer<?, ?>> afterHandling
    ) {
        MessageListener<String, byte[]> listener;
        if (mode == AckMode.BATCH) {
            listener = (ConsumerAwareMessageListener<String, byte[]>) (record, consumer) -> {
                adapter.handle(record);
                afterHandling.accept(record, consumer);
            };
        } else {
            listener = (AcknowledgingConsumerAwareMessageListener<String, byte[]>) (record, ack, consumer) -> {
                adapter.handle(record, ack);
                afterHandling.accept(record, consumer);
            };
        }
        var settings = new HashMap<>(consumerSettings());
        settings.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 3);
        var properties = properties(topic, group, listener);
        properties.setAckMode(mode);
        var container = new KafkaMessageListenerContainer<>(
            new DefaultKafkaConsumerFactory<String, byte[]>(settings), properties);
        container.setBeanName("batch-ack-experiment-" + group);
        container.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        container.setApplicationEventPublisher(event -> {
            if (event instanceof ConsumerStoppedEvent) {
                stopped.countDown();
            }
        });
        return container;
    }

    private OrderSnapshot snapshotOfOrder42() {
        var confirmation = confirmations.findByEventId("order-confirmed:v1:42").orElseThrow();
        var rows = activities.findAll().stream().filter(activity -> activity.getOrderId().equals(42L))
            .sorted(Comparator.comparing(ProductPurchaseActivity::getProductId))
            .map(activity -> new ActivitySnapshot(activity.getId(), activity.getProductId(), activity.getQuantity(),
                activity.getConfirmedAt())).toList();
        return new OrderSnapshot(confirmation.getId(), confirmation.getEventId(), confirmation.getFactSnapshot(), rows);
    }

    private record ActivitySnapshot(Long id, Long productId, int quantity, Instant confirmedAt) {}
    private record OrderSnapshot(Long confirmationId, String eventId, String factSnapshot,
                                 List<ActivitySnapshot> activities) {}
}
