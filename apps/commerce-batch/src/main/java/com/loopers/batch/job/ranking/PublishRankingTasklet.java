package com.loopers.batch.job.ranking;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class PublishRankingTasklet implements Tasklet {

    private final JdbcTemplate jdbc;

    PublishRankingTasklet(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        JobParameters params = chunkContext.getStepContext().getStepExecution().getJobParameters();
        WeeklySnapshot snapshot = new WeeklySnapshot(
            params.getLocalDate("periodStart"),
            Math.toIntExact(params.getLong("revision"))
        );

        long products = jdbc.queryForObject(
            "select count(distinct product_id) from ranking_snapshot_input where snapshot_id = ?",
            Long.class, snapshot.id()
        );
        long scores = jdbc.queryForObject(
            "select count(*) from ranking_score_stage where snapshot_id = ?",
            Long.class, snapshot.id()
        );
        if (scores != products) {
            throw new IllegalStateException(
                "snapshot " + snapshot.id() + " has " + scores + " scores for " + products + " products"
            );
        }

        long ranked = jdbc.queryForObject(
            "select count(*) from weekly_ranking where snapshot_id = ?",
            Long.class, snapshot.id()
        );
        if (ranked == 0) {
            jdbc.update("""
                insert into weekly_ranking (snapshot_id, rank_no, product_id, score)
                select snapshot_id, row_number() over (order by score desc, product_id), product_id, score
                from ranking_score_stage
                where snapshot_id = ?
                """, snapshot.id());
        }

        jdbc.update("""
            insert into weekly_ranking_publication (period_start, snapshot_id, revision, published_at)
            values (?, ?, ?, ?) as new
            on duplicate key update
              snapshot_id = if(new.revision > weekly_ranking_publication.revision,
                               new.snapshot_id, weekly_ranking_publication.snapshot_id),
              published_at = if(new.revision > weekly_ranking_publication.revision,
                                new.published_at, weekly_ranking_publication.published_at),
              revision = greatest(new.revision, weekly_ranking_publication.revision)
            """,
            snapshot.periodStart(), snapshot.id(), snapshot.revision(), LocalDateTime.now(ZoneOffset.UTC)
        );
        return RepeatStatus.FINISHED;
    }
}
