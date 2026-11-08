package com.loopers.application.outbox;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Pauses only its owned broker. No fabricated failed future, user DB, or existing Kafka topic/group. */
@SpringBootTest(properties = {
    "commerce.outbox-relay.enabled=false", "commerce.order-events.enabled=false",
    "spring.kafka.producer.properties.request.timeout.ms=1000",
    "spring.kafka.producer.properties.delivery.timeout.ms=3000"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(90)
class OutboxRelayKafkaOutageIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelayKafkaOutageIntegrationTest.class);
    private static final String TOPIC = "w7-outage-" + UUID.randomUUID();
    private static final String GROUP = "w7-outage-inspector-" + UUID.randomUUID();
    private static final String EVENT_ID = "order-confirmed:v1:42";
    private static final Instant NOW = Instant.parse("2026-11-06T03:04:05.123456Z");
    private static final Instant CREATED_AT = NOW.minusSeconds(120);
    private static final List<TopicPartition> PARTITIONS =
        List.of(new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1));
    private static final String PAYLOAD = """
        { "eventType":"OrderConfirmed", "schemaVersion":1,
          "eventId":"order-confirmed:v1:42", "orderId":42,
          "confirmedAt":"2026-11-06T03:02:05.123456Z",
          "items":[{"productId":7,"quantity":2},{"productId":8,"quantity":1}] }
        """;

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @Autowired private OutboxDeliveryTransactions transactions;
    @Autowired private OutboxRelaySettings settings;
    @Autowired private OutboxRepository outbox;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired @Qualifier("outboxKafkaTemplate") private KafkaTemplate<String, String> kafka;
    @MockitoSpyBean private OutboxSender sender;

    private final MutableClock clock = new MutableClock(NOW);
    private final List<CompletableFuture<OutboxReceipt>> actualSends = new ArrayList<>();

    @DynamicPropertySource
    static void configureOwnedBroker(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        properties.add("spring.kafka.admin.properties.bootstrap.servers", KAFKA::getBootstrapServers);
        properties.add("commerce.order-events.topic", () -> "unused-outage-route");
    }

    @BeforeAll
    static void createOwnedTopic() throws Exception {
        try (AdminClient admin = admin()) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 2, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
    }

    @AfterEach
    void cleanOnlyOwnedResources() {
        try {
            resumeOwnedBroker();
        } finally {
            cleanup.truncateAllTables();
        }
        // DirtiesContext closes producers; Testcontainers owns and removes this broker, not a developer broker.
    }

    @Test
    void realKafkaOutagePreservesObservableBacklogAndANewRelayPublishesTheSameRowAfterRecovery() throws Exception {
        OutboxMessage saved = new TransactionTemplate(transactionManager).execute(status -> outbox.save(
            OutboxMessage.pending(EVENT_ID, "Order", "42", "OrderConfirmed", 1,
                TOPIC, "42", PAYLOAD, CREATED_AT)));
        Map<String, Object> immutableFact = facts(saved.getId());
        assertBacklog("prepared", 1, 0, CREATED_AT, 0);
        observeRealSends(saved.getId());

        try (KafkaConsumer<String, String> inspector = inspector()) {
            inspector.assign(PARTITIONS);
            // Establish producer identity as well as metadata before the communication outage.
            // This owned readiness record is excluded by the following end-offset baseline.
            kafka.send(TOPIC, "outage-readiness", "{\"probe\":true}").get(10, TimeUnit.SECONDS);
            Map<TopicPartition, Long> before = inspector.endOffsets(PARTITIONS, Duration.ofSeconds(10));
            before.forEach(inspector::seek);
            assertThat(kafka.partitionsFor(TOPIC)).hasSize(2); // Real metadata before this broker becomes unavailable.
            pauseOwnedBroker();
            try {
                var initialRunner = newRunner();
                assertThat(initialRunner.runOnce()).isEqualTo(new OutboxRelay.Result(1, 0, false));
                assertFailedPending(saved, 1, NOW.plusSeconds(5), immutableFact);
                assertBacklog("first-failure", 1, 0, CREATED_AT, 1);
                assertThat(actualSends).hasSize(1);
                assertThat(actualSends.getFirst()).isCompletedExceptionally();

                Map<String, Object> waiting = row(saved.getId());
                clock.moveTo(NOW.plusSeconds(5).minusNanos(1_000));
                assertThat(initialRunner.runOnce()).isEqualTo(new OutboxRelay.Result(0, 0, false));
                assertThat(row(saved.getId())).isEqualTo(waiting);
                assertThat(actualSends).hasSize(1);

                // Move only the persisted retry clock; actual Kafka network timeouts use real elapsed time.
                clock.moveTo(NOW.plusSeconds(5));
                assertThat(initialRunner.runOnce()).isEqualTo(new OutboxRelay.Result(1, 0, false));
                assertFailedPending(saved, 2, NOW.plusSeconds(15), immutableFact);
                assertBacklog("repeated-failure", 1, 0, CREATED_AT, 2);
                assertThat(actualSends).hasSize(2).allSatisfy(future -> assertThat(future).isCompletedExceptionally());
            } finally {
                resumeOwnedBroker();
            }

            try (AdminClient admin = admin()) {
                assertThat(admin.describeCluster().nodes().get(15, TimeUnit.SECONDS)).hasSize(1);
            }
            clock.moveTo(NOW.plusSeconds(15));
            assertThat(newRunner().runOnce()).isEqualTo(new OutboxRelay.Result(1, 1, false));

            OutboxMessage published = outbox.findByEventId(EVENT_ID).orElseThrow();
            assertThat(published.getId()).isEqualTo(saved.getId());
            assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(published.getAttemptCount()).isEqualTo(3);
            assertThat(published.getPublishedAt()).isEqualTo(clock.instant());
            assertThat(published.getLastError()).isNull();
            assertThat(facts(saved.getId())).isEqualTo(immutableFact);
            assertBacklog("recovered", 0, 1, null, 3);
            assertThat(actualSends).hasSize(3);
            OutboxReceipt receipt = actualSends.getLast().get(1, TimeUnit.SECONDS);

            Map<TopicPartition, Long> after = inspector.endOffsets(PARTITIONS, Duration.ofSeconds(10));
            List<ConsumerRecord<String, String>> records = readThrough(inspector, after);
            // Pause does not discard TCP buffers: an unacknowledged attempt can also arrive after recovery.
            assertThat(records).isNotEmpty().allSatisfy(record -> {
                assertThat(record.topic()).isEqualTo(TOPIC);
                assertThat(record.key()).isEqualTo("42");
                assertThat(record.value()).isEqualTo(PAYLOAD);
                assertThat(record.headers().toArray()).isEmpty();
            });
            assertThat(records).anySatisfy(record -> assertThat(new OutboxReceipt(
                record.topic(), record.partition(), record.offset())).isEqualTo(receipt));
            long appended = PARTITIONS.stream().mapToLong(partition -> after.get(partition) - before.get(partition)).sum();
            assertThat(records.size()).isGreaterThanOrEqualTo(Math.toIntExact(appended));
            Map<String, Object> finalRow = row(saved.getId());

            assertThat(newRunner().runOnce()).isEqualTo(new OutboxRelay.Result(0, 0, false));
            assertThat(row(saved.getId())).isEqualTo(finalRow);
            // An old unacknowledged send may arrive late. Only this idle run's extra sends are forbidden.
            verify(sender, times(3)).send(any(OutboxDeliveryAttempt.class));
            log.info("w7_outage_recovery eventId={} outboxId={} topic={} inspectorGroup={} attempts={} brokerRecords={} "
                    + "ackPartition={} ackOffset={} immutableFactPreserved=true finalStatus=PUBLISHED",
                EVENT_ID, saved.getId(), TOPIC, GROUP, published.getAttemptCount(), records.size(),
                receipt.partition(), receipt.offset());
        }
    }

    private void observeRealSends(long id) {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            OutboxDeliveryAttempt attempt = invocation.getArgument(0);
            assertThat(attempt.id()).isEqualTo(id);
            assertThat(attempt.eventId()).isEqualTo(EVENT_ID);
            assertThat(attempt.topic()).isEqualTo(TOPIC);
            assertThat(attempt.messageKey()).isEqualTo("42");
            assertThat(attempt.payload()).isEqualTo(PAYLOAD);
            assertThat(row(id).get("status")).isEqualTo("PENDING");
            assertThat(row(id).get("attempt_count")).isEqualTo(attempt.attemptCount());
            @SuppressWarnings("unchecked")
            CompletableFuture<OutboxReceipt> actual = (CompletableFuture<OutboxReceipt>) invocation.callRealMethod();
            actualSends.add(actual);
            return actual;
        }).when(sender).send(any(OutboxDeliveryAttempt.class));
    }

    private void assertFailedPending(OutboxMessage original, int attempts, Instant next,
                                     Map<String, Object> immutableFact) {
        var pending = outbox.findByEventId(EVENT_ID).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(pending.getPublishedAt()).isNull();
        assertThat(pending.getAttemptCount()).isEqualTo(attempts);
        assertThat(pending.getLastError()).isEqualTo("SEND_FAILED");
        assertThat(pending.getNextAttemptAt()).isEqualTo(next);
        assertThat(facts(original.getId())).isEqualTo(immutableFact);
        var failed = jdbc.queryForMap("SELECT event_id,attempt_count,next_attempt_at,last_error "
            + "FROM outbox_message WHERE status='PENDING' ORDER BY next_attempt_at,id LIMIT 10");
        assertThat(failed.get("event_id")).isEqualTo(EVENT_ID);
        assertThat(failed.get("attempt_count")).isEqualTo(attempts);
        assertThat(failed.get("last_error")).isEqualTo("SEND_FAILED");
        log.info("w7_outage_failure eventId={} attempt={} nextAttemptAt={} lastError={}",
            EVENT_ID, attempts, next, pending.getLastError());
    }

    private void assertBacklog(String phase, long pending, long published, Instant oldest, long attempts) {
        var observed = jdbc.queryForObject("SELECT COUNT(*) AS total, "
            + "SUM(status='PENDING') AS pending, SUM(status='PUBLISHED') AS published, "
            + "MIN(CASE WHEN status='PENDING' THEN created_at END) AS oldest, SUM(attempt_count) AS attempts "
            + "FROM outbox_message", (result, index) -> {
                // DATETIME has no zone; Hibernate persists these Instant fields as UTC, independent of JVM zone.
                var timestamp = result.getObject("oldest", LocalDateTime.class);
                return new Backlog(result.getLong("total"), result.getLong("pending"), result.getLong("published"),
                    timestamp == null ? null : timestamp.toInstant(ZoneOffset.UTC), result.getLong("attempts"));
            });
        assertThat(observed).isEqualTo(new Backlog(1, pending, published, oldest, attempts));
        log.info("w7_outage_backlog phase={} pending={} published={} oldestPendingAt={} attempts={}",
            phase, pending, published, oldest, attempts);
    }

    private List<ConsumerRecord<String, String>> readThrough(KafkaConsumer<String, String> inspector,
                                                           Map<TopicPartition, Long> end) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            inspector.poll(Duration.ofMillis(200)).forEach(records::add);
            if (PARTITIONS.stream().allMatch(partition -> inspector.position(partition) >= end.get(partition))) {
                return records;
            }
        }
        throw new AssertionError("Owned Kafka inspector did not reach expected offsets: " + end);
    }

    private OutboxRelay newRunner() {
        return new OutboxRelay(transactions, sender, clock, settings, System::nanoTime);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM outbox_message WHERE id=?", id);
    }

    private Map<String, Object> facts(long id) {
        return jdbc.queryForMap("SELECT id,event_id,aggregate_type,aggregate_id,event_type,schema_version,"
            + "topic,message_key,payload,created_at FROM outbox_message WHERE id=?", id);
    }

    private static void pauseOwnedBroker() {
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
        assertThat(docker.inspectContainerCmd(KAFKA.getContainerId()).exec().getState().getPaused()).isTrue();
    }

    private static void resumeOwnedBroker() {
        var docker = DockerClientFactory.instance().client();
        if (Boolean.TRUE.equals(docker.inspectContainerCmd(KAFKA.getContainerId()).exec().getState().getPaused())) {
            docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }
    }

    private static AdminClient admin() {
        return AdminClient.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000,
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 15_000));
    }

    private KafkaConsumer<String, String> inspector() {
        return new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, GROUP,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000,
            ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000));
    }

    private record Backlog(long total, long pending, long published, Instant oldest, long attempts) { }

    private static final class MutableClock extends Clock {
        private Instant current;
        private MutableClock(Instant current) { this.current = current; }
        private void moveTo(Instant next) { current = next; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
