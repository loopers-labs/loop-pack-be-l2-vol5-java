package com.loopers.infrastructure.order.outbox;

import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 갱신 스케줄이 다른 테스트 동안 계속 돌지 않도록 클래스가 끝나면 컨텍스트를 닫는다.
@SpringBootTest(properties = {
    "order.confirmed-event.outbox.metrics.enabled=true",
    "order.confirmed-event.outbox.metrics.interval=100ms"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderConfirmedOutboxMetricsSchedulerIntegrationTest {

    private static final Duration DEADLINE = Duration.ofSeconds(5);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private DatabaseCleanUp databaseCleanUp;
    @Value("${order.confirmed-event.relay.enabled}") private boolean relayEnabled;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("Relay가 꺼져 있어도 지표 갱신 스케줄이 미해결 Outbox 수와 가장 오래된 대기 시간을 반영한다.")
    @Test
    void refreshesUnresolvedOutboxMetricsWithoutRelay() throws InterruptedException {
        assertThat(relayEnabled).isFalse();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        insertRows(List.of(
            row(1, "PENDING", now.minusSeconds(90)),
            row(2, "PENDING", now.minusSeconds(30)),
            row(3, "BLOCKED", now)
        ));

        awaitGauge("pending", 2D);

        assertThat(gauge("blocked")).isEqualTo(1D);
        assertThat(gauge("oldest_pending_age_seconds")).isBetween(89D, 120D);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM order_confirmed_outbox WHERE status = 'PENDING' AND attempt_count = 0",
            Long.class)).isEqualTo(2L);
    }

    private void awaitGauge(String name, double expected) throws InterruptedException {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        while (gauge(name) != expected && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(gauge(name)).isEqualTo(expected);
    }

    private Object[] row(int id, String status, LocalDateTime createdAt) {
        return new Object[]{"order-confirmed:v1:" + id, id, Integer.toString(id), status,
            createdAt, createdAt, createdAt};
    }

    private void insertRows(List<Object[]> rows) {
        jdbcTemplate.batchUpdate("""
            INSERT INTO order_confirmed_outbox
              (event_id, order_id, event_type, schema_version, message_key, payload, status,
               attempt_count, cycle_attempt_count, next_attempt_at, created_at, updated_at)
            VALUES (?, ?, 'OrderConfirmed', 1, ?, '{}', ?, 0, 0, ?, ?, ?)
            """, rows);
    }

    private double gauge(String name) {
        return meterRegistry.get("order_confirmed_outbox." + name).gauge().value();
    }
}
