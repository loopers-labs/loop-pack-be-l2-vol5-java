package com.loopers.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.purchase.OrderConfirmationHandler;
import com.loopers.application.purchase.PurchaseActivityRecorder;
import com.loopers.config.jpa.JpaConfig;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.infrastructure.purchase.ProcessedOrderConfirmationJpaRepository;
import com.loopers.infrastructure.purchase.ProcessedOrderConfirmationRepositoryImpl;
import com.loopers.infrastructure.purchase.ProductPurchaseActivityJpaRepository;
import com.loopers.infrastructure.purchase.PurchaseActivityRepositoryImpl;
import com.loopers.interfaces.consumer.OrderConfirmedMessageParser;
import com.loopers.interfaces.consumer.OrderConfirmedRecordAdapter;
import com.loopers.testcontainers.KafkaTestBroker;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.event.ConsumerStoppedEvent;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** 실제 Kafka container와 MySQL을 사용하는 격리 실험. 업무용 listener 설정은 변경하지 않는다. */
@SpringBootTest(classes = KafkaFailureHandlingExperimentTest.DatabaseConfig.class)
class KafkaFailureHandlingExperimentTest {
    private static KafkaTestBroker broker;

    @Autowired private OrderConfirmedRecordAdapter adapter;
    @Autowired private ProductPurchaseActivityJpaRepository activities;
    @Autowired private ProcessedOrderConfirmationJpaRepository confirmations;
    @Autowired private DatabaseCleanUp cleanUp;
    @MockitoSpyBean private PurchaseActivityRepositoryImpl repository;

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

    @ParameterizedTest(name = "DB failure: {0}")
    @EnumSource(Failure.class)
    @Timeout(90)
    void compareRetryWithDefaultRecoveryAndStopping(Failure failure) throws Exception {
        Map<String, Object> retry = runScenario(false, failure);
        cleanUp.truncateAllTables();
        Map<String, Object> stopping = runScenario(true, failure);

        System.out.println("FAILURE_HANDLING_EXPERIMENT " + new ObjectMapper().writeValueAsString(
            Map.of("failure", failure.name(), "retryWithDefaultRecovery", retry, "stopAndRestart", stopping)));
    }

