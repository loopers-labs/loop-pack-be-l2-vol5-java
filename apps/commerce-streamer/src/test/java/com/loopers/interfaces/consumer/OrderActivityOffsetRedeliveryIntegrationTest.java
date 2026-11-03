package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.PurchaseActivityHandler;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** C05: controlled close after a real DB commit, separate from the Spring listener DB-failure test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(60)
class OrderActivityOffsetRedeliveryIntegrationTest {
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final String TOPIC = "order-events-offset-gap-" + UUID.randomUUID();
    private static final String GROUP = "purchase-offset-gap-" + UUID.randomUUID();
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-29T00:00:00.123456789Z");
    private static final byte[] PAYLOAD = """
        {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:42",
         "orderId":42,"confirmedAt":"2026-10-29T00:00:00.123456789Z",
         "items":[{"productId":7,"quantity":2},{"productId":8,"quantity":1}]}
        """.getBytes(StandardCharsets.UTF_8);

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("purchase_offset_gap_test")
        .withInitScript("db/schema/001-purchase-activity.sql");
    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @Autowired private OrderConfirmedEventParser parser;
    @Autowired private PurchaseActivityHandler handler;
    @Autowired @Qualifier("orderActivityKafkaListenerContainerFactory")
    private ConcurrentKafkaListenerContainerFactory<String, byte[]> factory;

