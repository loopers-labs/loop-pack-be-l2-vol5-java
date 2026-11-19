package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.step.OpenWeeklyRankingTasklet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Step 3 공개의 수렴(R6)과 한 TX rollback(R3). period 2026-08-10, orderId 3000 번대. */
@Import(RankingTestConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME})
class OpenWeeklyRankingConvergenceTest {

    private static final LocalDate PERIOD = LocalDate.of(2026, 8, 10);
    private static final String WRONG_CHECKSUM = "0".repeat(64);

    private final Job job;
    private final Step openStep;
    private final JobRepository jobRepository;
    private final JobLauncher jobLauncher;
    private final JdbcTemplate jdbcTemplate;
    private final RankingTestConfig.FailOnceScoreHook hook;
    private final RankingTestSupport support;

    @Autowired
    OpenWeeklyRankingConvergenceTest(
        @Qualifier(WeeklyRankingJobConfig.JOB_NAME) Job job,
        @Qualifier(OpenWeeklyRankingTasklet.STEP_NAME) Step openStep,
        JobRepository jobRepository,
        JobLauncher jobLauncher,
        JdbcTemplate jdbcTemplate,
        RankingTestConfig.FailOnceScoreHook hook
    ) {
        this.job = job;
        this.openStep = openStep;
        this.jobRepository = jobRepository;
        this.jobLauncher = jobLauncher;
        this.jdbcTemplate = jdbcTemplate;
        this.hook = hook;
        this.support = new RankingTestSupport(jdbcTemplate);
    }

    @BeforeAll
    void createInputTable() {
        support.createPurchaseActivityTable();
    }

    @DisplayName("공개가 끝난 snapshot 에 Step 3 만 다시 실행해도 ranking·checksum·pointer(published_at 포함)가 바뀌지 않고, 공개 TX 중간 실패는 ranking·status·pointer 를 전부 되돌린다.")
    @Test
    void republishConvergesAndMidTxFailureRollsBackEverything() throws Exception {
        // arrange — r1 정상 공개
        support.insertFixture(PERIOD, 3000);
        String r1 = RankingTestSupport.snapshotId(PERIOD, 1);
        JobExecution r1Run = jobLauncher.run(job, RankingTestSupport.params(PERIOD, 1));
        assertThat(r1Run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<Map<String, Object>> scoresBefore = support.scores(r1);
        Map<String, Object> revisionBefore = support.revision(r1);
        Map<String, Object> pointerBefore = support.publication(PERIOD).get(0);

        // act — R6: Step 3 빈만 묶은 probe Job 을 같은 snapshotId 파라미터로 실행
        Job openOnly = new JobBuilder("openOnlyProbe", jobRepository).start(openStep).build();
        JobExecution republish = jobLauncher.run(openOnly, RankingTestSupport.params(PERIOD, 1));

        // assert — 변화 없음
        assertThat(republish.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(support.scores(r1)).usingRecursiveComparison().isEqualTo(scoresBefore);
        assertThat(support.revision(r1).get("result_checksum")).isEqualTo(revisionBefore.get("result_checksum"));
        Map<String, Object> pointerAfter = support.publication(PERIOD).get(0);
        assertThat(pointerAfter.get("snapshot_id")).isEqualTo(pointerBefore.get("snapshot_id"));
        assertThat(pointerAfter.get("revision")).isEqualTo(pointerBefore.get("revision"));
        assertThat(pointerAfter.get("published_at")).isEqualTo(pointerBefore.get("published_at"));
        System.out.println("[convergence] republish exitStatus=" + republish.getExitStatus());

        // arrange — R3: r2 를 Step 2 상품 9 직전에 멈추고, 저장된 result_checksum 을 잘못된 값으로 심어 Step 3 의 5단계가 실패하게 만든다
        String r2 = RankingTestSupport.snapshotId(PERIOD, 2);
        hook.armFailOnce(r2, 9L);
        JobExecution r2First = jobLauncher.run(job, RankingTestSupport.params(PERIOD, 2));
        assertThat(r2First.getStatus()).isEqualTo(BatchStatus.FAILED);
        jdbcTemplate.update("UPDATE weekly_ranking_revisions SET result_checksum = ? WHERE snapshot_id = ?", WRONG_CHECKSUM, r2);

        // act — 재시작: Step 2 는 상품 9 를 마치고, Step 3 는 순위를 쓴 뒤 checksum 불일치로 예외
        JobExecution r2Restart = jobLauncher.run(job, RankingTestSupport.params(PERIOD, 2));

        // assert — Step 3 TX 전체 rollback: ranking NULL, 회차 CALCULATING, pointer 는 r1 그대로
        assertThat(r2Restart.getStatus()).isEqualTo(BatchStatus.FAILED);
        StepExecution openExecution = r2Restart.getStepExecutions().stream()
            .filter(s -> s.getStepName().equals(OpenWeeklyRankingTasklet.STEP_NAME)).findFirst().orElseThrow();
        assertThat(openExecution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(openExecution.getFailureExceptions()).singleElement()
            .extracting(Throwable::getMessage).asString().contains("checksum");
        assertThat(support.scores(r2)).hasSize(3).allSatisfy(row -> assertThat(row.get("ranking")).isNull());
        Map<String, Object> rev2 = support.revision(r2);
        assertThat(rev2.get("status")).isEqualTo("CALCULATING");
        assertThat(rev2.get("result_checksum")).isEqualTo(WRONG_CHECKSUM);
        Map<String, Object> pointerStill = support.publication(PERIOD).get(0);
        assertThat(pointerStill.get("snapshot_id")).isEqualTo(r1);
        assertThat(pointerStill.get("revision")).isEqualTo(1);
        assertThat(pointerStill.get("published_at")).isEqualTo(pointerBefore.get("published_at"));
    }
}
