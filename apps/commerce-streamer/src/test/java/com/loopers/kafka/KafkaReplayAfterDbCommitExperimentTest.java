package com.loopers.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.infrastructure.purchase.ProcessedOrderConfirmationJpaRepository;
import com.loopers.infrastructure.purchase.ProcessedOrderConfirmationRepositoryImpl;
import com.loopers.infrastructure.purchase.ProductPurchaseActivityJpaRepository;
import com.loopers.infrastructure.purchase.PurchaseActivityRepositoryImpl;
import com.loopers.interfaces.consumer.OrderConfirmedRecordAdapter;
import com.loopers.testcontainers.KafkaTestBroker;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.event.ConsumerStoppedEvent;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ConsumerAwareMessageListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** DB commit 뒤 listener가 반환하기 전 중단해 같은 group의 실제 Kafka 재전달을 확인한다. */
@SpringBootTest(classes = KafkaFailureHandlingExperimentTest.DatabaseConfig.class)
class KafkaReplayAfterDbCommitExperimentTest {
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-28T01:00:00.123456Z");
    private static KafkaTestBroker broker;

    @Autowired private OrderConfirmedRecordAdapter adapter;
    @Autowired private ProductPurchaseActivityJpaRepository activities;
    @Autowired private ProcessedOrderConfirmationJpaRepository confirmations;
    @Autowired private DatabaseCleanUp cleanUp;
    @MockitoSpyBean private PurchaseActivityRepositoryImpl activityRepository;
    @MockitoSpyBean private ProcessedOrderConfirmationRepositoryImpl confirmationRepository;

    @BeforeAll
    static void startBroker() {
        broker = new KafkaTestBroker();
    }

    @AfterAll
    static void stopBroker() {
        if (broker != null) {
            broker.close();
        }
    }

    @AfterEach
    void cleanUp() {
        cleanUp.truncateAllTables();
    }

