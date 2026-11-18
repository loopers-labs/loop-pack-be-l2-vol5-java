package com.loopers.batch.job.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.sql.Connection;
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
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
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
class WeeklyRankingRestartTest {

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

    @DisplayName("JOB-004, EXP-026: 계산이 상품 9 에서 실패하면, 같은 parameter 의 재실행이 같은 instance 에서 고정 step 을 다시 돌지 않고 저장된 위치 다음 상품부터 이어 간다.")
    @Test
    void resumesCalculationFromLastCommittedProduct() throws Exception {
        JobParameters params = new JobParametersBuilder()
            .addLocalDate("periodStart", LocalDate.of(2026, 8, 17))
            .addLong("revision", 1L)
            .addString("snapshotId", SNAPSHOT_ID)
            .toJobParameters();

        long firstId;
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var hold = blocker.prepareStatement(
                "insert into ranking_score_stage (snapshot_id, product_id, score) values (?, 9, 0)"
            )) {
                hold.setString(1, SNAPSHOT_ID);
                hold.executeUpdate();
            }
            firstId = launch(params);
            blocker.rollback();
        }

        JobExecution first = jobExplorer.getJobExecution(firstId);
        StepExecution failedCalculation = stepNamed(first, "calculateScore");
        assertAll(
            () -> assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED),
            () -> assertThat(failedCalculation.getExecutionContext().get("snapshotProductReader.start.after"))
                .hasToString("{product_id=8}"),
            () -> assertThat(stagedScores()).containsExactly("7:5", "8:1")
        );

        long secondId = launch(params);

        JobExecution second = jobExplorer.getJobExecution(secondId);
        assertAll(
            () -> assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(second.getJobInstance().getInstanceId())
                .isEqualTo(first.getJobInstance().getInstanceId()),
            () -> assertThat(secondId).isNotEqualTo(firstId),
            () -> assertThat(second.getStepExecutions()).extracting(StepExecution::getStepName)
                .containsExactly("calculateScore", "publishRanking"),
            () -> assertThat(stepNamed(second, "calculateScore").getWriteCount()).isEqualTo(1),
            () -> assertThat(stagedScores()).containsExactly("7:5", "8:1", "9:5")
        );
    }

    private long launch(JobParameters params) throws Exception {
        JobExecution execution = jobLauncher.run(job, params);
        executionIds.add(execution.getId());
        return execution.getId();
    }

    private StepExecution stepNamed(JobExecution execution, String stepName) {
        StepExecution step = execution.getStepExecutions().stream()
            .filter(candidate -> candidate.getStepName().equals(stepName))
            .findFirst()
            .orElseThrow();
        return jobExplorer.getStepExecution(execution.getId(), step.getId());
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
