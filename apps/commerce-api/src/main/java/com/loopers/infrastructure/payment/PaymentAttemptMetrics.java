package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttemptStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// 미종결 결제 시도 수를 상태별로 DB 전체에서 센다. 미종결 상태는 도메인의 종결 정의에서 가져와, 상태가 늘면 지표도 따라간다.
// 주기 조회가 풀었어야 할 미확인 수도 함께 센다. 경보는 이 값으로 건다.
// 값은 갱신 스케줄이 집계해 메모리에 두므로 scrape 때 DB를 조회하지 않는다.
// 인스턴스마다 같은 DB를 세므로 여러 인스턴스의 값은 더하지 않고 최댓값으로 본다.
@Slf4j
@Component
public class PaymentAttemptMetrics {

    static final List<PaymentAttemptStatus> UNRESOLVED_STATUSES = Arrays.stream(PaymentAttemptStatus.values())
        .filter(status -> !status.isTerminal())
        .toList();

    static final String SUMMARY_SQL = """
        SELECT status, COUNT(*) AS attempts
        FROM payment_attempts
        WHERE status IN (:statuses)
          AND deleted_at IS NULL
        GROUP BY status
        """;

    static final Map<String, Object> SUMMARY_PARAMETERS =
        Map.of("statuses", UNRESOLVED_STATUSES.stream().map(Enum::name).toList());

    // 충돌이 기록된 시도는 주기 조회도 런북도 풀지 못하고, 주기 조회 기간이 지난 시도는 그 전에 이미 세었으므로 뺀다.
    // 런북 SQL과 같은 기준으로 맞추려고 DB의 현재 시각으로 잰다.
    static final String OVERDUE_SQL = """
        SELECT COUNT(*)
        FROM payment_attempts
        WHERE status = :status
          AND deleted_at IS NULL
          AND conflict_reason IS NULL
          AND created_at >= UTC_TIMESTAMP(6) - INTERVAL :lookupWindowSeconds SECOND
          AND updated_at <= UTC_TIMESTAMP(6) - INTERVAL :overdueAfterSeconds SECOND
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final boolean enabled;
    private final Map<String, Object> overdueParameters;
    private final Map<PaymentAttemptStatus, AtomicLong> counts = new LinkedHashMap<>();
    private final AtomicLong overdueCount = new AtomicLong();

    public PaymentAttemptMetrics(NamedParameterJdbcTemplate jdbcTemplate, MeterRegistry meterRegistry,
                                 @Value("${payment.attempt-metrics.enabled}") boolean enabled,
                                 @Value("${payment.attempt-metrics.overdue-after}") Duration overdueAfter,
                                 @Value("${payment.periodic-check.lookup-window}") Duration lookupWindow) {
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
        this.overdueParameters = Map.of("status", PaymentAttemptStatus.UNKNOWN.name(),
            "lookupWindowSeconds", lookupWindow.toSeconds(), "overdueAfterSeconds", overdueAfter.toSeconds());
        for (PaymentAttemptStatus status : UNRESOLVED_STATUSES) {
            AtomicLong count = new AtomicLong();
            counts.put(status, count);
            Gauge.builder("payment_attempt.unresolved", count, AtomicLong::get)
                .description("Unresolved payment attempts by status across the whole DB. "
                    + "Refreshed periodically, so changes appear after the refresh interval plus the scrape interval.")
                .tag("status", status.name())
                .register(meterRegistry);
        }
        Gauge.builder("payment_attempt.overdue_unknown", overdueCount, AtomicLong::get)
            .description("UNKNOWN payment attempts the periodic check should have resolved: no conflict, "
                + "created within the lookup window, unchanged longer than the overdue threshold. Measured with the DB clock.")
            .register(meterRegistry);
    }

    // 고정 간격으로 갱신한다. 기동 직후 한 번 갱신해 첫 주기 동안 실제 값이 0으로 보이지 않게 한다.
    // 설정으로 끄면 아무것도 하지 않는다. 집계가 실패하면 경고를 남기고 직전 값을 유지한다.
    @Scheduled(fixedDelayString = "${payment.attempt-metrics.interval}", initialDelay = 0)
    public void scheduledRefresh() {
        if (!enabled) {
            return;
        }
        try {
            refresh();
        } catch (RuntimeException failure) {
            log.warn("미종결 결제 시도 지표 갱신에 실패해 직전 값을 유지합니다.", failure);
        }
    }

    // 두 집계가 모두 끝난 뒤에만 값을 바꾼다. 하나라도 실패하면 예외를 던지고 직전 값을 그대로 둔다. 결과에 없는 상태는 0이다.
    void refresh() {
        Map<PaymentAttemptStatus, Long> fresh = jdbcTemplate.query(SUMMARY_SQL, SUMMARY_PARAMETERS,
                (row, rowNumber) -> new StatusCount(PaymentAttemptStatus.valueOf(row.getString("status")), row.getLong("attempts")))
            .stream()
            .collect(Collectors.toMap(StatusCount::status, StatusCount::attempts));
        long overdue = Objects.requireNonNullElse(jdbcTemplate.queryForObject(OVERDUE_SQL, overdueParameters, Long.class), 0L);
        counts.forEach((status, count) -> count.set(fresh.getOrDefault(status, 0L)));
        overdueCount.set(overdue);
    }

    Map<String, Object> overdueParameters() {
        return overdueParameters;
    }

    record StatusCount(PaymentAttemptStatus status, long attempts) {
    }
}