    @Test
    @Timeout(90)
    void restartReplaysCommittedDbFactWithoutAdditionalWrites() throws Exception {
        String topic = "week6-db-commit-replay-" + UUID.randomUUID();
        String group = "db-commit-replay-" + UUID.randomUUID();
        TopicPartition partition = new TopicPartition(topic, 0);
        CountDownLatch afterDbCommit = new CountDownLatch(1);
        CountDownLatch releaseStop = new CountDownLatch(1);
        CountDownLatch firstStopped = new CountDownLatch(1);
        CountDownLatch replayHandled = new CountDownLatch(1);
        AtomicBoolean transactionStillActive = new AtomicBoolean(true);
        AtomicLong firstPosition = new AtomicLong(-1L);
        AtomicLong resumedPosition = new AtomicLong(-1L);
        List<Delivery> deliveries = new CopyOnWriteArrayList<>();
        List<String> payloads = new CopyOnWriteArrayList<>();

        try (AdminClient admin = AdminClient.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            admin.alterConsumerGroupOffsets(group, Map.of(partition, new OffsetAndMetadata(0L)))
                .all().get(10, TimeUnit.SECONDS);
            assertThat(committed(admin, group, partition)).isZero();
            sendOrder(topic);

            var first = container(topic, group, "first", firstStopped, (record, consumer) -> {
                deliveries.add(delivery(record));
                payloads.add(new String(record.value(), StandardCharsets.UTF_8));
                firstPosition.set(consumer.position(partition));
                adapter.handle(record);
                // application의 DB TX가 성공해 돌아왔지만 listener는 아직 반환하지 않은 경계다.
                transactionStillActive.set(TransactionSynchronizationManager.isActualTransactionActive());
                afterDbCommit.countDown();
                try {
                    if (!releaseStop.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("DB commit checkpoint timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                // OS 강제 종료 대신 test hook과 중단 error handler로 offset commit을 막는다.
                throw new IllegalStateException("experiment: stop after DB commit before offset commit");
            });

            DatabaseSnapshot beforeRestart;
            try {
                first.start();
                assertThat(afterDbCommit.await(20, TimeUnit.SECONDS)).isTrue();
                assertThat(transactionStillActive.get()).isFalse();
                assertThat(firstPosition.get()).isEqualTo(1L);
                assertThat(deliveries).containsExactly(new Delivery(topic, 0, 0L, "42"));
                assertThat(committed(admin, group, partition)).isZero();
                assertOriginalOrderStored();
                beforeRestart = databaseSnapshot();
                verifyOnlyInitialWrites();

                releaseStop.countDown();
                assertThat(firstStopped.await(20, TimeUnit.SECONDS)).isTrue();
                assertThat(first.isRunning()).isFalse();
                assertThat(committed(admin, group, partition)).isZero();
                assertThat(databaseSnapshot()).isEqualTo(beforeRestart);
            } finally {
                releaseStop.countDown();
                first.stop();
            }

            // 별도 container/consumer 객체, 같은 group. seek나 시작 offset 재설정 없이 구독한다.
            var resumed = container(topic, group, "resumed", new CountDownLatch(1), (record, consumer) -> {
                deliveries.add(delivery(record));
                payloads.add(new String(record.value(), StandardCharsets.UTF_8));
                resumedPosition.set(consumer.position(partition));
                adapter.handle(record);
                replayHandled.countDown();
            });
            try {
                resumed.start();
                assertThat(replayHandled.await(20, TimeUnit.SECONDS)).isTrue();
                await(() -> committed(admin, group, partition) == 1L);
                assertThat(resumed.isRunning()).isTrue();
                assertThat(resumedPosition.get()).isEqualTo(1L);
                assertThat(deliveries).containsExactly(
                    new Delivery(topic, 0, 0L, "42"), new Delivery(topic, 0, 0L, "42"));
                assertThat(payloads).hasSize(2);
                assertThat(payloads.get(1)).isEqualTo(payloads.get(0));
                assertOriginalOrderStored();
                assertThat(databaseSnapshot()).isEqualTo(beforeRestart);
                verifyOnlyInitialWrites();
                long endOffset = admin.listOffsets(Map.of(partition, OffsetSpec.latest()))
                    .all().get(10, TimeUnit.SECONDS).get(partition).offset();
                assertThat(endOffset).isEqualTo(1L);

                System.out.println("DB_COMMIT_REPLAY_EXPERIMENT " + new ObjectMapper().writeValueAsString(Map.of(
                    "deliveryOffsets", deliveries.stream().map(Delivery::offset).toList(),
                    "deliveryKeys", deliveries.stream().map(Delivery::key).toList(),
                    "committedOffsets", List.of(0, 0, committed(admin, group, partition)),
                    "consumerPositions", List.of(firstPosition.get(), resumedPosition.get()),
                    "activityRowsBeforeRestart", beforeRestart.activities().size(),
                    "activityRowsAfterRestart", activities.count(),
                    "confirmationRows", confirmations.count(),
                    "rowIdsAndFactsUnchanged", databaseSnapshot().equals(beforeRestart),
                    "totalActivitySaveCalls", 2,
                    "kafkaEndOffset", endOffset)));
            } finally {
                resumed.stop();
            }
            assertThat(committed(admin, group, partition)).isEqualTo(1L);
        }
    }

    private KafkaMessageListenerContainer<String, byte[]> container(
        String topic, String group, String name, CountDownLatch stopped,
        ConsumerAwareMessageListener<String, byte[]> listener
    ) {
        var consumerFactory = new DefaultKafkaConsumerFactory<String, byte[]>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false));
        ContainerProperties properties = new ContainerProperties(topic);
        properties.setGroupId(group);
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        properties.setAssignmentCommitOption(ContainerProperties.AssignmentCommitOption.NEVER);
        properties.setSyncCommits(true);
        properties.setSyncCommitTimeout(Duration.ofSeconds(10));
        properties.setPollTimeout(100);
        properties.setShutdownTimeout(10_000);
        properties.setMessageListener(listener);
        var container = new KafkaMessageListenerContainer<>(consumerFactory, properties);
        container.setBeanName("db-commit-replay-" + name);
        container.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        container.setApplicationEventPublisher(event -> {
            if (event instanceof ConsumerStoppedEvent) {
                stopped.countDown();
            }
        });
        return container;
    }

    private void sendOrder(String topic) throws Exception {
        byte[] payload = """
            {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:42","orderId":42,
             "confirmedAt":"%s","items":[{"productId":7,"quantity":5},{"productId":8,"quantity":3}]}
            """.formatted(CONFIRMED_AT).getBytes(StandardCharsets.UTF_8);
        try (var producer = new KafkaProducer<String, byte[]>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            var metadata = producer.send(new ProducerRecord<>(topic, 0, "42", payload))
                .get(10, TimeUnit.SECONDS);
            assertThat(metadata.offset()).isZero();
        }
    }

    private Delivery delivery(ConsumerRecord<String, byte[]> record) {
        return new Delivery(record.topic(), record.partition(), record.offset(), record.key());
    }

    private long committed(AdminClient admin, String group, TopicPartition partition) throws Exception {
        var offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
            .get(10, TimeUnit.SECONDS).get(partition);
        assertThat(offset).isNotNull();
        return offset.offset();
    }

    private void assertOriginalOrderStored() {
        assertThat(confirmations.count()).isEqualTo(1);
        assertThat(activities.findAll()).extracting(ProductPurchaseActivity::getOrderId,
            ProductPurchaseActivity::getProductId, ProductPurchaseActivity::getQuantity,
            ProductPurchaseActivity::getConfirmedAt).containsExactlyInAnyOrder(
                tuple(42L, 7L, 5, CONFIRMED_AT), tuple(42L, 8L, 3, CONFIRMED_AT));
    }

    private DatabaseSnapshot databaseSnapshot() {
        var confirmation = confirmations.findByEventId("order-confirmed:v1:42").orElseThrow();
        List<ActivitySnapshot> rows = activities.findAll().stream()
            .sorted(Comparator.comparing(ProductPurchaseActivity::getProductId))
            .map(activity -> new ActivitySnapshot(activity.getId(), activity.getOrderId(), activity.getProductId(),
                activity.getQuantity(), activity.getConfirmedAt())).toList();
        return new DatabaseSnapshot(confirmation.getId(), confirmation.getEventId(), confirmation.getOrderId(),
            confirmation.getFactSnapshot(), rows);
    }

    private void verifyOnlyInitialWrites() {
        verify(activityRepository, times(2)).save(any(ProductPurchaseActivity.class));
        verify(confirmationRepository, times(1)).save(any(ProcessedOrderConfirmation.class));
    }

    private void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.call()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("expected committed offset was not observed");
    }

    private record Delivery(String topic, int partition, long offset, String key) {}
    private record ActivitySnapshot(Long id, Long orderId, Long productId, int quantity, Instant confirmedAt) {}
    private record DatabaseSnapshot(Long confirmationId, String eventId, Long orderId,
                                    String factSnapshot, List<ActivitySnapshot> activities) {}
}
