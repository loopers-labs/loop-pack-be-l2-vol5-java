package com.loopers.batch.ranking;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

public class PublishWeeklyRankingTasklet implements Tasklet {

    private final JdbcTemplate jdbc;
    private final String rawPeriodStart;
    private final String snapshotId;

    public PublishWeeklyRankingTasklet(JdbcTemplate jdbc, String periodStart, String snapshotId) {
        this.jdbc = jdbc;
        this.rawPeriodStart = periodStart;
        this.snapshotId = snapshotId;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        LocalDate periodStart = LocalDate.parse(rawPeriodStart);
        Integer inputProductCount = jdbc.queryForObject("""
            select count(distinct product_id)
            from weekly_ranking_snapshot_input where snapshot_id = ?
            """, Integer.class, snapshotId);
        Integer scoreProductCount = jdbc.queryForObject(
            "select count(*) from weekly_ranking_score where snapshot_id = ?",
            Integer.class,
            snapshotId
        );
        if (inputProductCount == null || scoreProductCount == null
            || !inputProductCount.equals(scoreProductCount)) {
            throw new IllegalStateException("Ranking scores are incomplete");
        }

        jdbc.update("delete from weekly_ranking_item where snapshot_id = ?", snapshotId);
        jdbc.update("""
            insert into weekly_ranking_item (snapshot_id, product_id, score, rank_no)
            select snapshot_id, product_id, score,
                   row_number() over (order by score desc, product_id asc)
            from weekly_ranking_score
            where snapshot_id = ?
            """, snapshotId);
        jdbc.update("""
            insert into weekly_ranking_publication (period_start, snapshot_id)
            values (?, ?)
            on duplicate key update snapshot_id = values(snapshot_id)
            """, periodStart, snapshotId);
        return RepeatStatus.FINISHED;
    }
}
