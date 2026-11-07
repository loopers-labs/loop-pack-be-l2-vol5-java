package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderEventRelay;
import com.loopers.application.order.OrderEventResume;
import com.loopers.domain.order.OrderEventOutbox;
import com.loopers.domain.order.OrderRepository;
import com.loopers.support.kafka.TopicRecords;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "kafka.topics.order-events=" + OrderEventResumeTest.TOPIC,
    "outbox.resume.event-ids=" + OrderEventResumeTest.BOOT_TARGET
})
class OrderEventResumeTest {

    static final String TOPIC = "order-events-relay-resume";
    static final String BOOT_TARGET = "order-confirmed:v1:404";
    private static final String LIMIT = "max.message.bytes";

    private final OrderConfirmations orders;
    private final OrderEventRelay relay;
    private final OrderEventResume resume;
    private final OrderEventResume.Runner runner;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    OrderEventResumeTest(OrderRepository orderRepository, TransactionTemplate transactionTemplate,
                         OrderEventOutbox outbox, OrderEventRelay relay, OrderEventResume resume,
                         OrderEventResume.Runner runner, JdbcTemplate jdbcTemplate,
                         DatabaseCleanUp databaseCleanUp) {
        this.orders = new OrderConfirmations(orderRepository, transactionTemplate, outbox);
        this.relay = relay;
        this.resume = resume;
        this.runner = runner;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeAll
    static void createRejectingTopic() {
        KafkaTestContainersConfig.createTopic(TOPIC, 1, Map.of(LIMIT, "1"));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Long blockedOrder() {
        Long orderId = orders.pendingOrderId();
        orders.commit(orderId);
        relay.relayOnce(10);
        assertThat(row(orderId).get("status")).isEqualTo("BLOCKED");
        return orderId;
    }

    private Map<String, Object> row(Long orderId) {
        return jdbcTemplate.queryForMap("""
            select event_id, status, attempt_count, last_error, payload, published_at,
                   next_attempt_at <= utc_timestamp(6) due
            from order_event_outbox where order_id = ?
            """, orderId);
    }

    private static int attempts(Map<String, Object> row) {
        return ((Number) row.get("attempt_count")).intValue();
    }

    @Test
    @DisplayName("원인을 고친 뒤 BLOCKED 를 재개하면 같은 행이 시도 수 · 원인을 지닌 채 PENDING 이 되고, relay 가 같은 key · payload 로 한 번 보내 PUBLISHED 로 바꾼다")
    void resumedRowIsSentByRelay() {
        Long orderId = blockedOrder();
        Map<String, Object> blocked = row(orderId);
        String eventId = (String) blocked.get("event_id");
        KafkaTestContainersConfig.deleteTopicConfig(TOPIC, LIMIT);

        Map<String, OrderEventResume.Result> results = resume.resume(List.of(eventId));

        Map<String, Object> resumed = row(orderId);
        assertThat(results).containsExactly(Map.entry(eventId, OrderEventResume.Result.RESUMED));
        assertThat(resumed.get("status")).isEqualTo("PENDING");
        assertThat(attempts(resumed)).isEqualTo(attempts(blocked));
        assertThat(resumed.get("last_error")).isEqualTo(blocked.get("last_error"));
        assertThat(((Number) resumed.get("due")).intValue()).isEqualTo(1);

        List<ConsumerRecord<String, byte[]>> records = TopicRecords.during(TOPIC, () -> relay.relayOnce(10));

        Map<String, Object> published = row(orderId);
        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.key()).isEqualTo(String.valueOf(orderId));
            assertThat(record.value()).isEqualTo((byte[]) blocked.get("payload"));
        });
        assertThat(published.get("status")).isEqualTo("PUBLISHED");
        assertThat(published.get("published_at")).isNotNull();
        assertThat(attempts(published)).isEqualTo(attempts(blocked) + 1);
    }

    @Test
    @DisplayName("BLOCKED 가 아닌 eventId 는 바꾸지 않고 NOT_BLOCKED 로 답한다")
    void leavesNonBlockedRowsAlone() {
        Long pendingOrder = orders.pendingOrderId();
        orders.commit(pendingOrder);
        insertRow("order-confirmed:v1:405", 405L, "PUBLISHED");
        Map<String, Object> pending = stateOf(pendingOrder);
        Map<String, Object> published = stateOf(405L);
        String pendingEventId = (String) pending.get("event_id");

        Map<String, OrderEventResume.Result> results =
            resume.resume(List.of(pendingEventId, "order-confirmed:v1:405", "order-confirmed:v1:999999"));

        assertThat(results).containsExactly(
            Map.entry(pendingEventId, OrderEventResume.Result.NOT_BLOCKED),
            Map.entry("order-confirmed:v1:405", OrderEventResume.Result.NOT_BLOCKED),
            Map.entry("order-confirmed:v1:999999", OrderEventResume.Result.NOT_BLOCKED));
        assertThat(stateOf(pendingOrder)).usingRecursiveComparison().isEqualTo(pending);
        assertThat(stateOf(405L)).usingRecursiveComparison().isEqualTo(published);
    }

    private Map<String, Object> stateOf(Long orderId) {
        return jdbcTemplate.queryForMap("select * from order_event_outbox where order_id = ?", orderId);
    }

    private void insertRow(String eventId, Long orderId, String status) {
        jdbcTemplate.update("""
            insert into order_event_outbox
              (event_id, order_id, event_type, schema_version, topic, record_key, payload,
               status, attempt_count, last_error, created_at, updated_at)
            values (?, ?, 'OrderConfirmed', 1, ?, ?, x'7B7D', ?, 1,
                    'RecordTooLargeException: too large', now(6), now(6))
            """, eventId, orderId, TOPIC, String.valueOf(orderId), status);
    }

    @Test
    @DisplayName("기동 인자의 eventId 가 BLOCKED 가 아니어도 앱은 뜨고, 그 eventId 가 BLOCKED 이면 Runner 가 PENDING 으로 되돌린다")
    void runnerResumesEventIdsFromArguments() {
        insertRow(BOOT_TARGET, 404L, "BLOCKED");

        runner.run(new DefaultApplicationArguments());

        Map<String, Object> resumed = row(404L);
        assertThat(resumed.get("status")).isEqualTo("PENDING");
        assertThat(attempts(resumed)).isEqualTo(1);
    }
}
