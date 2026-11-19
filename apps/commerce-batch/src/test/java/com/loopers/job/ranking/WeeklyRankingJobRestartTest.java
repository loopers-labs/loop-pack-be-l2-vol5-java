package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.step.FreezeWeeklyInputTasklet;
import com.loopers.batch.job.ranking.step.OpenWeeklyRankingTasklet;
import com.loopers.batch.job.ranking.step.WeeklyScoreStepComponents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * weeklyRanking Job 의 재시작 검증 (과제 §6 7단계). period 2026-08-17, fixture orderId 42·43·44 그대로라 기대 checksum 과 비교한다.
 * 실패 지점은 JobParameters 가 아니라 {@link RankingTestConfig.FailOnceScoreHook} 으로 만든다.
 */
@Import(RankingTestConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME})
class WeeklyRankingJobRestartTest {

    private static final LocalDate PERIOD = LocalDate.of(2026, 8, 17);
    private static final String READER_READ_COUNT_KEY = WeeklyScoreStepComponents.READER_NAME + ".read.count";

    private final Job job;
    private final JobLauncher jobLauncher;
    private final JobExplorer jobExplorer;
    private final RankingTestConfig.FailOnceScoreHook hook;
    private final RankingTestSupport support;

    @Autowired
    WeeklyRankingJobRestartTest(
        @Qualifier(WeeklyRankingJobConfig.JOB_NAME) Job job,
        JobLauncher jobLauncher,
        JobExplorer jobExplorer,
        RankingTestConfig.FailOnceScoreHook hook,
        JdbcTemplate jdbcTemplate
    ) {
        this.job = job;
        this.jobLauncher = jobLauncher;
        this.jobExplorer = jobExplorer;
        this.hook = hook;
        this.support = new RankingTestSupport(jdbcTemplate);
    }

    @BeforeAll
    void createInputTable() {
        support.createPurchaseActivityTable();
    }

    @DisplayName("weeklyRanking Job 은 입력 고정 → 점수 계산 → 공개 세 Step 을 순서대로 가진다.")
    @Test
    void assemblesThreeStepsInOrder() {
        assertThat(job.getName()).isEqualTo(WeeklyRankingJobConfig.JOB_NAME);
        assertThat(((SimpleJob) job).getStepNames()).containsExactly(
            FreezeWeeklyInputTasklet.STEP_NAME, WeeklyScoreStepComponents.STEP_NAME, OpenWeeklyRankingTasklet.STEP_NAME);
        assertThat(job.isRestartable()).isTrue();
        assertThat(job.getJobParametersIncrementer()).isNull();
    }

