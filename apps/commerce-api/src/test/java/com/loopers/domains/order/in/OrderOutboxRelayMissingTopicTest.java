package com.loopers.domains.order.in;

import com.loopers.domains.order.app.OrderOutboxRelay;
import com.loopers.domains.order.app.port.OrderConfirmedPayload;
import com.loopers.domains.order.out.OrderConfirmedMessageSerializer;
import com.loopers.support.pg.MutablePaymentClock;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

// R11: 없는 topic은 모든 행에 해당하는 broker 수준 실패다(relay-1 ①). 공유 테스트 broker는 topic 자동 생성이 켜져 있어
// 없는 topic으로 보내도 실패하지 않으므로, 이 클래스만 자동 생성을 끈 전용 broker를 띄우고 relay 대상 topic을 만들지 않는다.
// producer는 metadata를 max.block(2초)까지 기다리다 TimeoutException을 failed future로 돌려준다(kafka-clients 3.8.1). 운영에서 topic이 지워진 상황 그 자체는 증명하지 못한다.
@SpringBootTest
@Import(PaymentLookupScheduleIntegrationTest.TimeConfig.class)
class OrderOutboxRelayMissingTopicTest {
    private static final Instant NOW = Instant.parse("2026-10-29T01:00:00Z");
    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"))
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    static {
        KAFKA.start();
    }

    @Autowired private OrderOutboxRelay relay;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutablePaymentClock clock;
    @Autowired private DatabaseCleanUp cleanup;

    @DynamicPropertySource
    static void missingTopic(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.admin.auto-create", () -> false);
        registry.add("commerce.kafka.topics.order-events", () -> "order-events-missing-" + UUID.randomUUID());
    }

    @BeforeEach
    void setClock() {
        clock.set(NOW);
    }

    @AfterEach
    void clean() {
        cleanup.truncateAllTables();
    }

    // 회차마다 그 회차 시작 시각에 차례가 된 행 중 가장 이른 행(next_attempt_at, id 순) 하나만 시도하고 멈춘다(broker 수준).
    // 실패한 행은 뒤로 밀리므로 어느 행이 시도될지는 회차마다 DB 값으로 계산해 고정한다.
    @Test
    @DisplayName("relay 대상 topic이 broker에 없는 채로 회차가 이어지면 회차마다 가장 이른 행 하나만 시도해 원인을 남기고 멈추며 두 행 모두 BLOCKED 없이 PENDING·행 탓 실패 0이다")
    void missingTopicIsBrokerLevelFailure() {
        long first = insertPending(1, NOW.minusSeconds(1));
        long second = insertPending(2, NOW);

        for (int round = 1; round <= 3; round++) {
            Map<Long, Map<String, Object>> before = Map.of(first, row(first), second, row(second));
            long attempted = earliestDue(before, clock.instant());
            long other = attempted == first ? second : first;

            relay.runOnce();

            Map<String, Object> attemptedRow = row(attempted);
            Map<String, Object> otherRow = row(other);
            assertThat(attempts(attemptedRow) + attempts(otherRow)).isEqualTo(round);
            assertThat(attempts(attemptedRow)).isEqualTo(attempts(before.get(attempted)) + 1);
            assertThat(otherRow).isEqualTo(before.get(other));
            for (Map<String, Object> row : List.of(attemptedRow, otherRow)) {
                assertThat(row).containsEntry("status", "PENDING").containsEntry("row_failure_count", 0);
            }
            assertThat((String) attemptedRow.get("last_error")).contains("TimeoutException").contains("not present in metadata");
            Instant nextDue = List.of(attemptedRow, otherRow).stream().map(row -> instant(row.get("next_attempt_at")))
                .min(Comparator.naturalOrder()).orElseThrow();
            if (nextDue.isAfter(clock.instant())) {
                clock.set(nextDue);
            }
        }
    }

    private static long earliestDue(Map<Long, Map<String, Object>> rows, Instant now) {
        return rows.entrySet().stream()
            .filter(entry -> !instant(entry.getValue().get("next_attempt_at")).isAfter(now))
            .min(Comparator.<Map.Entry<Long, Map<String, Object>>, Instant>comparing(entry -> instant(entry.getValue().get("next_attempt_at")))
                .thenComparing(Map.Entry::getKey))
            .orElseThrow()
            .getKey();
    }

    private static int attempts(Map<String, Object> row) {
        return (Integer) row.get("attempt_count");
    }

    private static Instant instant(Object value) {
        return ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
    }

    private long insertPending(long orderId, Instant nextAttemptAt) {
        OrderConfirmedPayload payload = new OrderConfirmedPayload(String.valueOf(orderId), "order-confirmed:v1:" + orderId,
            orderId, NOW, List.of(new OrderConfirmedPayload.Item(1, 1)));
        jdbc.update("insert into order_outbox(event_id, order_id, event_type, schema_version, message_key, payload, status,"
                + " attempt_count, row_failure_count, next_attempt_at, created_at) values (?, ?, 'OrderConfirmed', 1, ?, ?,"
                + " 'PENDING', 0, 0, ?, ?)",
            payload.eventId(), orderId, payload.key(), OrderConfirmedMessageSerializer.serialize(payload),
            LocalDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC), LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return jdbc.queryForObject("select id from order_outbox where order_id = ?", Long.class, orderId);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("select status, attempt_count, row_failure_count, last_error, next_attempt_at from order_outbox"
            + " where id = ?", id);
    }
}
