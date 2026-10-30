package com.loopers.interfaces.consumer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.application.purchase.PurchaseActivityHandler;
import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.domain.purchase.OrderConfirmation;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.PurchaseActivityRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.event.ConsumerStoppedEvent;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.listener.adapter.RecordMessagingMessageListenerAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = "order.confirmed-event.auto-startup=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PurchaseActivityListenerIntegrationTest {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(PurchaseActivityListenerIntegrationTest.class);
    private static final String TOPIC = "purchase-listener-" + UUID.randomUUID();
    private static final String GROUP = "purchase-listener-" + UUID.randomUUID();
    private static final Instant CONFIRMED_AT = Instant.parse("2026-09-07T01:00:00.123456Z");
    private static final int WAIT_SECONDS = 20;

    @TestConfiguration
    static class ProbeConfiguration {
        @Bean
        StopProbe stopProbe() {
            return new StopProbe();
        }
    }

    static class StopProbe {
        private final BlockingQueue<ConsumerStoppedEvent> events = new LinkedBlockingQueue<>();

        @EventListener
        public void onStopped(ConsumerStoppedEvent event) {
            events.add(event);
        }

        void awaitStopped(MessageListenerContainer container) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (System.nanoTime() < deadline) {
                ConsumerStoppedEvent event = events.poll(100, TimeUnit.MILLISECONDS);
                if (event != null && event.getContainer(MessageListenerContainer.class) == container) {
                    return;
                }
            }
            throw new AssertionError("구매 활동 consumer 종료 이벤트를 기한 내 관찰하지 못했습니다.");
        }
    }

    @DynamicPropertySource
    static void isolatedKafka(DynamicPropertyRegistry registry) {
        registry.add("order.confirmed-event.topic", () -> TOPIC);
        registry.add("order.confirmed-event.group-id", () -> GROUP);
    }

    @Autowired
    @Qualifier("orderActivityKafkaListenerContainerFactory")
    private ConcurrentKafkaListenerContainerFactory<String, byte[]> recordFactory;
    @Autowired
    @Qualifier(KafkaConfig.BATCH_LISTENER)
    private ConcurrentKafkaListenerContainerFactory<Object, Object> batchFactory;
    @Autowired
    private KafkaListenerEndpointRegistry registry;
    @Autowired
    private KafkaTemplate<Object, Object> producer;
    @Autowired
    private KafkaAdmin kafkaAdmin;
    @MockitoSpyBean
    private PurchaseActivityRepository repository;
    @MockitoSpyBean
    private PurchaseActivityHandler handler;
    @MockitoSpyBean
    private OrderConfirmedConsumer consumer;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private StopProbe stopProbe;

    private final Logger consumerLogger = (Logger) LoggerFactory.getLogger(OrderConfirmedConsumer.class);
    private final ListAppender<ILoggingEvent> errors = new ListAppender<>();
    private CountDownLatch resumeAck = new CountDownLatch(0);
    private CountDownLatch resumeFollowing = new CountDownLatch(0);

    @BeforeEach
    void setUp() {
        stopProbe.events.clear();
        errors.start();
        consumerLogger.addAppender(errors);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        resumeAck.countDown();
        resumeFollowing.countDown();
        try {
            CountDownLatch stopped = new CountDownLatch(1);
            container().stop(stopped::countDown);
            assertThat(stopped.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            producer.flush();
            databaseCleanUp.truncateAllTables();
        } finally {
            consumerLogger.detachAppender(errors);
            errors.stop();
        }
    }

    @DisplayName("실제 구매 활동 consumer는 자동 commit 없이 String key·JSON bytes를 받고 producer·admin과 같은 broker를 사용한다.")
    @Test
    void usesEffectiveConsumerConfiguration() {
        ConsumerConfig consumer = consumerConfig();

        assertThat(consumer.getBoolean(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isFalse();
        assertThat(consumer.getClass(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)).isEqualTo(StringDeserializer.class);
        assertThat(consumer.getClass(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)).isEqualTo(ByteArrayDeserializer.class);
        assertThat(consumer.getString(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).isEqualTo("earliest");
        assertThat(consumer.getBoolean(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG)).isFalse();
        assertThat(consumer.originals()).doesNotContainKey("enable-auto-commit");
        assertThat(consumer.getList(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG))
            .isEqualTo(new ProducerConfig(producer.getProducerFactory().getConfigurationProperties())
                .getList(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG))
            .isEqualTo(new AdminClientConfig(kafkaAdmin.getConfigurationProperties())
                .getList(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG));
    }

    @DisplayName("실제 등록 listener는 별도 record factory의 concurrency 3·동기 MANUAL_IMMEDIATE·중단형 error handler를 사용한다.")
    @Test
    void usesRecordFactoryForActualListener() {
        var container = (ConcurrentMessageListenerContainer<?, ?>) registry.getListenerContainer("purchase-activity-listener");

        assertThat(recordFactory.isBatchListener()).isFalse();
        assertThat(container.getContainerProperties().getMessageListener())
            .isInstanceOf(RecordMessagingMessageListenerAdapter.class);
        assertThat(container.getConcurrency()).isEqualTo(3);
        assertThat(container.getContainerProperties().getTopics()).containsExactly(TOPIC);
        assertThat(container.getContainerProperties().getGroupId()).isEqualTo(GROUP);
        assertThat(container.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        assertThat(container.getContainerProperties().isSyncCommits()).isTrue();
        assertThat(container.getCommonErrorHandler()).isInstanceOf(CommonContainerStoppingErrorHandler.class);
    }

    @DisplayName("테스트의 구매 활동 consumer는 최소 1byte·최대 100ms fetch 대기로 작은 record를 관찰한다.")
    @Test
    void usesShortFetchWaitInTestProfile() {
        ConsumerConfig consumer = consumerConfig();

        assertThat(consumer.getInt(ConsumerConfig.FETCH_MIN_BYTES_CONFIG)).isEqualTo(1);
        assertThat(consumer.getInt(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG)).isEqualTo(100);
    }

    @DisplayName("기존 demo batch factory의 batch·concurrency 3·MANUAL·1MiB와 5초 fetch 설정을 유지한다.")
    @Test
    void preservesExistingBatchFactory() {
        var container = batchFactory.createContainer("configuration-probe");
        ConsumerConfig consumer = new ConsumerConfig(batchFactory.getConsumerFactory().getConfigurationProperties());

        assertThat(batchFactory.isBatchListener()).isTrue();
        assertThat(batchFactory.getConsumerFactory()).isNotSameAs(recordFactory.getConsumerFactory());
        assertThat(container.getConcurrency()).isEqualTo(3);
        assertThat(container.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL);
        assertThat(consumer.getInt(ConsumerConfig.FETCH_MIN_BYTES_CONFIG)).isEqualTo(1_048_576);
        assertThat(consumer.getInt(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG)).isEqualTo(5_000);
    }

    @DisplayName("실제 listener의 DB 실패는 전체 rollback·offset 유지·중단되고, 같은 group 재시작 후 DB commit이 끝나야 ack한다.")
    @Test
    void rollsBackStopsAndRecoversWithoutSkippingRecord() throws Exception {
        kafkaAdmin.createOrModifyTopics(TopicBuilder.name(TOPIC).partitions(1).replicas(1).build());
        var container = container();
        AtomicBoolean fail = new AtomicBoolean(true);
        AtomicInteger failedWrites = new AtomicInteger();
        List<RecordPosition> deliveries = new CopyOnWriteArrayList<>();
        AtomicReference<Thread> listenerThread = new AtomicReference<>();
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        AtomicBoolean transactionActiveAfterHandler = new AtomicBoolean(true);
        CountDownLatch committedInDatabase = new CountDownLatch(1);
        CountDownLatch followingEntered = new CountDownLatch(1);
        resumeAck = new CountDownLatch(1);
        resumeFollowing = new CountDownLatch(1);

        doAnswer(invocation -> {
            invocation.callRealMethod();
            var activity = invocation.getArgument(0, ProductPurchaseActivity.class);
            if (activity.orderId() == 42L && fail.get()) {
                failedWrites.incrementAndGet();
                if (activity.productId() == 8L) {
                    throw new DataAccessResourceFailureException("두 번째 품목 저장 뒤 listener 테스트 오류");
                }
            }
            return null;
        }).when(repository).saveActivity(any());
        doAnswer(invocation -> {
            ConsumerRecord<String, byte[]> record = invocation.getArgument(0);
            if ("42".equals(record.key())) {
                deliveries.add(new RecordPosition(record.topic(), record.partition(), record.offset()));
                listenerThread.set(Thread.currentThread());
            }
            if ("43".equals(record.key())) {
                followingEntered.countDown();
                awaitRelease(resumeFollowing);
            }
            return invocation.callRealMethod();
        }).when(consumer).listen(any(), any());
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (invocation.getArgument(0, OrderConfirmation.class).orderId() == 42L) {
                handlerThread.set(Thread.currentThread());
                transactionActiveAfterHandler.set(TransactionSynchronizationManager.isActualTransactionActive());
                committedInDatabase.countDown();
                awaitRelease(resumeAck);
            }
            return result;
        }).when(handler).consume(any());

        AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties());
        try {
            RecordMetadata warmup = send(41L);
            TopicPartition partition = new TopicPartition(warmup.topic(), warmup.partition());
            container.start();
            awaitAssignment(container, partition);
            await(() -> committed(admin, partition) == warmup.offset() + 1);
            assertStored(41L);

            RecordMetadata target = send(42L);
            RecordMetadata following = send(43L);
            assertThat(target.offset()).isEqualTo(warmup.offset() + 1);
            assertThat(following.offset()).isEqualTo(target.offset() + 1);
            stopProbe.awaitStopped(container);

            assertThat(container.isRunning()).isFalse();
            assertThat(container.isInExpectedState()).isFalse();
            assertThat(failedWrites).hasValue(2);
            assertAbsent(42L);
            assertAbsent(43L);
            assertStored(41L);
            long failedOffset = committed(admin, partition);
            assertThat(failedOffset).isEqualTo(target.offset());
            assertThat(deliveries).containsExactly(position(target));
            assertThat(followingEntered.getCount()).isEqualTo(1);
            assertThat(errors.list).singleElement().satisfies(error -> {
                assertThat(error.getLevel()).isEqualTo(Level.ERROR);
                assertThat(error.getFormattedMessage()).contains("topic=" + TOPIC, "partition=0", "offset=" + target.offset());
                assertThat(error.getThrowableProxy().getMessage()).contains("두 번째 품목 저장 뒤 listener 테스트 오류");
            });

            fail.set(false);
            container.start();
            awaitAssignment(container, partition);
            assertThat(committedInDatabase.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertStored(42L);
            assertAbsent(43L);
            long beforeAck = committed(admin, partition);
            assertThat(beforeAck).isEqualTo(target.offset());
            assertThat(transactionActiveAfterHandler).isFalse();
            assertThat(handlerThread.get()).isSameAs(listenerThread.get());
            assertThat(deliveries).containsExactly(position(target), position(target));

            resumeAck.countDown();
            assertThat(followingEntered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            long afterAck = committed(admin, partition);
            assertThat(afterAck).isEqualTo(target.offset() + 1);
            assertAbsent(43L);

            resumeFollowing.countDown();
            await(() -> committed(admin, partition) == following.offset() + 1);
            assertStored(41L);
            assertStored(42L);
            assertStored(43L);
            log.info("W6_LISTENER_FAILURE topic={}, partition={}, targetOffset={}, stoppedOffset={}, dbCommittedBeforeAck={}, ackedOffset={}, finalCommitted={}, targetDeliveries={}",
                TOPIC, partition.partition(), target.offset(), failedOffset, beforeAck, afterAck,
                committed(admin, partition), deliveries.size());
        } finally {
            resumeAck.countDown();
            resumeFollowing.countDown();
            admin.close(Duration.ofSeconds(2));
        }
    }

    private RecordMetadata send(long orderId) throws Exception {
        Map<String, Object> payload = Map.of("eventType", "OrderConfirmed", "schemaVersion", 1,
            "eventId", "order-confirmed:v1:" + orderId, "orderId", orderId, "confirmedAt", CONFIRMED_AT.toString(),
            "items", List.of(Map.of("productId", 7L, "quantity", 2), Map.of("productId", 8L, "quantity", 1)));
        return producer.send(TOPIC, 0, Long.toString(orderId), payload).get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    private void assertStored(long orderId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.flush();
            entityManager.clear();
            var expected = new OrderConfirmation("order-confirmed:v1:" + orderId, orderId, CONFIRMED_AT,
                List.of(new OrderConfirmation.Item(7L, 2), new OrderConfirmation.Item(8L, 1)));
            assertThat(repository.findActivities(orderId)).isEqualTo(expected.activities());
            assertThat(jdbc.queryForObject("select event_id from processed_order_confirmation where order_id = ?",
                String.class, orderId)).isEqualTo(expected.eventId());
        });
    }

    private void assertAbsent(long orderId) {
        assertThat(jdbc.queryForObject("select count(*) from product_purchase_activity where order_id = ?",
            Integer.class, orderId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from processed_order_confirmation where order_id = ?",
            Integer.class, orderId)).isZero();
    }

    private static RecordPosition position(RecordMetadata metadata) {
        return new RecordPosition(metadata.topic(), metadata.partition(), metadata.offset());
    }

    private long committed(AdminClient admin, TopicPartition partition) {
        try {
            OffsetAndMetadata offset = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                .get(5, TimeUnit.SECONDS).get(partition);
            return offset == null ? -1L : offset.offset();
        } catch (Exception e) {
            throw new IllegalStateException("실제 group committed offset 조회 실패", e);
        }
    }

    private ConcurrentMessageListenerContainer<?, ?> container() {
        return (ConcurrentMessageListenerContainer<?, ?>) registry.getListenerContainer("purchase-activity-listener");
    }

    private static void awaitAssignment(MessageListenerContainer container, TopicPartition partition) throws InterruptedException {
        await(() -> container.getAssignedPartitions() != null
            && container.getAssignedPartitions().size() == 1 && container.getAssignedPartitions().contains(partition));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(50L);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void awaitRelease(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("listener 테스트 대기 해제가 기한을 넘었습니다.");
        }
    }

    private record RecordPosition(String topic, int partition, long offset) {}

    private ConsumerConfig consumerConfig() {
        return new ConsumerConfig(recordFactory.getConsumerFactory().getConfigurationProperties());
    }
}
