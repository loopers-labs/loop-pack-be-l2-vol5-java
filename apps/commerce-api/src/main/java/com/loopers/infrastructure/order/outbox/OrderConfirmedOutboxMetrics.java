package com.loopers.infrastructure.order.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class OrderConfirmedOutboxMetrics {

    static final String SUMMARY_SQL = """
        SELECT COUNT(CASE WHEN status = 'PENDING' THEN 1 END),
               COUNT(CASE WHEN status = 'BLOCKED' THEN 1 END),
               MIN(CASE WHEN status = 'PENDING' THEN created_at END)
        FROM order_confirmed_outbox
        WHERE status IN ('PENDING', 'BLOCKED')
        """;

    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong pendingCount = new AtomicLong();
    private final AtomicLong blockedCount = new AtomicLong();
    private final AtomicLong oldestPendingCreatedAtMillis = new AtomicLong();

    public OrderConfirmedOutboxMetrics(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        Gauge.builder("order_confirmed_outbox.pending", pendingCount, AtomicLong::get)
            .description("Number of pending OrderConfirmed outbox rows")
            .register(meterRegistry);
        Gauge.builder("order_confirmed_outbox.blocked", blockedCount, AtomicLong::get)
            .description("Number of blocked OrderConfirmed outbox rows")
            .register(meterRegistry);
        Gauge.builder("order_confirmed_outbox.oldest_pending_age_seconds", this,
                OrderConfirmedOutboxMetrics::oldestPendingAgeSeconds)
            .description("Age of the oldest pending OrderConfirmed outbox row")
            .register(meterRegistry);
    }

    public void refresh() {
        Summary summary = jdbcTemplate.query(SUMMARY_SQL, resultSet -> {
            resultSet.next();
            LocalDateTime oldestPending = resultSet.getObject(3, LocalDateTime.class);
            return new Summary(resultSet.getLong(1), resultSet.getLong(2),
                oldestPending == null ? 0L : oldestPending.toInstant(ZoneOffset.UTC).toEpochMilli());
        });
        pendingCount.set(summary.pendingCount());
        blockedCount.set(summary.blockedCount());
        oldestPendingCreatedAtMillis.set(summary.oldestPendingCreatedAtMillis());
    }

    private double oldestPendingAgeSeconds() {
        long createdAtMillis = oldestPendingCreatedAtMillis.get();
        return createdAtMillis == 0L ? 0D : Math.max(0D, (System.currentTimeMillis() - createdAtMillis) / 1_000D);
    }

    private record Summary(long pendingCount, long blockedCount, long oldestPendingCreatedAtMillis) {}
}
