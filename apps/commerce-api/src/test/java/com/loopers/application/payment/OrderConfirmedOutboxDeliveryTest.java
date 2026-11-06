package com.loopers.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.application.order.OrderOutbox;
import com.loopers.application.order.OrderOutboxMessage;
import com.loopers.application.order.OutboxDeliveryStore;
import com.loopers.application.order.OutboxKafkaSender;
import com.loopers.application.payment.fixture.OrderPaymentFixture;
import com.loopers.infrastructure.order.OrderOutboxRepository;
import com.loopers.kafka.event.OrderConfirmed;
import com.loopers.support.payment.LocalPgServer;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class OrderConfirmedOutboxDeliveryTest {
    private static final LocalPgServer PG = LocalPgServer.start();
    private static final String TOPIC = "order-events-outbox-" + UUID.randomUUID();

    @Autowired private RequestOrderPaymentFacade requestPayment;
    @Autowired private ReconcileOrderPaymentFacade reconcilePayment;
    @Autowired private OrderPaymentFixture fixture;
    @Autowired private OrderOutboxRelay relay;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private OrderOutboxRepository repository;
    @Autowired private OrderOutbox outbox;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean(name = "outboxRelayClock") private Clock clock;
    @MockitoSpyBean private OutboxDeliveryStore store;
    @MockitoSpyBean private OutboxKafkaSender sender;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("payment-provider.base-url", PG::baseUrl);
        registry.add("order-events.topic", () -> TOPIC);
    }

    @BeforeEach
    void resetPg() {
        PG.reset();
    }

    @AfterEach
    void clean() {
        cleanUp.deleteAllEntities();
    }

    @AfterAll
    static void closePg() {
        PG.close();
    }

    @Test
    void 대기_이벤트_개수와_가장_오래된_생성_시각을_SQL로_확인한다() {
        String query = "select count(*) as pending_count, min(created_at) as oldest_pending"
                + " from order_outbox where status = 'PENDING'";
        var before = jdbc.queryForMap(query);
        assertThat(((Number) before.get("pending_count")).longValue()).isZero();
        assertThat(before.get("oldest_pending")).isNull();
        var oldestPending = java.sql.Timestamp.valueOf("2026-01-02 10:00:00");
        var laterPending = java.sql.Timestamp.valueOf("2026-01-03 10:00:00");
        var olderNonPending = java.sql.Timestamp.valueOf("2026-01-01 10:00:00");
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            outbox.record(new OrderOutboxMessage("pending-old", 101L, "OrderConfirmed", 1, "101", TOPIC, "{}"));
            outbox.record(new OrderOutboxMessage("pending-new", 102L, "OrderConfirmed", 1, "102", TOPIC, "{}"));
            outbox.record(new OrderOutboxMessage("blocked-old", 103L, "OrderConfirmed", 1, "103", TOPIC, "{}"));
            outbox.record(new OrderOutboxMessage("published-old", 104L, "OrderConfirmed", 1, "104", TOPIC, "{}"));
            jdbc.update("update order_outbox set created_at = ? where event_id = ?", oldestPending, "pending-old");
            jdbc.update("update order_outbox set created_at = ? where event_id = ?", laterPending, "pending-new");
            jdbc.update("update order_outbox set created_at = ?, status = 'BLOCKED' where event_id = ?",
                    olderNonPending, "blocked-old");
            jdbc.update("update order_outbox set created_at = ?, status = 'PUBLISHED' where event_id = ?",
                    olderNonPending, "published-old");
        });

        var backlog = jdbc.queryForMap(query);

        assertThat(((Number) backlog.get("pending_count")).longValue()).isEqualTo(2);
        assertThat(backlog.get("oldest_pending")).isEqualTo(oldestPending.toLocalDateTime());
    }

    @Test
    void 서로_다른_JSON_표현도_같은_사건이면_중복으로_인정한다() {
        var first = new OrderOutboxMessage("json-100", 100L, "OrderCanceled", 1,
                "100", TOPIC, "{\"orderId\":100,\"reason\":\"requested\"}");
        var same = new OrderOutboxMessage("json-100", 100L, "OrderCanceled", 1,
                "100", TOPIC, "{ \"reason\": \"requested\", \"orderId\": 100 }");
        var transaction = new TransactionTemplate(transactionManager);

        Boolean created = transaction.execute(status -> outbox.recordIfAbsent(first));
        Boolean duplicate = transaction.execute(status -> outbox.recordIfAbsent(same));

        assertThat(created).isTrue();
        assertThat(duplicate).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{} {}"})
    void 잘못된_JSON은_같은_트랜잭션의_outbox_기록까지_롤백한다(String invalidPayload) {
        var valid = new OrderOutboxMessage("valid-100", 100L, "OrderConfirmed", 1, "100", TOPIC, "{}");
        var invalid = new OrderOutboxMessage("invalid-100", 100L, "OrderCanceled", 1,
                "100", TOPIC, invalidPayload);
        var transaction = new TransactionTemplate(transactionManager);
        assertThat(jdbc.queryForObject("select count(*) from order_outbox", Long.class)).isZero();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            outbox.record(valid);
            outbox.record(invalid);
        })).isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class).hasMessageContaining("JSON");

        assertThat(jdbc.queryForObject("select count(*) from order_outbox", Long.class)).isZero();
    }

    @Test
    void 같은_eventId는_내용이_같을때만_복구를_중복으로_인정한다() {
        var original = new OrderOutboxMessage("cancel-100", 100L, "OrderCanceled", 1,
                "100", "canceled-orders", "{\"orderId\":100}");
        var differentTopic = new OrderOutboxMessage("cancel-100", 100L, "OrderCanceled", 1,
                "100", "other-orders", "{\"orderId\":100}");
        var transaction = new TransactionTemplate(transactionManager);

        Boolean created = transaction.execute(status -> outbox.recordIfAbsent(original));
        Boolean duplicated = transaction.execute(status -> outbox.recordIfAbsent(original));
        assertThat(created).isTrue();
        assertThat(duplicated).isFalse();
        assertThatThrownBy(() -> transaction.execute(status -> outbox.recordIfAbsent(differentTopic)))
                .hasMessageContaining("does not match");

        assertThat(jdbc.queryForObject("select count(*) from order_outbox where event_id = ?",
                Long.class, original.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select destination_topic from order_outbox where event_id = ?",
                String.class, original.eventId())).isEqualTo("canceled-orders");
    }

    @Test
    void 서로_다른_주문_이벤트를_저장된_각각의_토픽으로_발행한다() throws Exception {
        String confirmedTopic = "outbox-confirmed-" + UUID.randomUUID();
        String canceledTopic = "outbox-canceled-" + UUID.randomUUID();
        String confirmedPayload = "{\"eventType\":\"OrderConfirmed\",\"orderId\":100}";
        String canceledPayload = "{\"eventType\":\"OrderCanceled\",\"orderId\":100}";
        Instant now = Instant.now();
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            outbox.record(new OrderOutboxMessage("confirmed-100", 100L, "OrderConfirmed", 1,
                    "100", confirmedTopic, confirmedPayload));
            outbox.record(new OrderOutboxMessage("canceled-100", 100L, "OrderCanceled", 1,
                    "100", canceledTopic, canceledPayload));
        });
        when(clock.instant()).thenReturn(now.plusSeconds(1));

        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KafkaTestContainersConfig.bootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(confirmedTopic, 1, (short) 1),
                    new NewTopic(canceledTopic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            try {
                relay.runOnce();

                assertThat(readTopicPayload(confirmedTopic)).isEqualTo(confirmedPayload);
                assertThat(readTopicPayload(canceledTopic)).isEqualTo(canceledPayload);
                assertThat(jdbc.queryForObject("select count(*) from order_outbox where status = 'PUBLISHED'",
                        Long.class)).isEqualTo(2);
            } finally {
                admin.deleteTopics(List.of(confirmedTopic, canceledTopic)).all().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void 실제_broker_확인_뒤_상태_저장에_실패하면_같은_사건을_재발행한다() throws Exception {
        var order = fixture.createUnpaidOrder();
        var accepted = requestPayment.request(order.userId(), order.orderId(), fixture.createPaymentRequestCommand());
        PG.succeedPaymentForOrder(accepted.providerOrderId());
        reconcilePayment.reconcile(order.userId(), order.orderId());
        assertThat(status(order.orderId())).isEqualTo("PENDING");
        Instant firstRun = Instant.now().plusSeconds(10);
        when(clock.instant()).thenReturn(firstRun);
        doThrow(new IllegalStateException("stop before status update")).doCallRealMethod()
                .when(store).markPublished(anyString(), any(Instant.class));

        assertThatThrownBy(relay::runOnce).hasMessageContaining("stop before status update");
        assertThat(status(order.orderId())).isEqualTo("PENDING");
        when(clock.instant()).thenReturn(firstRun.plusSeconds(6));
        relay.runOnce();

        assertThat(status(order.orderId())).isEqualTo("PUBLISHED");
        var records = readRecords(order.orderId());
        assertThat(records).hasSize(2);
        assertThat(records.get(0).key()).isEqualTo(Long.toString(order.orderId()));
        assertThat(records.get(1).key()).isEqualTo(records.get(0).key());
        assertThat(records.get(1).value()).isEqualTo(records.get(0).value());
        assertThat(objectMapper.readValue(records.get(0).value(), OrderConfirmed.class).eventId())
                .isEqualTo("order-confirmed:v1:" + order.orderId());
        assertThat(records.get(1).offset()).isGreaterThan(records.get(0).offset());
    }

    @Test
    void 세번_전송_실패하면_blocked로_남고_명시적_재개까지_다시_전송하지_않는다() {
        var order = fixture.createUnpaidOrder();
        var accepted = requestPayment.request(order.userId(), order.orderId(), fixture.createPaymentRequestCommand());
        PG.succeedPaymentForOrder(accepted.providerOrderId());
        reconcilePayment.reconcile(order.userId(), order.orderId());
        assertThat(status(order.orderId())).isEqualTo("PENDING");
        Instant firstRun = Instant.now().plusSeconds(10);
        when(clock.instant()).thenReturn(firstRun);
        assertThatThrownBy(() -> relay.resume("order-confirmed:v1:" + order.orderId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Blocked");
        assertThat(jdbc.queryForObject("select attempt_count from order_outbox where order_id = ?",
                Integer.class, order.orderId())).isZero();
        doThrow(new IllegalStateException("broker unavailable")).when(sender).send(anyString(), anyString(), anyString());

        relay.runOnce();
        assertThat(status(order.orderId())).isEqualTo("PENDING");
        when(clock.instant()).thenReturn(firstRun.plusSeconds(10));
        relay.runOnce();
        assertThat(status(order.orderId())).isEqualTo("PENDING");
        when(clock.instant()).thenReturn(firstRun.plusSeconds(20));
        relay.runOnce();
        when(clock.instant()).thenReturn(firstRun.plusSeconds(100));
        relay.runOnce();

        var row = jdbc.queryForMap("select status, attempt_count, next_attempt_at from order_outbox"
                + " where order_id = ?", order.orderId());
        assertThat(row.get("status")).isEqualTo("BLOCKED");
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(3);
        assertThat(row.get("next_attempt_at")).isNull();

        org.mockito.Mockito.reset(sender);
        relay.resume("order-confirmed:v1:" + order.orderId());

        assertThat(status(order.orderId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select attempt_count from order_outbox where order_id = ?",
                Integer.class, order.orderId())).isEqualTo(3);
        verify(sender, never()).send(anyString(), anyString(), anyString());

        relay.runOnce();

        assertThat(status(order.orderId())).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("select attempt_count from order_outbox where order_id = ?",
                Integer.class, order.orderId())).isEqualTo(4);
        assertThatThrownBy(() -> relay.resume("order-confirmed:v1:" + order.orderId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Blocked");
    }

    @Test
    void 다른_사건이_발행중이어도_blocked_재개는_저장되고_다음_조회에서_발행된다() throws Exception {
        var first = new OrderOutboxMessage("active-101", 101L, "OrderConfirmed", 1, "101", TOPIC, "{}");
        var blocked = new OrderOutboxMessage("blocked-102", 102L, "OrderConfirmed", 1, "102", TOPIC, "{}");
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            outbox.record(first);
            outbox.record(blocked);
        });
        Instant now = Instant.now().plusSeconds(30);
        when(clock.instant()).thenReturn(now);
        for (int attempt = 1; attempt <= 3; attempt++) {
            store.beginAttempt(blocked.eventId(), now, now.plusSeconds(5)).orElseThrow();
            store.recordFailure(blocked.eventId(), attempt == 3 ? null : now, "TimeoutException");
        }
        assertThat(status(101L)).isEqualTo("PENDING");
        assertThat(status(102L)).isEqualTo("BLOCKED");

        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            if ("101".equals(invocation.getArgument(1))) {
                entered.countDown();
                if (!release.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("send was not released");
                }
            }
            return invocation.callRealMethod();
        }).when(sender).send(anyString(), anyString(), anyString());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var active = executor.submit(relay::runOnce);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            relay.resume(blocked.eventId());

            assertThat(status(102L)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("select attempt_count from order_outbox where event_id = ?",
                    Integer.class, blocked.eventId())).isEqualTo(3);
            verify(sender, times(1)).send(anyString(), eq("101"), anyString());
            verify(sender, never()).send(anyString(), eq("102"), anyString());

            release.countDown();
            active.get(15, TimeUnit.SECONDS);
            assertThat(status(102L)).isEqualTo("PENDING");
            relay.runOnce();

            assertThat(status(102L)).isEqualTo("PUBLISHED");
            assertThat(jdbc.queryForObject("select attempt_count from order_outbox where event_id = ?",
                    Integer.class, blocked.eventId())).isEqualTo(4);
            verify(sender, times(1)).send(anyString(), eq("102"), anyString());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void 세번째_시작_직후_중단되면_기한_이후_blocked로_격리한다() {
        var event = new OrderOutboxMessage("interrupted-100", 100L, "OrderConfirmed", 1,
                "100", TOPIC, "{\"orderId\":100}");
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> outbox.record(event));
        Instant attemptTime = Instant.now().plusSeconds(1);
        store.beginAttempt(event.eventId(), attemptTime, attemptTime.plusSeconds(5)).orElseThrow();
        store.recordFailure(event.eventId(), attemptTime, "TimeoutException");
        store.beginAttempt(event.eventId(), attemptTime, attemptTime.plusSeconds(5)).orElseThrow();
        store.recordFailure(event.eventId(), attemptTime, "TimeoutException");
        store.beginAttempt(event.eventId(), attemptTime, attemptTime.plusSeconds(5)).orElseThrow();
        assertThat(status(100L)).isEqualTo("PENDING");

        when(clock.instant()).thenReturn(attemptTime.plusSeconds(4));
        relay.runOnce();
        assertThat(status(100L)).isEqualTo("PENDING");

        when(clock.instant()).thenReturn(attemptTime.plusSeconds(6));
        relay.runOnce();

        assertThat(status(100L)).isEqualTo("BLOCKED");
        assertThat(jdbc.queryForObject("select last_error from order_outbox where event_id = ?",
                String.class, event.eventId())).isEqualTo("AttemptOutcomeUnknown");
        verify(sender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void 보존기간이_지난_발행완료만_정리하고_pending과_blocked는_남긴다() {
        var order = fixture.createUnpaidOrder();
        var accepted = requestPayment.request(order.userId(), order.orderId(), fixture.createPaymentRequestCommand());
        PG.succeedPaymentForOrder(accepted.providerOrderId());
        reconcilePayment.reconcile(order.userId(), order.orderId());
        Instant now = Instant.now().plusSeconds(10);
        when(clock.instant()).thenReturn(now);
        relay.runOnce();
        assertThat(status(order.orderId())).isEqualTo("PUBLISHED");
        jdbc.update("update order_outbox set published_at = ? where order_id = ?",
                java.sql.Timestamp.from(now.minus(Duration.ofDays(8))), order.orderId());
        jdbc.update("insert into order_outbox"
                        + " (event_id, order_id, event_type, schema_version, message_key, destination_topic, payload, status,"
                        + " attempt_count, cycle_attempt_count, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "old-pending", 999L, "OrderConfirmed", 1, "999", TOPIC, "{}", "PENDING", 3, 3,
                java.sql.Timestamp.from(now.minus(Duration.ofDays(8))));
        assertThat(status(999L)).isEqualTo("PENDING");
        jdbc.update("insert into order_outbox"
                        + " (event_id, order_id, event_type, schema_version, message_key, destination_topic, payload, status,"
                        + " attempt_count, cycle_attempt_count, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "old-blocked", 998L, "OrderConfirmed", 1, "998", TOPIC, "{}", "BLOCKED", 3, 3,
                java.sql.Timestamp.from(now.minus(Duration.ofDays(8))));
        assertThat(status(998L)).isEqualTo("BLOCKED");

        assertThat(repository.deletePublishedBefore(now.minus(Duration.ofDays(7)))).isEqualTo(1);

        assertThat(jdbc.queryForObject("select count(*) from order_outbox where event_id = 'old-pending'",
                Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from order_outbox where event_id = 'old-blocked'",
                Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from order_outbox where order_id = ?",
                Long.class, order.orderId())).isZero();
    }

    private String status(long orderId) {
        return jdbc.queryForObject("select status from order_outbox where order_id = ?",
                String.class, orderId);
    }

    private String readTopicPayload(String topic) {
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "outbox-route-inspect-" + UUID.randomUUID(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.assign(List.of(new TopicPartition(topic, 0)));
            consumer.seekToBeginning(consumer.assignment());
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(250));
                if (!records.isEmpty()) {
                    assertThat(records.count()).isEqualTo(1);
                    assertThat(records.iterator().next().key()).isEqualTo("100");
                    return records.iterator().next().value();
                }
            }
            throw new AssertionError("No record received from " + topic);
        }
    }

    private List<ConsumerRecord<String, byte[]>> readRecords(long orderId) {
        var properties = Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "outbox-inspect-" + UUID.randomUUID(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        try (var consumer = new KafkaConsumer<String, byte[]>(properties)) {
            var partitions = List.of(new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1));
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            var records = new ArrayList<ConsumerRecord<String, byte[]>>();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (records.size() < 2 && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(250)).forEach(record -> {
                    if (Long.toString(orderId).equals(record.key())) {
                        records.add(record);
                    }
                });
            }
            return records;
        }
    }
}