    private Map<String, Object> runScenario(boolean stopOnFailure, Failure failure) throws Exception {
        String topic = "week6-failure-" + UUID.randomUUID();
        String group = "failure-" + UUID.randomUUID();
        TopicPartition partition = new TopicPartition(topic, 0);
        AtomicBoolean faultActive = new AtomicBoolean(true);
        AtomicInteger partialActivityRows = new AtomicInteger();
        AtomicInteger partialConfirmationRows = new AtomicInteger();
        CountDownLatch failed = new CountDownLatch(1);
        CountDownLatch inspectFailure = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        List<String> deliveries = new CopyOnWriteArrayList<>();
        List<Long> recoveredOffsets = new CopyOnWriteArrayList<>();

        doAnswer(invocation -> {
            ProductPurchaseActivity activity = invocation.getArgument(0);
            boolean matching = activity.getOrderId().equals(42L) && activity.getProductId().equals(8L);
            boolean inject = matching && (failure == Failure.PERSISTENT
                ? faultActive.get() : faultActive.getAndSet(false));
            if (inject) {
                partialActivityRows.set(Math.toIntExact(activities.count()));
                partialConfirmationRows.set(Math.toIntExact(confirmations.count()));
                throw new TransientDataAccessResourceException("experiment: second product save failed");
            }
            return invocation.callRealMethod();
        }).when(repository).save(any());

        try (AdminClient admin = AdminClient.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            // reset 정책은 비교하지 않는다. 두 방식 모두 저장 위치 0에서 시작한다.
            admin.alterConsumerGroupOffsets(group, Map.of(partition, new OffsetAndMetadata(0L)))
                .all().get(10, TimeUnit.SECONDS);
            assertThat(committed(admin, group, partition)).isZero();
            sendOrders(topic);

            ContainerProperties properties = new ContainerProperties(topic);
            properties.setGroupId(group);
            properties.setAckMode(ContainerProperties.AckMode.RECORD);
            properties.setAssignmentCommitOption(ContainerProperties.AssignmentCommitOption.NEVER);
            properties.setSyncCommits(true);
            properties.setSyncCommitTimeout(Duration.ofSeconds(10));
            properties.setPollTimeout(100);
            properties.setShutdownTimeout(10_000);
            properties.setMessageListener((MessageListener<String, byte[]>) record -> {
                deliveries.add(record.key() + "@" + record.offset());
                try {
                    adapter.handle(record);
                } catch (RuntimeException exception) {
                    // DB proxy의 rollback 뒤, error handler에 넘기기 전 상태를 주 스레드에서 확인한다.
                    if (failed.getCount() > 0) {
                        failed.countDown();
                        try {
                            if (!inspectFailure.await(15, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("failure inspection timed out", exception);
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    }
                    throw exception;
                }
            });

            var container = new KafkaMessageListenerContainer<>(consumerFactory(), properties);
            container.setBeanName("failure-experiment-" + group);
            container.setApplicationEventPublisher(event -> {
                if (event instanceof ConsumerStoppedEvent) {
                    stopped.countDown();
                }
            });
            if (stopOnFailure) {
                container.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
            } else {
                // 2회 재시도와 기본 recoverer의 조합을 비교한다. 모든 재시도 방식의 결과는 아니다.
                var retry = new DefaultErrorHandler(new FixedBackOff(200L, 2L));
                retry.setRetryListeners(new RetryListener() {
                    @Override
                    public void failedDelivery(ConsumerRecord<?, ?> record,
                                               Exception exception, int deliveryAttempt) {}

                    @Override
                    public void recovered(ConsumerRecord<?, ?> record,
                                          Exception exception) {
                        recoveredOffsets.add(record.offset());
                    }
                });
                container.setCommonErrorHandler(retry);
            }

            try {
                container.start();
                assertThat(failed.await(20, TimeUnit.SECONDS)).isTrue();
                assertThat(partialActivityRows.get()).isEqualTo(1);
                assertThat(partialConfirmationRows.get()).isEqualTo(1);
                assertThat(activities.count()).isZero();
                assertThat(confirmations.count()).isZero();
                assertThat(deliveries).containsExactly("42@0");
                assertThat(committed(admin, group, partition)).isZero();
                inspectFailure.countDown();

                if (stopOnFailure) {
                    assertThat(stopped.await(20, TimeUnit.SECONDS)).isTrue();
                    assertThat(container.isRunning()).isFalse();
                    assertThat(deliveries).containsExactly("42@0");
                    assertThat(committed(admin, group, partition)).isZero();
                    assertThat(activities.count()).isZero();
                    assertThat(confirmations.count()).isZero();

                    // 실험자가 원인을 제거하고 같은 group으로 명시적으로 재시작한다.
                    faultActive.set(false);
                    container.start();
                    await(() -> committed(admin, group, partition) == 2L && confirmations.count() == 2L);
                    assertThat(deliveries).containsExactly("42@0", "42@0", "43@1");
                    assertAllOrdersStored();
                    return Map.of("beforeRestart", Map.of("deliveries", List.of("42@0"),
                            "activityRows", 0, "confirmationRows", 0, "committedOffset", 0),
                        "afterRestart", Map.of("deliveries", List.copyOf(deliveries),
                            "activityRows", activities.count(), "confirmationRows", confirmations.count(),
                            "committedOffset", committed(admin, group, partition)));
                }

                long expectedConfirmations = failure == Failure.ONCE ? 2L : 1L;
                await(() -> committed(admin, group, partition) == 2L
                    && confirmations.count() == expectedConfirmations);
                assertThat(container.isRunning()).isTrue();
                if (failure == Failure.ONCE) {
                    assertThat(deliveries).containsExactly("42@0", "42@0", "43@1");
                    assertThat(recoveredOffsets).isEmpty();
                    assertAllOrdersStored();
                } else {
                    assertThat(deliveries).containsExactly("42@0", "42@0", "42@0", "43@1");
                    assertThat(recoveredOffsets).containsExactly(0L);
                    assertThat(confirmations.findByEventId("order-confirmed:v1:42")).isEmpty();
                    assertThat(activities.findAll()).extracting(ProductPurchaseActivity::getOrderId,
                        ProductPurchaseActivity::getProductId, ProductPurchaseActivity::getQuantity)
                        .containsExactlyInAnyOrder(tuple(43L, 7L, 5), tuple(43L, 8L, 3));
                }
                return Map.of("deliveries", List.copyOf(deliveries), "recoveredOffsets", recoveredOffsets,
                    "activityRows", activities.count(), "confirmationRows", confirmations.count(),
                    "committedOffset", committed(admin, group, partition));
            } finally {
                inspectFailure.countDown();
                container.stop();
            }
        }
    }

    private DefaultKafkaConsumerFactory<String, byte[]> consumerFactory() {
        return new DefaultKafkaConsumerFactory<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false));
    }

    private void sendOrders(String topic) throws Exception {
        try (var producer = new KafkaProducer<String, byte[]>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            for (long orderId : List.of(42L, 43L)) {
                byte[] payload = """
                    {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:%d",
                     "orderId":%d,"confirmedAt":"2026-10-28T01:00:00.123456Z",
                     "items":[{"productId":7,"quantity":5},{"productId":8,"quantity":3}]}
                    """.formatted(orderId, orderId).getBytes(StandardCharsets.UTF_8);
                var metadata = producer.send(new ProducerRecord<>(topic, 0, Long.toString(orderId), payload))
                    .get(10, TimeUnit.SECONDS);
                assertThat(metadata.offset()).isEqualTo(orderId - 42L);
            }
        }
    }

    private long committed(AdminClient admin, String group, TopicPartition partition) throws Exception {
        var offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
            .get(10, TimeUnit.SECONDS).get(partition);
        assertThat(offset).isNotNull();
        return offset.offset();
    }

    private void assertAllOrdersStored() {
        assertThat(confirmations.count()).isEqualTo(2);
        assertThat(activities.findAll()).extracting(ProductPurchaseActivity::getOrderId,
            ProductPurchaseActivity::getProductId, ProductPurchaseActivity::getQuantity)
            .containsExactlyInAnyOrder(tuple(42L, 7L, 5), tuple(42L, 8L, 3),
                tuple(43L, 7L, 5), tuple(43L, 8L, 3));
    }

    private void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.call()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("expected offset and DB state were not observed");
    }

    enum Failure { ONCE, PERSISTENT }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = KafkaAutoConfiguration.class)
    @ComponentScan(basePackageClasses = JpaConfig.class)
    @Import({MySqlTestContainersConfig.class, DatabaseCleanUp.class,
        PurchaseActivityRecorder.class, PurchaseActivityRepositoryImpl.class,
        OrderConfirmationHandler.class, ProcessedOrderConfirmationRepositoryImpl.class,
        OrderConfirmedMessageParser.class, OrderConfirmedRecordAdapter.class})
    static class DatabaseConfig {}
}
