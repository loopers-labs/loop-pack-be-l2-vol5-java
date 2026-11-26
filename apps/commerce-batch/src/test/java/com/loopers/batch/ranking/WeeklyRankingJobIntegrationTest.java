package com.loopers.batch.ranking;

import com.loopers.testcontainers.MySqlTestContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.batch.job.name=weeklyRankingJob",
    "spring.batch.job.enabled=false",
    "weekly-ranking.batch.chunk-size=2"
})
@Import(MySqlTestContainersConfig.class)
@Sql("/week9-ranking-schema.sql")
class WeeklyRankingJobIntegrationTest {

    @Autowired JobLauncher jobLauncher;
    @Autowired @Qualifier(WeeklyRankingJobConfig.JOB_NAME) Job weeklyRankingJob;
    @Autowired JdbcTemplate jdbc;

    @Test
    void resumesSameJobAfterChunkFailureAndPublishesOnlyWhenComplete() throws Exception {
        LocalDate periodStart = randomMonday();
        LocalDate periodEnd = periodStart.plusWeeks(1);
        String snapshotId = "ranking-" + UUID.randomUUID();
        insertPurchase(1001L, 7L, 2, periodStart.atStartOfDay());
        insertPurchase(1001L, 8L, 1, periodStart.atStartOfDay());
        insertPurchase(1002L, 7L, 3, periodStart.plusDays(1).atStartOfDay());
        insertPurchase(1003L, 9L, 5, periodStart.plusDays(2).atStartOfDay());
        insertPurchase(1004L, 10L, 99, periodEnd.atStartOfDay());

        JobParameters parameters = parameters(periodStart, snapshotId);
        jdbc.execute("alter table weekly_ranking_score "
            + "add constraint ck_weekly_score_failure check (product_id <> 9)");
        JobExecution failed;
        try {
            failed = jobLauncher.run(weeklyRankingJob, parameters);
        } finally {
            jdbc.execute("alter table weekly_ranking_score drop check ck_weekly_score_failure");
        }

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        StepExecution failedCalculation = failed.getStepExecutions().stream()
            .filter(step -> step.getStepName().equals("calculateWeeklyScoresStep"))
            .findFirst()
            .orElseThrow();
        assertThat(failedCalculation.getExecutionContext().getInt("weeklyScoreReader.read.count"))
            .isEqualTo(2);
        assertThat(count("weekly_ranking_snapshot_input", snapshotId)).isEqualTo(4);
        assertThat(count("weekly_ranking_score", snapshotId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
            "select count(*) from weekly_ranking_publication where period_start = ?",
            Integer.class,
            periodStart
        )).isZero();

        JobExecution completed = jobLauncher.run(weeklyRankingJob, parameters);

        assertThat(completed.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(completed.getJobInstance().getInstanceId())
            .isEqualTo(failed.getJobInstance().getInstanceId());
        assertThat(completed.getId()).isNotEqualTo(failed.getId());
        assertThat(count("weekly_ranking_score", snapshotId)).isEqualTo(3);
        assertThat(jdbc.query("""
            select product_id, score, rank_no from weekly_ranking_item
            where snapshot_id = ? order by rank_no
            """, (rs, row) -> new WeeklyScoreRow(rs.getLong(1), rs.getLong(2), rs.getLong(3)), snapshotId))
            .containsExactly(
                new WeeklyScoreRow(7L, 5L, 1L),
                new WeeklyScoreRow(9L, 5L, 2L),
                new WeeklyScoreRow(8L, 1L, 3L)
            );
        assertThat(jdbc.queryForObject(
            "select snapshot_id from weekly_ranking_publication where period_start = ?",
            String.class,
            periodStart
        )).isEqualTo(snapshotId);
    }

    @Test
    void scoreWriterCanSafelyWriteTheSameSnapshotItemTwice() throws Exception {
        String snapshotId = "idempotent-" + UUID.randomUUID();
        WeeklyScoreWriter writer = new WeeklyScoreWriter(jdbc, snapshotId);
        var chunk = new org.springframework.batch.item.Chunk<>(
            java.util.List.of(new WeeklyScore(77L, 12L))
        );

        writer.write(chunk);
        writer.write(chunk);

        assertThat(count("weekly_ranking_score", snapshotId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select score from weekly_ranking_score where snapshot_id = ? and product_id = 77",
            Long.class,
            snapshotId
        )).isEqualTo(12L);
    }

    private void insertPurchase(long orderId, long productId, int quantity, LocalDateTime confirmedAt) {
        jdbc.update("""
            insert into product_purchase_activity (order_id, product_id, quantity, confirmed_at)
            values (?, ?, ?, ?)
            """, orderId + ThreadLocalRandom.current().nextLong(100_000_000L), productId, quantity,
            Timestamp.valueOf(confirmedAt));
    }

    private int count(String table, String snapshotId) {
        return jdbc.queryForObject("select count(*) from " + table + " where snapshot_id = ?", Integer.class, snapshotId);
    }

    private static JobParameters parameters(LocalDate periodStart, String snapshotId) {
        return new JobParametersBuilder()
            .addString("periodStart", periodStart.toString(), true)
            .addString("snapshotId", snapshotId, true)
            .toJobParameters();
    }

    private static LocalDate randomMonday() {
        int weekOffset = ThreadLocalRandom.current().nextInt(1, 100_000);
        return LocalDate.of(2024, 1, 1)
            .with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
            .plusWeeks(weekOffset);
    }

    private record WeeklyScoreRow(long productId, long score, long rank) {
    }
}