    @DynamicPropertySource
    static void ownedResources(DynamicPropertyRegistry properties) {
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

    @Test
    void committedPurchaseSurvivesCloseBeforeOffsetCommitAndTheSameRecordIsAppliedOnlyOnce() throws Exception {
        assertThat(factory.getConsumerFactory().getConfigurationProperties().get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG))
            .isEqualTo(false);
        AdminClient admin = AdminClient.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000,
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000));
        try {
            admin.createTopics(List.of(new NewTopic(TOPIC, 2, (short) 1))).all().get(10, TimeUnit.SECONDS);
            TopicPosition target = publishOnce();
            List<TopicPosition> deliveries = new ArrayList<>();
            List<Long> committedOffsets = new ArrayList<>();
            DatabaseSnapshot stored;

            Consumer<String, byte[]> first = newConsumer("first");
            try {
                prepareFirstConsumer(first, target);
                committedOffsets.add(committed(admin, target.partition()));
                assertThat(committedOffsets).containsExactly(target.offset());
                assertThat(snapshot().confirmations()).isEmpty();
                assertThat(snapshot().activities()).isEmpty();

                ConsumerRecord<String, byte[]> record = pollOne(first);
                deliveries.add(positionOf(record));
                assertRecord(record, target);
                assertThat(handler.consume(parser.parseAndValidate(record.key(), record.value())))
                    .isEqualTo(PurchaseActivityHandler.Result.APPLIED);
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                // A new physical JDBC connection sees committed rows, not a persistence-context/flush illusion.
                stored = snapshot();
                assertPurchase(stored);
                assertThat(first.position(target.partition(), REQUEST_TIMEOUT)).isEqualTo(target.offset() + 1);
                assertThat(committed(admin, target.partition())).isEqualTo(target.offset());
                // Deliberately end here: no ack, commitAsync, commitSync(n+1), or close callback.
            } finally {
                first.close(CLOSE_TIMEOUT);
            }

            committedOffsets.add(committed(admin, target.partition()));
            assertThat(committedOffsets).containsExactly(target.offset(), target.offset());
            assertThat(snapshot()).isEqualTo(stored);

            Consumer<String, byte[]> replacement = newConsumer("replacement");
            try {
                // Same broker/topic/group/DB; no seek, reset, or new producer record on this consumer.
                replacement.subscribe(List.of(TOPIC));
                ConsumerRecord<String, byte[]> replay = pollOne(replacement);
                deliveries.add(positionOf(replay));
                assertRecord(replay, target);
                assertThat(replacement.position(target.partition(), REQUEST_TIMEOUT)).isEqualTo(target.offset() + 1);
                assertThat(committed(admin, target.partition())).isEqualTo(target.offset());
                assertThat(handler.consume(parser.parseAndValidate(replay.key(), replay.value())))
                    .isEqualTo(PurchaseActivityHandler.Result.DUPLICATE);
                assertThat(snapshot()).as("including PKs: no insertion, deletion, quantity or time changes").isEqualTo(stored);

                replacement.commitSync(Map.of(target.partition(), new OffsetAndMetadata(target.offset() + 1)), REQUEST_TIMEOUT);
                committedOffsets.add(committed(admin, target.partition()));
                assertThat(committedOffsets).containsExactly(target.offset(), target.offset(), target.offset() + 1);
                assertThat(deliveries).containsExactly(target, target);
                assertThat(snapshot()).isEqualTo(stored);
                System.out.printf("offset_gap_verified topic=%s group=%s partition=%d offset=%d deliveries=%d "
                        + "committed=%s completionRows=%d activityRows=%d results=APPLIED,DUPLICATE%n",
                    TOPIC, GROUP, target.partition().partition(), target.offset(), deliveries.size(), committedOffsets,
                    stored.confirmations().size(), stored.activities().size());
            } finally {
                replacement.close(CLOSE_TIMEOUT);
            }
            assertThat(committed(admin, target.partition())).isEqualTo(target.offset() + 1);
        } finally {
            admin.close(CLOSE_TIMEOUT);
        }
    }

    private Consumer<String, byte[]> newConsumer(String suffix) {
        return new DefaultKafkaConsumerFactory<String, byte[]>(factory.getConsumerFactory().getConfigurationProperties())
            .createConsumer(GROUP, "offset-gap-", suffix);
    }

    private void prepareFirstConsumer(Consumer<String, byte[]> consumer, TopicPosition target) {
        consumer.subscribe(List.of(TOPIC), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) { }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                consumer.pause(partitions); // Do not silently discard the target in a setup poll.
            }
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (consumer.assignment().size() < 2 && System.nanoTime() < deadline) {
            assertThat(consumer.poll(Duration.ofMillis(100))).isEmpty();
        }
        assertThat(consumer.assignment()).hasSize(2).contains(target.partition());
        consumer.commitSync(Map.of(target.partition(), new OffsetAndMetadata(target.offset())), REQUEST_TIMEOUT);
        consumer.seek(target.partition(), target.offset()); // First consumer setup only, never used for the replacement.
        consumer.resume(consumer.assignment());
    }

    private ConsumerRecord<String, byte[]> pollOne(Consumer<String, byte[]> consumer) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            var records = consumer.poll(Duration.ofMillis(100));
            if (!records.isEmpty()) {
                assertThat(records.count()).isEqualTo(1);
                return records.iterator().next();
            }
        }
        throw new AssertionError("Expected the target record within 15 seconds");
    }

    private TopicPosition publishOnce() throws Exception {
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
            ProducerConfig.ACKS_CONFIG, "all",
            ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000,
            ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000,
            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000));
        try {
            var metadata = producer.send(new ProducerRecord<>(TOPIC, "42", PAYLOAD)).get(10, TimeUnit.SECONDS);
            return new TopicPosition(new TopicPartition(metadata.topic(), metadata.partition()), metadata.offset());
        } finally {
            producer.close(CLOSE_TIMEOUT);
        }
    }

    private long committed(AdminClient admin, TopicPartition partition) throws Exception {
        var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
        assertThat(offsets).containsKey(partition);
        return offsets.get(partition).offset();
    }

    private void assertRecord(ConsumerRecord<String, byte[]> record, TopicPosition target) {
        assertThat(positionOf(record)).isEqualTo(target);
        assertThat(record.key()).isEqualTo("42");
        assertThat(record.value()).containsExactly(PAYLOAD);
    }

    private TopicPosition positionOf(ConsumerRecord<String, byte[]> record) {
        return new TopicPosition(new TopicPartition(record.topic(), record.partition()), record.offset());
    }

    private DatabaseSnapshot snapshot() throws Exception {
        try (var connection = MYSQL.createConnection("")) {
            JdbcTemplate observer = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            return new DatabaseSnapshot(
                observer.queryForList("SELECT * FROM processed_order_confirmation ORDER BY id"),
                observer.queryForList("SELECT * FROM product_purchase_activity ORDER BY id"));
        }
    }

    private void assertPurchase(DatabaseSnapshot snapshot) {
        assertThat(snapshot.confirmations()).hasSize(1);
        assertThat(snapshot.activities()).hasSize(2);
        assertThat(snapshot.confirmations().getFirst()).containsEntry("event_id", "order-confirmed:v1:42")
            .containsEntry("order_id", 42L).containsEntry("event_type", "OrderConfirmed").containsEntry("schema_version", 1)
            .containsEntry("confirmed_at_second", CONFIRMED_AT.getEpochSecond()).containsEntry("confirmed_at_nano", 123456789);
        assertThat(snapshot.activities()).extracting(row -> row.get("product_id")).containsExactly(7L, 8L);
        assertThat(snapshot.activities()).extracting(row -> row.get("quantity")).containsExactly(2, 1);
        for (Map<String, Object> row : snapshot.activities()) {
            assertThat(row).containsEntry("order_id", 42L).containsEntry("confirmed_at_second", CONFIRMED_AT.getEpochSecond())
                .containsEntry("confirmed_at_nano", 123456789);
        }
    }

    private record TopicPosition(TopicPartition partition, long offset) { }

    private record DatabaseSnapshot(List<Map<String, Object>> confirmations, List<Map<String, Object>> activities) { }
}
