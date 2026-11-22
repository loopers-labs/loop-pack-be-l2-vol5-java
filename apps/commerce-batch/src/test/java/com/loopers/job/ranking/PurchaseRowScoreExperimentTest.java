package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static com.loopers.job.ranking.PurchaseActivityFixture.purchase;
import static org.assertj.core.api.Assertions.assertThat;

/** 구매 내역 기준 계산과 재처리를 테스트 DB의 실험용 테이블로 확인한다. */
@Tag("experiment")
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "commerce.ranking.weekly.chunk-size=2"
})
class PurchaseRowScoreExperimentTest {
    private static final int CHUNK_SIZE = 2;
    private PurchaseRowScoreExperiment experiment;

    private record RankedScore(long productId, long score, long rank) {}

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job weeklyRanking;

    @Autowired
    @Qualifier("mySqlMainDataSource")
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareExperimentTables() {
        jdbc = new JdbcTemplate(dataSource);
        experiment = new PurchaseRowScoreExperiment(dataSource, jobRepository, transactionManager, jdbc);
        experiment.prepareTables();
    }

    @Test
    void rollsBackDeltaAndResumesFromLastCommittedPurchaseKey() throws Exception {
        String periodStart = "2027-11-08";
        String snapshotId = "weekly-20271108-r1";
        new PurchaseActivityFixture(jdbc).insert(
            purchase(1701, 7, 2, periodStart + "T01:00:00"),
            purchase(1702, 8, 1, periodStart + "T02:00:00"),
            purchase(1702, 9, 5, periodStart + "T02:00:00"),
            purchase(1703, 7, 3, periodStart + "T03:00:00"));

        var parameters = new JobParametersBuilder().addString("snapshotId", snapshotId).toJobParameters();
        var productBasedParameters = new JobParametersBuilder(parameters).addString("periodStart", periodStart).toJobParameters();
        assertThat(jobLauncher.run(weeklyRanking, productBasedParameters).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        var failed = jobLauncher.run(experiment.job(snapshotId, CHUNK_SIZE, true), parameters);
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        var failedStep = failed.getStepExecutions().iterator().next();
        var checkpoint = jobExplorer.getStepExecution(failed.getId(), failedStep.getId()).getExecutionContext();
        assertThat(checkpoint.getInt(PurchaseRowScoreExperiment.READER_NAME + ".read.count")).isEqualTo(2);
        assertThat(checkpoint.get(PurchaseRowScoreExperiment.READER_NAME + ".start.after")).isEqualTo(Map.of("order_id", 1702L, "product_id", 8L));
        assertThat(purchaseBasedRanking(snapshotId)).containsExactly(new RankedScore(7, 2, 1), new RankedScore(8, 1, 2));

        var resumed = jobLauncher.run(experiment.job(snapshotId, CHUNK_SIZE, false), parameters);
        assertThat(resumed.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(resumed.getJobInstance().getId()).isEqualTo(failed.getJobInstance().getId());
        assertThat(resumed.getId()).isNotEqualTo(failed.getId());
        assertThat(resumed.getStepExecutions().iterator().next().getReadCount()).isEqualTo(2);
        var expected = List.of(new RankedScore(7, 5, 1), new RankedScore(9, 5, 2), new RankedScore(8, 1, 3));
        assertThat(purchaseBasedRanking(snapshotId)).containsExactlyElementsOf(expected);
        assertThat(productBasedRanking(snapshotId)).containsExactlyElementsOf(expected);

        // 같은 결과 영역을 재사용한 새 JobInstance에는 이전 체크포인트가 없다.
        var replayParameters = new JobParametersBuilder(parameters).addLong("replay", 1L).toJobParameters();
        var replayed = jobLauncher.run(experiment.job(snapshotId, CHUNK_SIZE, false), replayParameters);
        assertThat(replayed.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(replayed.getJobInstance().getId()).isNotEqualTo(resumed.getJobInstance().getId());
        assertThat(replayed.getStepExecutions().iterator().next().getReadCount()).isEqualTo(4);
        assertThat(purchaseBasedRanking(snapshotId)).containsExactly(
            new RankedScore(7, 10, 1), new RankedScore(9, 10, 2), new RankedScore(8, 2, 3));
    }

    private List<RankedScore> purchaseBasedRanking(String snapshotId) {
        return jdbc.query("""
            SELECT product_id, score, ROW_NUMBER() OVER (ORDER BY score DESC, product_id ASC) AS ranking
            FROM exp_purchase_row_score WHERE snapshot_id = ? ORDER BY ranking
            """, (rs, n) -> new RankedScore(rs.getLong("product_id"), rs.getLong("score"), rs.getLong("ranking")), snapshotId);
    }

    private List<RankedScore> productBasedRanking(String snapshotId) {
        return jdbc.query("SELECT product_id, score, ranking FROM ranking_entry WHERE snapshot_id = ? ORDER BY ranking",
            (rs, n) -> new RankedScore(rs.getLong("product_id"), rs.getLong("score"), rs.getLong("ranking")), snapshotId);
    }
}
