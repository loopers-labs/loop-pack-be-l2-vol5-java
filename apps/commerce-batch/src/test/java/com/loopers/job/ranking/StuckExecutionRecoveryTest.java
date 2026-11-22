package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.ProductScore;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingStore;
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
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.loopers.job.ranking.PurchaseActivityFixture.purchase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * 프로세스 강제 종료로 실행 기록이 STARTED에 남았을 때의 정리 절차(runbook/mark-execution-failed.sql, ADR 0092).
 * 테스트는 운영자가 쓰는 SQL 파일을 그대로 실행한다.
 */
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "commerce.ranking.weekly.chunk-size=2"
})
class StuckExecutionRecoveryTest {

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    @Autowired
    private JobExplorer jobExplorer;

    @MockitoSpyBean
    private WeeklyRankingStore store;

    @Autowired
    @Qualifier("mySqlMainDataSource")
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private PurchaseActivityFixture activities;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        activities = new PurchaseActivityFixture(jdbc);
    }

    @DisplayName("강제 종료로 STARTED에 남은 실행이 있으면 같은 parameter의 재실행이 거부되고, 정리 SQL을 실행하면 같은 JobInstance로 이어서 끝난다.")
    @Test
    void recoversStartedExecutionWithRunbookSql() throws Exception {
        insertThreeProducts(1101, "2026-10-19", "2026-10-20");
        JobParameters params = params("2026-10-19", "weekly-20261019-r1");
        failOnceBeforeWritingProductNine();
        JobExecution first = jobLauncher.run(job, params);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        leaveAsStarted(first.getId());

        assertThatThrownBy(() -> jobLauncher.run(job, params)).isInstanceOf(JobExecutionAlreadyRunningException.class);

        markFailed(first.getId());
        JobExecution second = jobLauncher.run(job, params);

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getId()).isEqualTo(first.getJobInstance().getId());
        assertThat(scoreStep(second).getReadCount()).isEqualTo(1);
        assertThat(stageScores("weekly-20261019-r1"))
            .containsExactly(Map.entry(7L, 5L), Map.entry(8L, 1L), Map.entry(9L, 5L));
    }

    @DisplayName("끝난 줄 알고 정리한 실행이 살아 있었다면, 그 실행의 다음 chunk commit이 version 불일치로 실패해 점수가 남지 않는다. 그 실행은 UNKNOWN으로 끝나므로 한 번 더 정리한 뒤 재시작한다.")
    @Test
    void runbookSqlStopsExecutionThatWasStillAlive() throws Exception {
        insertThreeProducts(1201, "2026-10-26", "2026-10-27");
        JobParameters params = params("2026-10-26", "weekly-20261026-r1");
        CountDownLatch reachedProductNine = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pauseBeforeWritingProductNine(reachedProductNine, release);

        CompletableFuture<JobExecution> alive = CompletableFuture.supplyAsync(() -> {
            try {
                return jobLauncher.run(job, params);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(reachedProductNine.await(30, TimeUnit.SECONDS)).isTrue();
        Long executionId = jobExplorer.findRunningJobExecutions(WeeklyRankingJobConfig.JOB_NAME).stream()
            .filter(execution -> execution.getJobParameters().equals(params))
            .findFirst().orElseThrow().getId();

        markFailed(executionId);
        release.countDown();
        JobExecution oldExecution = alive.get(30, TimeUnit.SECONDS);

        assertThat(oldExecution.getStatus()).isNotEqualTo(BatchStatus.COMPLETED);
        assertThat(stageScores("weekly-20261026-r1")).containsExactly(Map.entry(7L, 5L), Map.entry(8L, 1L));
        assertThat(jobExplorer.getJobExecution(executionId).getStatus()).isEqualTo(BatchStatus.UNKNOWN);
        assertThatThrownBy(() -> jobLauncher.run(job, params)).isInstanceOf(org.springframework.batch.core.repository.JobRestartException.class);

        markFailed(executionId);
        JobExecution restarted = jobLauncher.run(job, params);
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stageScores("weekly-20261026-r1"))
            .containsExactly(Map.entry(7L, 5L), Map.entry(8L, 1L), Map.entry(9L, 5L));
    }

    private void insertThreeProducts(long firstOrderId, String monday, String tuesday) {
        activities.insert(
            purchase(firstOrderId, 7, 2, monday + "T01:00:00"),
            purchase(firstOrderId, 8, 1, monday + "T01:00:00"),
            purchase(firstOrderId + 1, 7, 3, tuesday + "T01:00:00"),
            purchase(firstOrderId + 2, 9, 5, tuesday + "T01:00:00"));
    }

    /** kill -9처럼 아무도 FAILED를 쓰지 못한 상태로 되돌린다. */
    private void leaveAsStarted(long jobExecutionId) {
        jdbc.update("UPDATE BATCH_STEP_EXECUTION SET STATUS = 'STARTED', EXIT_CODE = 'UNKNOWN', END_TIME = NULL "
            + "WHERE JOB_EXECUTION_ID = ? AND STATUS = 'FAILED'", jobExecutionId);
        jdbc.update("UPDATE BATCH_JOB_EXECUTION SET STATUS = 'STARTED', EXIT_CODE = 'UNKNOWN', END_TIME = NULL "
            + "WHERE JOB_EXECUTION_ID = ?", jobExecutionId);
    }

    /** 운영자가 쓰는 정리 SQL 파일을 그대로 실행한다. */
    private void markFailed(long jobExecutionId) throws Exception {
        String sql = new ClassPathResource("runbook/mark-execution-failed.sql").getContentAsString(StandardCharsets.UTF_8);
        String withoutComments = Arrays.stream(sql.split("\n"))
            .filter(line -> !line.strip().startsWith("--"))
            .reduce("", (a, b) -> a + "\n" + b);
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc);
        for (String statement : withoutComments.split(";")) {
            if (!statement.isBlank()) {
                named.update(statement, Map.of("jobExecutionId", jobExecutionId));
            }
        }
    }

    private void failOnceBeforeWritingProductNine() {
        AtomicBoolean failed = new AtomicBoolean();
        doAnswer(invocation -> {
            List<ProductScore> scores = invocation.getArgument(1);
            if (scores.stream().anyMatch(score -> score.productId() == 9L) && failed.compareAndSet(false, true)) {
                throw new IllegalStateException("상품 9 저장 직전 실패");
            }
            return invocation.callRealMethod();
        }).when(store).insertScores(anyString(), anyList());
    }

    /** 상품 9를 쓰기 직전에 한 번 멈춰 기다린다. 그동안 그 실행은 살아 있다. */
    private void pauseBeforeWritingProductNine(CountDownLatch reached, CountDownLatch release) {
        AtomicBoolean paused = new AtomicBoolean();
        doAnswer(invocation -> {
            List<ProductScore> scores = invocation.getArgument(1);
            if (scores.stream().anyMatch(score -> score.productId() == 9L) && paused.compareAndSet(false, true)) {
                reached.countDown();
                release.await(30, TimeUnit.SECONDS);
            }
            return invocation.callRealMethod();
        }).when(store).insertScores(anyString(), anyList());
    }

    private StepExecution scoreStep(JobExecution execution) {
        return execution.getStepExecutions().stream()
            .filter(step -> step.getStepName().equals(WeeklyRankingJobConfig.SCORE_STEP))
            .findFirst().orElseThrow();
    }

    private Map<Long, Long> stageScores(String snapshotId) {
        Map<Long, Long> scores = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT product_id, score FROM ranking_score_stage WHERE snapshot_id = ? ORDER BY product_id",
            rs -> {
                scores.put(rs.getLong("product_id"), rs.getLong("score"));
            }, snapshotId);
        return scores;
    }

    private static JobParameters params(String periodStart, String snapshotId) {
        return new JobParametersBuilder()
            .addString("periodStart", periodStart, true)
            .addString("snapshotId", snapshotId, true)
            .toJobParameters();
    }
}
