package com.loopers.batch.ranking;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;

public class FixPurchaseSnapshotTasklet implements Tasklet {

    private final JdbcTemplate jdbc;
    private final String rawPeriodStart;
    private final String snapshotId;

    public FixPurchaseSnapshotTasklet(JdbcTemplate jdbc, String periodStart, String snapshotId) {
        this.jdbc = jdbc;
        this.rawPeriodStart = periodStart;
        this.snapshotId = snapshotId;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        LocalDate periodStart = LocalDate.parse(rawPeriodStart);
        if (periodStart.getDayOfWeek() != DayOfWeek.MONDAY
            || snapshotId == null || snapshotId.isBlank() || snapshotId.length() > 64) {
            throw new IllegalArgumentException("Invalid weekly ranking job parameters");
        }
        LocalDate periodEnd = periodStart.plusWeeks(1);
        LocalDate existingPeriod = jdbc.query(
            "select period_start from weekly_ranking_snapshot where snapshot_id = ?",
            resultSet -> resultSet.next() ? resultSet.getDate(1).toLocalDate() : null,
            snapshotId
        );
        if (existingPeriod != null) {
            if (!existingPeriod.equals(periodStart)) {
                throw new IllegalStateException("snapshotId already belongs to another period");
            }
            return RepeatStatus.FINISHED;
        }
        Integer periodExists = jdbc.queryForObject(
            "select count(*) from weekly_ranking_snapshot where period_start = ?",
            Integer.class,
            periodStart
        );
        if (periodExists != null && periodExists > 0) {
            throw new IllegalStateException("A snapshot already exists for this period");
        }

        jdbc.update("""
            insert into weekly_ranking_snapshot
                (snapshot_id, period_start, period_end, input_fixed_at, input_count)
            values (?, ?, ?, utc_timestamp(6), 0)
            """, snapshotId, periodStart, periodEnd);
        jdbc.update("""
            insert into weekly_ranking_snapshot_input
                (snapshot_id, order_id, product_id, quantity, confirmed_at)
            select ?, order_id, product_id, quantity, confirmed_at
            from product_purchase_activity
            where confirmed_at >= ? and confirmed_at < ?
            order by order_id, product_id
            """, snapshotId, periodStart, periodEnd);
        jdbc.update("""
            update weekly_ranking_snapshot
            set input_count = (
                select count(*) from weekly_ranking_snapshot_input where snapshot_id = ?
            )
            where snapshot_id = ?
            """, snapshotId, snapshotId);
        return RepeatStatus.FINISHED;
    }
}
