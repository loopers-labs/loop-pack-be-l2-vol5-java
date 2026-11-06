package com.loopers.infrastructure.order.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderConfirmedOutboxMetricsIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmedOutboxMetricsIntegrationTest.class);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrderConfirmedOutboxMetrics metrics;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    void aggregatesUnresolvedRowsUsingStatusIndexWhenPublishedHistoryGrows() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (int id = 1; id <= 2_000; id++) {
            rows.add(row(id, "PUBLISHED", now.minusDays(1)));
        }
        rows.add(row(2_001, "PENDING", now.minusSeconds(90)));
        rows.add(row(2_002, "PENDING", now.minusSeconds(30)));
        rows.add(row(2_003, "BLOCKED", now));
        insertRows(rows);
        jdbcTemplate.execute("ANALYZE TABLE order_confirmed_outbox");

        metrics.refresh();

        assertThat(gauge("pending")).isEqualTo(2D);
        assertThat(gauge("blocked")).isEqualTo(1D);
        assertThat(gauge("oldest_pending_age_seconds")).isBetween(89D, 120D);
        String explain = jdbcTemplate.queryForObject("EXPLAIN FORMAT=JSON "
            + OrderConfirmedOutboxMetrics.SUMMARY_SQL, String.class);
        JsonNode plan = objectMapper.readTree(explain);
        log.info("OUTBOX_METRICS_PLAN publishedRows=2000 unresolvedRows=3 plan={}", explain);
        assertThat(plan.findValues("access_type")).as("MySQL EXPLAIN: %s", explain)
            .extracting(JsonNode::asText).contains("range");
        assertThat(plan.findValues("key")).as("MySQL EXPLAIN: %s", explain)
            .extracting(JsonNode::asText).contains("idx_order_confirmed_outbox_due");
    }

    @Test
    void reportsZeroWhenOnlyPublishedHistoryRemains() {
        insertRows(List.<Object[]>of(row(1, "PUBLISHED", LocalDateTime.now(ZoneOffset.UTC))));

        metrics.refresh();

        assertThat(gauge("pending")).isZero();
        assertThat(gauge("blocked")).isZero();
        assertThat(gauge("oldest_pending_age_seconds")).isZero();
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
