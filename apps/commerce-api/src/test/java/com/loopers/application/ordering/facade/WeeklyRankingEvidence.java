package com.loopers.application.ordering.facade;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

record WeeklyRankingEvidence(UUID snapshotId, LocalDate periodStart, LocalDate periodEnd, int revision, String state,
                             Instant frozenAt, long inputCount, List<Input> inputs, Job job, List<Step> steps) {
    record Input(long orderId, long productId, int quantity, Instant confirmedAt) {}
    record Job(long executionId, String name, String status, String exitCode, LocalDate periodStart, int revision) {}
    record Step(String name, String status, String exitCode) {}

    static WeeklyRankingEvidence read(JdbcTemplate db, UUID snapshotId) {
        String id = snapshotId.toString();
        List<Input> inputs = db.query("""
            SELECT order_id, product_id, quantity, confirmed_at
              FROM weekly_ranking_snapshot_inputs
             WHERE snapshot_id = ?
             ORDER BY order_id, product_id
            """, (row, index) -> new Input(row.getLong("order_id"), row.getLong("product_id"), row.getInt("quantity"),
                row.getObject("confirmed_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)), id);
        Job job = db.queryForObject("""
            SELECT j.JOB_EXECUTION_ID, i.JOB_NAME, j.STATUS, j.EXIT_CODE, w.PARAMETER_VALUE AS period_start, r.PARAMETER_VALUE AS revision
              FROM BATCH_JOB_EXECUTION j
              JOIN BATCH_JOB_INSTANCE i ON i.JOB_INSTANCE_ID = j.JOB_INSTANCE_ID
              JOIN BATCH_JOB_EXECUTION_PARAMS s ON s.JOB_EXECUTION_ID = j.JOB_EXECUTION_ID
                  AND s.PARAMETER_NAME = 'snapshotId' AND s.IDENTIFYING = 'Y'
              JOIN BATCH_JOB_EXECUTION_PARAMS w ON w.JOB_EXECUTION_ID = j.JOB_EXECUTION_ID
                  AND w.PARAMETER_NAME = 'periodStart' AND w.IDENTIFYING = 'Y'
              JOIN BATCH_JOB_EXECUTION_PARAMS r ON r.JOB_EXECUTION_ID = j.JOB_EXECUTION_ID
                  AND r.PARAMETER_NAME = 'revision' AND r.IDENTIFYING = 'Y'
             WHERE i.JOB_NAME = 'weeklyRanking' AND s.PARAMETER_VALUE = ?
            """, (row, index) -> new Job(row.getLong("JOB_EXECUTION_ID"), row.getString("JOB_NAME"),
                row.getString("STATUS"), row.getString("EXIT_CODE"), LocalDate.parse(row.getString("period_start")), row.getInt("revision")), id);
        List<Step> steps = db.query("""
            SELECT STEP_NAME, STATUS, EXIT_CODE
              FROM BATCH_STEP_EXECUTION
             WHERE JOB_EXECUTION_ID = ?
             ORDER BY STEP_EXECUTION_ID
            """, (row, index) -> new Step(row.getString("STEP_NAME"), row.getString("STATUS"), row.getString("EXIT_CODE")), job.executionId());
        return db.queryForObject("""
            SELECT period_start, period_end, revision, state, frozen_at, input_count
              FROM weekly_ranking_snapshots WHERE snapshot_id = ?
            """, (row, index) -> {
                var frozen = row.getObject("frozen_at", LocalDateTime.class);
                return new WeeklyRankingEvidence(snapshotId, row.getObject("period_start", LocalDate.class),
                    row.getObject("period_end", LocalDate.class), row.getInt("revision"), row.getString("state"),
                    frozen == null ? null : frozen.toInstant(ZoneOffset.UTC), row.getLong("input_count"), inputs, job, steps);
            }, id);
    }

    void assertCompleted(LocalDate expectedWeek, List<PurchaseActivityEvidence.Activity> activities) {
        assertThat(periodStart).isEqualTo(expectedWeek);
        assertThat(periodEnd).isEqualTo(expectedWeek.plusWeeks(1));
        assertThat(revision).isPositive();
        assertThat(state).isEqualTo("FROZEN");
        assertThat(frozenAt).isNotNull();
        assertThat(inputCount).isEqualTo(20);
        assertThat(inputs).hasSize(20).containsExactlyInAnyOrderElementsOf(activities.stream()
            .map(row -> new Input(row.orderId(), row.productId(), row.quantity(), row.confirmedAt())).toList());
        assertThat(job.name()).isEqualTo("weeklyRanking");
        assertThat(job.periodStart()).isEqualTo(periodStart);
        assertThat(job.revision()).isEqualTo(revision);
        assertThat(job.status()).isEqualTo("COMPLETED");
        assertThat(job.exitCode()).isEqualTo("COMPLETED");
        assertThat(steps).containsExactly(new Step("freezeWeeklyRankingInput", "COMPLETED", "COMPLETED"),
            new Step("calculateWeeklyScores", "COMPLETED", "COMPLETED"), new Step("publishWeeklyRanking", "COMPLETED", "COMPLETED"));
    }
}
