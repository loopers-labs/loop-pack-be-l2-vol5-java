package com.loopers.interfaces.consumer;

import com.loopers.domain.purchase.OrderConfirmation;
import com.loopers.domain.purchase.PurchaseActivityStore;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/** Actual Spring listener, manual DDL MySQL and owned broker. No API/current product database is consulted. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
@Timeout(45)
class OrderActivityKafkaIntegrationTest {
    private static final String TOPIC = "order-events-consumption-" + UUID.randomUUID();
    private static final String GROUP = "purchase-activity-test-" + UUID.randomUUID();
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("purchase_activity_listener_test")
        .withInitScript("db/schema/001-purchase-activity.sql");
    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private KafkaListenerEndpointRegistry registry;
    @Autowired @Qualifier("orderActivityKafkaListenerContainerFactory")
    private ConcurrentKafkaListenerContainerFactory<String, byte[]> factory;
    @MockitoSpyBean private PurchaseActivityStore store;
    private KafkaProducer<String, String> producer;
    private AdminClient admin;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("datasource.mysql-jpa.main.jdbc-url", MYSQL::getJdbcUrl);
        properties.add("datasource.mysql-jpa.main.username", MYSQL::getUsername);
        properties.add("datasource.mysql-jpa.main.password", MYSQL::getPassword);
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        properties.add("spring.kafka.admin.properties.bootstrap.servers", KAFKA::getBootstrapServers);
        properties.add("commerce.purchase-activity.topic", () -> TOPIC);
        properties.add("commerce.purchase-activity.group-id", () -> GROUP);
        properties.add("commerce.purchase-activity.auto-startup", () -> false);
        properties.add("demo-kafka.test.enabled", () -> false);
    }

    @BeforeAll
    static void prepareTopic() throws Exception {
        try (AdminClient client = adminClient()) {
            client.createTopics(List.of(new NewTopic(TOPIC, 2, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void prepareClients() {
        admin = adminClient();
        producer = new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.ACKS_CONFIG, "all",
            ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000,
            ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000,
            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000));
    }

    @AfterEach
    void closeClientsAndClearOwnedData() throws InterruptedException {
        var listener = registry.getListenerContainer(OrderActivityKafkaListener.LISTENER_ID);
        CountDownLatch stopped = new CountDownLatch(1);
        listener.stop(stopped::countDown);
        assertThat(stopped.await(10, TimeUnit.SECONDS)).as("consumer must stop before resetting its DB").isTrue();
        try {
            if (producer != null) {
                producer.close(Duration.ofSeconds(5));
            }
        } finally {
            if (admin != null) {
                admin.close(Duration.ofSeconds(5));
            }
        }
        reset(target());
        jdbc.update("DELETE FROM product_purchase_activity");
        jdbc.update("DELETE FROM processed_order_confirmation");
    }

    @Test
    void realListenerCommitsEveryItemBeforeAckAndAnIdenticalReplayAddsNothing(CapturedOutput output) throws Exception {
        startListener();
        RecordMetadata first = send(42L, payload(42L, "[{\"productId\":7,\"quantity\":2},{\"productId\":8,\"quantity\":1}]"));
        awaitAck(first);
        assertCounts(1, 2);
        assertThat(jdbc.queryForList("SELECT quantity FROM product_purchase_activity ORDER BY product_id", Integer.class))
            .containsExactly(2, 1);
        assertThat(jdbc.queryForObject("SELECT confirmed_at_nano FROM processed_order_confirmation", Integer.class))
            .isEqualTo(123456789);

        RecordMetadata duplicate = send(42L, payload(42L,
            "[{\"productId\":8,\"quantity\":1},{\"productId\":7,\"quantity\":1},{\"productId\":7,\"quantity\":1}]"));
        awaitAck(duplicate);
        assertCounts(1, 2);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getAll()).contains("result=DUPLICATE"));

        RecordMetadata another = send(43L, payload(43L, "[{\"productId\":7,\"quantity\":3}]"));
        awaitAck(another);
        assertCounts(2, 3);
        assertThat(jdbc.queryForObject("SELECT SUM(quantity) FROM product_purchase_activity WHERE product_id=7", Long.class))
            .isEqualTo(5);
        // There are no commerce Order/Product tables in this database; these are independent historical facts.
    }

    @Test
    void realSqlFailureStopsWithoutCommittingTheRecordAndSameGroupRestartRecovers(CapturedOutput output) throws Exception {
        AtomicBoolean sawPartialSql = new AtomicBoolean();
        doAnswer(invocation -> {
            OrderConfirmation.Item item = invocation.getArgument(2);
            if (item.productId() == 8L) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertCounts(1, 1);
                sawPartialSql.set(true);
                throw new TestWriteFailure();
            }
            return invocation.callRealMethod();
        }).when(target()).insertActivity(anyLong(), any(Instant.class), any(OrderConfirmation.Item.class));

        startListener();
        RecordMetadata failing = send(52L, payload(52L, "[{\"productId\":7,\"quantity\":2},{\"productId\":8,\"quantity\":1}]"));
        var listener = registry.getListenerContainer(OrderActivityKafkaListener.LISTENER_ID);
        await().atMost(Duration.ofSeconds(15)).until(() -> !listener.isRunning());
        assertThat(sawPartialSql).isTrue();
        assertCounts(0, 0);
        assertThat(committed(failing)).isLessThanOrEqualTo(failing.offset());
        assertThat(output.getAll()).contains("purchase_activity_processing_failed", "errorType=TestWriteFailure");

        reset(target());
        startListener(); // Same topic and group. No seek, reset, or replacement record.
        awaitAck(failing);
        assertCounts(1, 2);
        assertThat(committed(failing)).isEqualTo(failing.offset() + 1);
        assertThat(output.getAll()).contains("purchase_activity_completed");
    }

    @Test
    void effectiveFactoryUsesRecordManualCommitAndTheOwnedBroker() {
        var config = factory.getConsumerFactory().getConfigurationProperties();
        assertThat(config.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG).toString()).contains(KAFKA.getBootstrapServers());
        assertThat(config.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)).isEqualTo(StringDeserializer.class);
        assertThat(config.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)).isEqualTo(ByteArrayDeserializer.class);
        assertThat(config.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isEqualTo(false);
        assertThat(config.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG)).isEqualTo(1);
        assertThat(config.get(ConsumerConfig.FETCH_MIN_BYTES_CONFIG)).isEqualTo(1);
        assertThat(config.get(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG)).isEqualTo(100);
        assertThat(factory.isBatchListener()).isFalse();
        var container = (ConcurrentMessageListenerContainer<?, ?>)
            registry.getListenerContainer(OrderActivityKafkaListener.LISTENER_ID);
        assertThat(container.getConcurrency()).isEqualTo(1);
        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        assertThat(factory.getContainerProperties().isSyncCommits()).isTrue();
        assertThat(factory.getContainerProperties().isAsyncAcks()).isFalse();
    }

    private void startListener() {
        var listener = registry.getListenerContainer(OrderActivityKafkaListener.LISTENER_ID);
        listener.start();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(listener.getAssignedPartitions()).hasSize(2));
    }

    private RecordMetadata send(long orderId, String json) throws Exception {
        return producer.send(new ProducerRecord<>(TOPIC, Long.toString(orderId), json)).get(10, TimeUnit.SECONDS);
    }

    private void awaitAck(RecordMetadata record) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(committed(record)).isEqualTo(record.offset() + 1));
    }

    private long committed(RecordMetadata record) throws Exception {
        var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
        var offset = offsets.get(new TopicPartition(record.topic(), record.partition()));
        return offset == null ? -1L : offset.offset();
    }

    private String payload(long orderId, String items) {
        return "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"eventId\":\"order-confirmed:v1:" + orderId
            + "\",\"orderId\":" + orderId + ",\"confirmedAt\":\"2026-10-29T00:00:00.123456789Z\",\"items\":" + items + "}";
    }

    private void assertCounts(int confirmations, int activities) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_order_confirmation", Integer.class)).isEqualTo(confirmations);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_purchase_activity", Integer.class)).isEqualTo(activities);
    }

    private PurchaseActivityStore target() {
        return AopTestUtils.getUltimateTargetObject(store);
    }

    private static AdminClient adminClient() {
        return AdminClient.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000,
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000));
    }

    private static final class TestWriteFailure extends RuntimeException { }
}