    @DisplayName("상품 9 직전에 실패한 r1 을 같은 파라미터로 재시작하면 상품 9 만 처리해 완성·공개하고, 정상 1회 실행 r2 와 같은 결과를 낸다.")
    @Test
    void restartsFromCheckpointAndMatchesCleanRun() throws Exception {
        // arrange
        support.insertFixture(PERIOD, 0);
        String r1 = RankingTestSupport.snapshotId(PERIOD, 1);
        JobParameters r1Params = RankingTestSupport.params(PERIOD, 1);
        hook.armFailOnce(r1, 9L);

        // act (1) 첫 실행 — 상품 9 직전 실패
        JobExecution first = jobLauncher.run(job, r1Params);

        // assert (2)~(4): Step 1 commit, Step 2 첫 chunk 만 commit, 공개 없음
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(support.countInputs(r1)).isEqualTo(4);
        Map<String, Object> rev1 = support.revision(r1);
        assertThat(rev1.get("status")).isEqualTo("CALCULATING");
        assertThat(rev1.get("input_count")).isEqualTo(4);
        assertThat(rev1.get("expected_product_count")).isEqualTo(3);
        assertThat(rev1.get("input_checksum")).isEqualTo(RankingTestSupport.FIXTURE_INPUT_CHECKSUM);
        assertThat(support.scores(r1)).extracting(m -> m.get("product_id"), m -> m.get("score"), m -> m.get("ranking"))
            .containsExactly(tuple(7L, 5L, null), tuple(8L, 1L, null));
        StepExecution firstScoreStep = stepFromDb(first, WeeklyScoreStepComponents.STEP_NAME);
        print("first", first, firstScoreStep);
        assertThat(firstScoreStep.getExecutionContext().getInt(READER_READ_COUNT_KEY)).isEqualTo(2);
        assertThat(firstScoreStep.getReadCount()).isEqualTo(3);
        assertThat(firstScoreStep.getWriteCount()).isEqualTo(2);
        assertThat(firstScoreStep.getCommitCount()).isEqualTo(1);
        assertThat(firstScoreStep.getRollbackCount()).isEqualTo(1);
        assertThat(support.publication(PERIOD)).isEmpty();

        // act (5) 같은 파라미터로 재시작
        JobExecution second = jobLauncher.run(job, r1Params);

        // assert (5)(6): 같은 instance·다른 execution, Step 1 재실행 없음, 상품 9 만
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getId()).isEqualTo(first.getJobInstance().getId());
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getStepExecutions()).extracting(StepExecution::getStepName)
            .containsExactly(WeeklyScoreStepComponents.STEP_NAME, OpenWeeklyRankingTasklet.STEP_NAME);
        StepExecution secondScoreStep = stepFromDb(second, WeeklyScoreStepComponents.STEP_NAME);
        print("second", second, secondScoreStep);
        assertThat(secondScoreStep.getReadCount()).isEqualTo(1);
        assertThat(secondScoreStep.getWriteCount()).isEqualTo(1);
        assertThat(support.countInputs(r1)).isEqualTo(4);

        // assert (7) 완성·공개
        assertThat(support.scores(r1)).extracting(m -> m.get("product_id"), m -> m.get("score"), m -> m.get("ranking"))
            .containsExactly(tuple(7L, 5L, 1), tuple(8L, 1L, 3), tuple(9L, 5L, 2));
        Map<String, Object> rev1Done = support.revision(r1);
        assertThat(rev1Done.get("status")).isEqualTo("COMPLETED");
        assertThat(rev1Done.get("result_checksum")).isEqualTo(RankingTestSupport.FIXTURE_RESULT_CHECKSUM);
        assertThat(support.publication(PERIOD)).singleElement().satisfies(p -> {
            assertThat(p.get("snapshot_id")).isEqualTo(r1);
            assertThat(p.get("revision")).isEqualTo(1);
        });

        // act (8) 정상 1회 비교 — r2
        String r2 = RankingTestSupport.snapshotId(PERIOD, 2);
        JobExecution clean = jobLauncher.run(job, RankingTestSupport.params(PERIOD, 2));
        print("clean-r2", clean, stepFromDb(clean, WeeklyScoreStepComponents.STEP_NAME));

        // assert (8)
        assertThat(clean.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(clean.getJobInstance().getId()).isNotEqualTo(first.getJobInstance().getId());
        Map<String, Object> rev2 = support.revision(r2);
        assertThat(rev2.get("input_checksum")).isEqualTo(rev1Done.get("input_checksum"));
        assertThat(rev2.get("result_checksum")).isEqualTo(rev1Done.get("result_checksum"));
        assertThat(support.scores(r2)).usingRecursiveComparison().isEqualTo(support.scores(r1));
        assertThat(support.publication(PERIOD)).singleElement().satisfies(p -> {
            assertThat(p.get("snapshot_id")).isEqualTo(r2);
            assertThat(p.get("revision")).isEqualTo(2);
        });
        // R7: r1 행은 그대로
        assertThat(support.countInputs(r1)).isEqualTo(4);
        assertThat(support.scores(r1)).hasSize(3);
        assertThat(support.revision(r1).get("status")).isEqualTo("COMPLETED");
    }

    private StepExecution stepFromDb(JobExecution execution, String stepName) {
        StepExecution inMemory = execution.getStepExecutions().stream()
            .filter(s -> s.getStepName().equals(stepName)).findFirst().orElseThrow();
        return jobExplorer.getStepExecution(execution.getId(), inMemory.getId());
    }

    private static void print(String label, JobExecution job, StepExecution step) {
        System.out.println("[restart:" + label + "] jobInstanceId=" + job.getJobInstance().getId()
            + " jobExecutionId=" + job.getId() + " jobStatus=" + job.getStatus()
            + " stepExecutionId=" + step.getId() + " step=" + step.getStepName() + " stepStatus=" + step.getStatus()
            + " read=" + step.getReadCount() + " write=" + step.getWriteCount()
            + " commit=" + step.getCommitCount() + " rollback=" + step.getRollbackCount()
            + " steps=" + job.getStepExecutions().stream().map(s -> s.getStepName() + "#" + s.getId()).toList());
        List.copyOf(step.getExecutionContext().entrySet())
            .forEach(e -> System.out.println("[restart:" + label + "] ctx " + e.getKey() + " = " + e.getValue()));
    }
}
