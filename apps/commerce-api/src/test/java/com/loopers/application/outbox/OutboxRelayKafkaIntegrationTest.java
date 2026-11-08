package com.loopers.application.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.outbox.OutboxDeliveryAttempt;
import com.loopers.domain.outbox.OutboxMessage;
import com.loopers.domain.outbox.OutboxReceipt;
import com.loopers.domain.outbox.OutboxRepository;
import com.loopers.domain.outbox.OutboxSender;
import com.loopers.domain.outbox.OutboxStatus;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Real Outbox state transactions and actual broker ACK; PG creation and consumer business storage remain separate tests. */
@SpringBootTest(properties = {"commerce.outbox-relay.enabled=false", "commerce.order-events.enabled=false"})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(40)
class OutboxRelayKafkaIntegrationTest {
    private static final String TOPIC = "w7-relay-db-broker-" + UUID.randomUUID();
    private static final String EVENT_ID = "order-confirmed:v1:42";
    private static final Instant CONFIRMED_AT = Instant.parse("2026-11-04T03:04:05.123456Z");
    private static final List<TopicPartition> PARTITIONS =
        List.of(new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1));
    private static final String PAYLOAD = """
        { "eventType":"OrderConfirmed", "schemaVersion":1,
          "eventId":"order-confirmed:v1:42", "orderId":42,
          "confirmedAt":"2026-11-04T03:04:05.123456Z",
          "items":[{"productId":7,"quantity":2},{"productId":8,"quantity":1}] }
        """;

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @Autowired private OutboxRelay relay;
    @Autowired private OutboxRepository outbox;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private DatabaseCleanUp cleanup;
    @MockitoSpyBean private OutboxSender sender;

    @DynamicPropertySource
    static void configureOwnedBroker(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        properties.add("spring.kafka.admin.properties.bootstrap.servers", KAFKA::getBootstrapServers);
        // The persisted topic must be used even when the current route points somewhere else.
        properties.add("commerce.order-events.topic", () -> "unused-current-route");
    }

    @BeforeAll
    static void createOwnedTopic() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000,
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 2, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
    }

    @AfterEach
    void cleanOnlyOwnedDatabase() {
        cleanup.truncateAllTables();
        // DirtiesContext closes the producer, and Testcontainers removes only this test's broker/topic/group.
    }

    @Test
    void actualBrokerReceiptPrecedesPublishedCommitAndAPublishedRowIsNotSentAgain() throws Exception {
        // This is a new W7 relay fixture, not a recreated W6 missing-publication scenario.
        OutboxMessage saved = new TransactionTemplate(transactionManager).execute(status -> outbox.save(
            OutboxMessage.pending(EVENT_ID, "Order", "42", "OrderConfirmed", 1,
                TOPIC, "42", PAYLOAD, Instant.now().minusSeconds(1))));
        Map<String, Object> immutableFact = facts(saved.getId());
        AtomicReference<OutboxReceipt> actualReceipt = new AtomicReference<>();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            OutboxDeliveryAttempt attempt = invocation.getArgument(0);
            assertThat(attempt.id()).isEqualTo(saved.getId());
            assertThat(attempt.eventId()).isEqualTo(EVENT_ID);
            assertThat(attempt.topic()).isEqualTo(TOPIC);
            assertThat(attempt.messageKey()).isEqualTo("42");
            assertThat(attempt.payload()).isEqualTo(PAYLOAD);
            assertThat(attempt.attemptCount()).isEqualTo(1);
            assertPendingCommittedAttempt(saved.getId());

            @SuppressWarnings("unchecked")
            CompletableFuture<OutboxReceipt> realSend =
                (CompletableFuture<OutboxReceipt>) invocation.callRealMethod();
            return realSend.thenApply(receipt -> {
                // The relay cannot mark PUBLISHED until this actual metadata future finishes.
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertPendingCommittedAttempt(saved.getId());
                actualReceipt.set(receipt);
                return receipt;
            });
        }).when(sender).send(any(OutboxDeliveryAttempt.class));

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.assign(PARTITIONS);
            Map<TopicPartition, Long> before = consumer.endOffsets(PARTITIONS, Duration.ofSeconds(10));
            before.forEach(consumer::seek);

            assertThat(relay.runOnce()).isEqualTo(new OutboxRelay.Result(1, 1, false));

            OutboxMessage published = outbox.findByEventId(EVENT_ID).orElseThrow();
            assertThat(published.getId()).isEqualTo(saved.getId());
            assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(published.getAttemptCount()).isEqualTo(1);
            assertThat(published.getPublishedAt()).isNotNull();
            assertThat(published.getLastError()).isNull();
            assertThat(facts(saved.getId())).isEqualTo(immutableFact);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_message", Long.class)).isEqualTo(1);

            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (records.isEmpty() && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            assertThat(records).as("actual saved fact read from owned Kafka").hasSize(1);
            ConsumerRecord<String, String> record = records.getFirst();
            assertThat(record.topic()).isEqualTo(TOPIC);
            assertThat(record.key()).isEqualTo("42");
            assertThat(record.value()).isEqualTo(PAYLOAD);
            assertThat(record.headers().toArray()).isEmpty();
            assertThat(actualReceipt.get()).isEqualTo(new OutboxReceipt(record.topic(), record.partition(), record.offset()));
            var json = mapper.readTree(record.value());
            assertThat(json.isObject()).isTrue();
            assertThat(json.size()).isEqualTo(6);
            assertThat(json.get("eventType").asText()).isEqualTo("OrderConfirmed");
            assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
            assertThat(json.get("eventId").asText()).isEqualTo(EVENT_ID);
            assertThat(json.get("orderId").isIntegralNumber()).isTrue();
            assertThat(json.get("orderId").asLong()).isEqualTo(42);
            assertThat(Instant.parse(json.get("confirmedAt").asText())).isEqualTo(CONFIRMED_AT);
            assertThat(json.get("items")).isEqualTo(mapper.readTree("[{\"productId\":7,\"quantity\":2},{\"productId\":8,\"quantity\":1}]"));

            Map<TopicPartition, Long> afterPublish = consumer.endOffsets(PARTITIONS, Duration.ofSeconds(10));
            long appended = PARTITIONS.stream().mapToLong(partition -> afterPublish.get(partition) - before.get(partition)).sum();
            assertThat(appended).isEqualTo(1);
            Map<String, Object> publishedRow = row(saved.getId());

            assertThat(relay.runOnce()).isEqualTo(new OutboxRelay.Result(0, 0, false));

            assertThat(row(saved.getId())).isEqualTo(publishedRow);
            assertThat(consumer.endOffsets(PARTITIONS, Duration.ofSeconds(10))).isEqualTo(afterPublish);
            assertThat(consumer.poll(Duration.ofMillis(300)).isEmpty()).isTrue();
            verify(sender, times(1)).send(any(OutboxDeliveryAttempt.class));
        }
    }

    private void assertPendingCommittedAttempt(long id) {
        Map<String, Object> state = row(id);
        assertThat(state.get("status")).isEqualTo("PENDING");
        assertThat(state.get("attempt_count")).isEqualTo(1);
        assertThat(state.get("published_at")).isNull();
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM outbox_message WHERE id=?", id);
    }

    private Map<String, Object> facts(long id) {
        return jdbc.queryForMap("SELECT id,event_id,aggregate_type,aggregate_id,event_type,schema_version,"
            + "topic,message_key,payload,created_at FROM outbox_message WHERE id=?", id);
    }

    private KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "w7-relay-db-broker-" + UUID.randomUUID(),
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000,
            ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000));
    }
}
