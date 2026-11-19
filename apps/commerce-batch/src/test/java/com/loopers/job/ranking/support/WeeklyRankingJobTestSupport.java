package com.loopers.job.ranking.support;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.infrastructure.RankingPublicationStore;
import com.loopers.batch.job.ranking.infrastructure.RankingSnapshotStore;
import com.loopers.batch.job.ranking.infrastructure.ScoreStageStore;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@SpringBootTest(properties = {
        "spring.batch.job.enabled=false",
        "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
        "weekly-ranking.chunk-size=2"
})
public abstract class WeeklyRankingJobTestSupport {
    protected static final String WEEK = "2026-08-17";

    @Autowired @Qualifier("mySqlMainDataSource") protected DataSource dataSource;
    @Autowired protected JobLauncher jobLauncher;
    @Autowired protected JobExplorer jobExplorer;
    @Autowired protected JobRepository jobRepository;
    @Autowired @Qualifier(WeeklyRankingJobConfig.JOB_NAME) protected Job job;

    @MockitoSpyBean protected RankingSnapshotStore snapshotStore;
    @MockitoSpyBean protected ScoreStageStore scoreStageStore;
    @MockitoSpyBean protected RankingPublicationStore publicationStore;

    protected WeeklyRankingTestDatabase database;
    protected JdbcTemplate jdbc;

    @BeforeEach
    void prepareDatabase() {
        database = new WeeklyRankingTestDatabase(dataSource);
        database.reset();
        jdbc = database.jdbc();
    }

    protected static JobParameters params(String periodStart, String snapshotId) {
        return new JobParametersBuilder()
                .addString("periodStart", periodStart, true)
                .addString("snapshotId", snapshotId, true)
                .toJobParameters();
    }

    protected static String snapshot(int revision) {
        return "weekly-20260817-r" + revision;
    }

    protected JobExecution launch(String snapshotId) throws Exception {
        return jobLauncher.run(job, params(WEEK, snapshotId));
    }

    protected StepExecution persistedStep(JobExecution execution, String stepName) {
        StepExecution step = execution.getStepExecutions().stream()
                .filter(s -> s.getStepName().equals(stepName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("step " + stepName + " was not executed"));
        return jobExplorer.getStepExecution(execution.getId(), step.getId());
    }

    protected List<String> executedSteps(JobExecution execution) {
        return execution.getStepExecutions().stream().map(StepExecution::getStepName).toList();
    }

    protected Map<Long, Long> stageScores(String snapshotId) {
        var scores = new LinkedHashMap<Long, Long>();
        jdbc.query("SELECT product_id, score FROM ranking_score_stage WHERE snapshot_id = ? ORDER BY product_id",
                rs -> {
                    scores.put(rs.getLong(1), rs.getLong(2));
                }, snapshotId);
        return scores;
    }

    protected List<List<Long>> ranking(String snapshotId) {
        return jdbc.query("SELECT product_id, score, rank_no FROM ranking_result WHERE snapshot_id = ? "
                + "ORDER BY rank_no", (rs, n) -> List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3)), snapshotId);
    }

    protected Optional<String> publishedSnapshot(String periodStart) {
        return jdbc.queryForList("SELECT snapshot_id FROM ranking_publication WHERE period_start = ?",
                String.class, periodStart).stream().findFirst();
    }

    protected Map<String, Object> manifest(String snapshotId) {
        return jdbc.queryForMap("SELECT * FROM ranking_snapshot WHERE snapshot_id = ?", snapshotId);
    }

    protected int inputCount(String snapshotId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ranking_snapshot_input WHERE snapshot_id = ?",
                Integer.class, snapshotId);
        return count == null ? 0 : count;
    }
}
