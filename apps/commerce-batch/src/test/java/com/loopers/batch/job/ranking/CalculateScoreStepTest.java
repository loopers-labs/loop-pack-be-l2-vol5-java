package com.loopers.batch.job.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "ranking.chunk-size=2"
})
@Sql("/sql/purchase-activity.sql")
class CalculateScoreStepTest {

    private static final String SNAPSHOT_ID = "weekly-20260817-r1";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    private JdbcTemplate jdbc;

    private final List<Long> executionIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("truncate table purchase_activity");
        jdbc.execute("truncate table ranking_snapshot");
        jdbc.execute("truncate table ranking_snapshot_input");
        jdbc.execute("truncate table ranking_score_stage");

        insertActivity(42L, 7L, 2, LocalDateTime.parse("2026-08-17T01:00:00"));
        insertActivity(42L, 8L, 1, LocalDateTime.parse("2026-08-17T01:00:00"));
        insertActivity(43L, 7L, 3, LocalDateTime.parse("2026-08-18T01:00:00"));
        insertActivity(44L, 9L, 5, LocalDateTime.parse("2026-08-19T01:00:00"));
    }

    @AfterEach
    void tearDown() {
        new JobRepositoryTestUtils(jobRepository).removeJobExecutions(
            executionIds.stream().map(jobExplorer::getJobExecution).toList()
        );
    }

    @DisplayName("CAL-001, CAL-002, CAL-003: 고정한 입력의 상품마다 quantity 합을 점수로 stage 에 쓴다.")
    @Test
    void writesQuantitySumPerProduct() throws Exception {
        long executionId = launch();

        assertAll(
            () -> assertThat(statusOf(executionId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(stagedScores()).containsExactly("7:5", "8:1", "9:5")
        );
    }

    @DisplayName("CAL-002: 점수는 고정한 입력에서 구한다. 고정 뒤 원본에 들어온 구매는 점수에 없다.")
    @Test
    void scoresFromFrozenInputNotFromSource() throws Exception {
        long executionId = launch();
        insertActivity(60L, 8L, 4, LocalDateTime.parse("2026-08-20T00:00:00"));
        jdbc.update("delete from ranking_score_stage where snapshot_id = ?", SNAPSHOT_ID);
        new JobRepositoryTestUtils(jobRepository).removeJobExecutions(
            List.of(jobExplorer.getJobExecution(executionId))
        );
        executionIds.remove(executionId);

        long rerunId = launch();

        assertAll(
            () -> assertThat(statusOf(rerunId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(stagedScores()).containsExactly("7:5", "8:1", "9:5")
        );
    }

    private long launch() throws Exception {
        JobExecution execution = jobLauncher.run(job, new JobParametersBuilder()
            .addLocalDate("periodStart", LocalDate.of(2026, 8, 17))
            .addLong("revision", 1L)
            .addString("snapshotId", SNAPSHOT_ID)
            .toJobParameters());
        executionIds.add(execution.getId());
        return execution.getId();
    }

    private BatchStatus statusOf(long executionId) {
        return jobExplorer.getJobExecution(executionId).getStatus();
    }

    private List<String> stagedScores() {
        return jdbc.query(
            "select product_id, score from ranking_score_stage where snapshot_id = ? order by product_id",
            (rs, rowNum) -> rs.getLong("product_id") + ":" + rs.getLong("score"),
            SNAPSHOT_ID
        );
    }

    private void insertActivity(long orderId, long productId, int quantity, LocalDateTime confirmedAtUtc) {
        jdbc.update(
            "insert into purchase_activity "
                + "(created_at, updated_at, order_id, product_id, quantity, confirmed_at) "
                + "values (now(6), now(6), ?, ?, ?, ?)",
            orderId, productId, quantity, confirmedAtUtc
        );
    }
}
