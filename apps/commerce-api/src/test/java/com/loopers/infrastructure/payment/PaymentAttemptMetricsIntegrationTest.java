package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 미종결 결제 지표가 실제 MySQL의 결제 시도를 상태별로 세고, 상태 인덱스로 집계하는지 확인한다.
// 갱신 스케줄이 주기대로 지표를 바꾸는지는 사고 실험이 실제 Prometheus 응답으로 확인한다.
@SpringBootTest
class PaymentAttemptMetricsIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private NamedParameterJdbcTemplate namedJdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PaymentAttemptMetrics metrics;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("미종결 시도를 상태별로 세고, 종결된 시도와 논리 삭제된 시도는 세지 않으며, 상태 인덱스로 집계한다.")
    @Test
    void countsUnresolvedAttemptsByStatusUsingStatusIndex() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        int id = 0;
        for (int index = 0; index < 1_000; index++) {
            rows.add(row(++id, "SUCCEEDED", null));
        }
        rows.add(row(++id, "FAILED", null));
        rows.add(row(++id, "REQUESTED", null));
        rows.add(row(++id, "PENDING", null));
        rows.add(row(++id, "PENDING", null));
        rows.add(row(++id, "UNKNOWN", null));
        rows.add(row(++id, "UNKNOWN", null));
        rows.add(row(++id, "UNKNOWN", null));
        rows.add(row(++id, "UNKNOWN", LocalDateTime.now(ZoneOffset.UTC)));
        insert(rows);
        jdbcTemplate.execute("ANALYZE TABLE payment_attempts");

        metrics.refresh();

        assertThat(gauge("REQUESTED")).isEqualTo(1D);
        assertThat(gauge("PENDING")).isEqualTo(2D);
        assertThat(gauge("UNKNOWN")).isEqualTo(3D);
        String explain = namedJdbcTemplate.queryForObject("EXPLAIN FORMAT=JSON " + PaymentAttemptMetrics.SUMMARY_SQL,
            PaymentAttemptMetrics.SUMMARY_PARAMETERS, String.class);
        JsonNode plan = objectMapper.readTree(explain);
        assertThat(plan.findValues("key")).as("MySQL EXPLAIN: %s", explain)
            .extracting(JsonNode::asText).contains("idx_payment_attempts_status_checked");
    }

    @DisplayName("주기 조회가 풀었어야 할 UNKNOWN만 센다. 충돌, 주기 조회 기간 밖, 기한 전, 논리 삭제, 다른 상태는 빼고, 상태 인덱스로 집계한다.")
    @Test
    void countsOnlyOverdueUnknownAttempts() throws Exception {
        LocalDateTime now = jdbcTemplate.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
        LocalDateTime recent = now.minusHours(1);
        insertAttempt(1, "UNKNOWN", recent, now.minusMinutes(10), null, null);
        insertAttempt(2, "UNKNOWN", recent, now.minusMinutes(5).minusSeconds(30), null, null);
        insertAttempt(3, "UNKNOWN", recent, now.minusMinutes(4).minusSeconds(30), null, null);
        insertAttempt(4, "UNKNOWN", recent, now.minusMinutes(10), "MULTIPLE_TRANSACTIONS", null);
        insertAttempt(5, "UNKNOWN", now.minusHours(25), now.minusMinutes(10), null, null);
        insertAttempt(6, "UNKNOWN", recent, now.minusMinutes(10), null, now);
        insertAttempt(7, "PENDING", recent, now.minusMinutes(10), null, null);

        metrics.refresh();

        assertThat(overdueGauge()).isEqualTo(2D);
        String explain = namedJdbcTemplate.queryForObject("EXPLAIN FORMAT=JSON " + PaymentAttemptMetrics.OVERDUE_SQL,
            metrics.overdueParameters(), String.class);
        assertThat(objectMapper.readTree(explain).findValues("key")).as("MySQL EXPLAIN: %s", explain)
            .extracting(JsonNode::asText).contains("idx_payment_attempts_status_checked");
    }

    @DisplayName("미종결 시도가 종결되면 그 상태와 기한 초과 지표는 0으로 돌아간다.")
    @Test
    void resetsResolvedStatusesToZero() {
        LocalDateTime now = jdbcTemplate.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
        insertAttempt(1, "UNKNOWN", now.minusHours(1), now.minusMinutes(10), null, null);
        metrics.refresh();
        assertThat(gauge("UNKNOWN")).isEqualTo(1D);
        assertThat(overdueGauge()).isEqualTo(1D);

        jdbcTemplate.update("UPDATE payment_attempts SET status = 'SUCCEEDED'");
        metrics.refresh();

        assertThat(gauge("REQUESTED")).isZero();
        assertThat(gauge("PENDING")).isZero();
        assertThat(gauge("UNKNOWN")).isZero();
        assertThat(overdueGauge()).isZero();
    }

    @DisplayName("두 집계 중 하나라도 실패하면 갱신 스케줄은 예외를 밖으로 던지지 않고, 두 지표 모두 직전에 집계한 값을 유지한다.")
    @Test
    void keepsLastValuesWhenRefreshFails() {
        NamedParameterJdbcTemplate failing = mock(NamedParameterJdbcTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PaymentAttemptMetrics isolated = new PaymentAttemptMetrics(failing, registry, true, Duration.ofMinutes(5), Duration.ofHours(24));
        when(failing.query(eq(PaymentAttemptMetrics.SUMMARY_SQL), eq(PaymentAttemptMetrics.SUMMARY_PARAMETERS),
            any(RowMapper.class)))
            .thenReturn(List.of(new PaymentAttemptMetrics.StatusCount(PaymentAttemptStatus.UNKNOWN, 3L)))
            .thenThrow(new DataAccessResourceFailureException("DB down"))
            .thenReturn(List.of(new PaymentAttemptMetrics.StatusCount(PaymentAttemptStatus.UNKNOWN, 5L)));
        when(failing.queryForObject(eq(PaymentAttemptMetrics.OVERDUE_SQL), anyMap(), eq(Long.class)))
            .thenReturn(2L)
            .thenThrow(new DataAccessResourceFailureException("DB down"));
        isolated.scheduledRefresh();

        // 상태별 집계가 실패한다.
        assertThatCode(isolated::scheduledRefresh).doesNotThrowAnyException();
        assertKept(registry);

        // 상태별 집계는 새 값을 냈지만 기한 초과 집계가 실패한다.
        assertThatCode(isolated::scheduledRefresh).doesNotThrowAnyException();
        assertKept(registry);
    }

    private static void assertKept(MeterRegistry registry) {
        assertThat(registry.get("payment_attempt.unresolved").tag("status", "UNKNOWN").gauge().value()).isEqualTo(3D);
        assertThat(registry.get("payment_attempt.overdue_unknown").gauge().value()).isEqualTo(2D);
    }

    private Object[] row(int id, String status, LocalDateTime deletedAt) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return new Object[]{id, 7_000L, now, now, deletedAt, "00000000-0000-0000-0000-%012d".formatted(id),
            "LP-ORD-%010d-7f3a9c1e".formatted(id), status};
    }

    private void insert(List<Object[]> rows) {
        jdbcTemplate.batchUpdate("""
            INSERT INTO payment_attempts
              (order_id, amount, created_at, updated_at, deleted_at, payment_attempt_key, provider_order_id, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, rows);
    }

    private void insertAttempt(int id, String status, LocalDateTime createdAt, LocalDateTime updatedAt,
                               String conflictReason, LocalDateTime deletedAt) {
        jdbcTemplate.update("""
            INSERT INTO payment_attempts
              (order_id, amount, created_at, updated_at, deleted_at, payment_attempt_key, provider_order_id, status, conflict_reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, 7_000L, createdAt, updatedAt, deletedAt, "00000000-0000-0000-0000-%012d".formatted(id),
            "LP-ORD-%010d-7f3a9c1e".formatted(id), status, conflictReason);
    }

    private double overdueGauge() {
        return meterRegistry.get("payment_attempt.overdue_unknown").gauge().value();
    }

    private double gauge(String status) {
        return meterRegistry.get("payment_attempt.unresolved").tag("status", status).gauge().value();
    }
}
