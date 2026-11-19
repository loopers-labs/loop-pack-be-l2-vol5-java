package com.loopers.batch.job.ranking.coordination;

import com.loopers.application.ranking.command.WeeklyRankingOrderPartitionCommand;
import com.loopers.domain.ranking.model.vo.RankingPeriod;
import com.loopers.infrastructure.persistence.ranking.JdbcWeeklyRankingOrderPartitionInputs;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.jdbc.core.JdbcTemplate;

final class OrderPartitionRestartEvidence {
    private final JdbcTemplate jdbc;
    private final JobRepository jobs;
    private final JdbcWeeklyRankingOrderPartitionInputs inputs;

    OrderPartitionRestartEvidence(JdbcTemplate jdbc, JobRepository jobs, JdbcWeeklyRankingOrderPartitionInputs inputs) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.inputs = inputs;
    }

    boolean matches(WeeklyPlanStore.Claim claim, JobExecution execution) {
        String snapshot = claim.snapshot().toString();
        if (execution == null) {
            return jdbc.queryForObject("""
                SELECT s.frozen_at IS NULL
                    AND NOT EXISTS(SELECT 1 FROM weekly_ranking_partial_scores v WHERE v.snapshot_id=s.snapshot_id AND v.order_partition_id=s.order_partition_id)
                    AND NOT EXISTS(SELECT 1 FROM weekly_ranking_score_conflicts c WHERE c.snapshot_id=s.snapshot_id AND c.order_partition_id=s.order_partition_id)
                    AND NOT EXISTS(SELECT 1 FROM weekly_ranking_snapshot_inputs i WHERE NOT s.is_empty AND i.snapshot_id=s.snapshot_id
                        AND (s.order_min IS NULL OR i.order_id>=s.order_min) AND (s.order_max IS NULL OR i.order_id<s.order_max))
                FROM weekly_ranking_order_partitions s WHERE s.snapshot_id=? AND s.order_partition_id=?
                """, Boolean.class, snapshot, claim.orderPartitionId());
        }
        long staged = jdbc.queryForObject("""
            SELECT COUNT(*) FROM weekly_ranking_partial_scores WHERE snapshot_id=? AND order_partition_id=?
            """, Long.class, snapshot, claim.orderPartitionId());
        boolean frozen = jdbc.queryForObject("""
            SELECT frozen_at IS NOT NULL FROM weekly_ranking_order_partitions WHERE snapshot_id=? AND order_partition_id=?
            """, Boolean.class, snapshot, claim.orderPartitionId());
        if (!frozen) {
            var freeze = jobs.getLastStepExecution(execution.getJobInstance(), "freezeWeeklyRankingOrderPartition");
            return staged == 0 && (freeze == null || freeze.getStatus() != BatchStatus.COMPLETED)
                && jobs.getLastStepExecution(execution.getJobInstance(), "calculateWeeklyRankingOrderPartition") == null;
        }
        var command = new WeeklyRankingOrderPartitionCommand(claim.snapshot(), claim.orderPartitionId(), new RankingPeriod(claim.period()),
            claim.revision(), claim.owner(), claim.generation());
        long verified = 0;
        for (var localProductPartition : inputs.localProductPartitions(command)) {
            var step = jobs.getLastStepExecution(execution.getJobInstance(), "scoreWeeklyRankingLocalProductPartition:localProductPartition-" + localProductPartition.index());
            if (step == null) {
                continue;
            }
            var context = step.getExecutionContext();
            if (step.getStatus() == BatchStatus.UNKNOWN || step.getStatus() == BatchStatus.ABANDONED
                || context.getLong("minProductId", -1) != localProductPartition.firstProduct()
                || context.getLong("maxProductId", -1) != localProductPartition.lastProduct()) {
                return false;
            }
            long saved = context.getInt("weeklyRankingLocalProductPartitionProducts.read.count", 0);
            var counts = jdbc.queryForObject("""
                WITH expected AS (
                    SELECT product_id,SUM(quantity) AS score FROM weekly_ranking_snapshot_inputs
                    WHERE snapshot_id=? AND product_id BETWEEN ? AND ?
                      AND (? IS NULL OR order_id>=?) AND (? IS NULL OR order_id<?) GROUP BY product_id
                ), prefix AS (SELECT * FROM expected ORDER BY product_id LIMIT ?)
                SELECT (SELECT COUNT(*) FROM expected),(SELECT COUNT(*) FROM prefix e JOIN weekly_ranking_partial_scores v
                    ON v.snapshot_id=? AND v.order_partition_id=? AND v.product_id=e.product_id AND v.score=e.score)
                """, (row, n) -> new Counts(row.getLong(1), row.getLong(2)), snapshot, localProductPartition.firstProduct(), localProductPartition.lastProduct(),
                localProductPartition.orderPartitionBounds().min(), localProductPartition.orderPartitionBounds().min(), localProductPartition.orderPartitionBounds().max(), localProductPartition.orderPartitionBounds().max(),
                Math.max(0, saved), snapshot, claim.orderPartitionId());
            boolean completed = step.getStatus() == BatchStatus.COMPLETED;
            if (saved < 0 || saved > counts.total() + 1 || completed && saved < counts.total()
                || counts.matches() != Math.min(saved, counts.total())) {
                return false;
            }
            verified += counts.matches();
        }
        return verified == staged;
    }

    private record Counts(long total, long matches) { }
}
