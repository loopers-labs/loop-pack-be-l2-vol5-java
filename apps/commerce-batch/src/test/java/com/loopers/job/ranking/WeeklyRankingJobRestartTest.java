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
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * weeklyRanking Job 의 재시작 검증 (과제 §6 7단계). period 2026-08-17, fixture orderId 42·43·44 그대로라 기대 checksum 과 비교한다.
 * 실패 지점은 JobParameters 가 아니라 {@link RankingTestConfig.FailOnceProcessListener} 으로 만든다.
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
    private final RankingTestConfig.FailOnceProcessListener hook;
    private final JdbcTemplate jdbcTemplate;
    private final RankingTestSupport support;

    @Autowired
    WeeklyRankingJobRestartTest(
        @Qualifier(WeeklyRankingJobConfig.JOB_NAME) Job job,
        JobLauncher jobLauncher,
        JobExplorer jobExplorer,
        RankingTestConfig.FailOnceProcessListener hook,
        JdbcTemplate jdbcTemplate
    ) {
        this.job = job;
        this.jobLauncher = jobLauncher;
        this.jobExplorer = jobExplorer;
        this.hook = hook;
        this.jdbcTemplate = jdbcTemplate;
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

    @DisplayName("snapshotId 의 날짜가 periodStart 와 다르거나 revision 이 0 이면 Job 시작 전에 거절되고 JobInstance 가 생기지 않는다.")
    @Test
    void rejectsInvalidParametersBeforeCreatingInstance() throws Exception {
        long instancesBefore = jobExplorer.getJobInstanceCount(WeeklyRankingJobConfig.JOB_NAME);
        JobParameters mismatched = new JobParametersBuilder()
            .addString("periodStart", "2026-08-17", true).addString("snapshotId", "weekly-20260818-r1", true).toJobParameters();
        JobParameters revisionZero = new JobParametersBuilder()
            .addString("periodStart", "2026-08-17", true).addString("snapshotId", "weekly-20260817-r0", true).toJobParameters();

        assertThatThrownBy(() -> jobLauncher.run(job, mismatched)).isInstanceOf(JobParametersInvalidException.class)
            .hasMessageContaining("weekly-20260818-r1").hasMessageContaining("2026-08-17");
        assertThatThrownBy(() -> jobLauncher.run(job, revisionZero)).isInstanceOf(JobParametersInvalidException.class)
            .hasMessageContaining("weekly-20260817-r0");
        assertThat(jobExplorer.getJobInstanceCount(WeeklyRankingJobConfig.JOB_NAME)).isEqualTo(instancesBefore);
    }

    @DisplayName("정상 1회 실행 r2 를 기준으로, 상품 9 직전에 실패한 r1 을 늦은 구매가 도착한 뒤 같은 파라미터로 재시작해도 입력·checksum·결과가 불변이고(늦은 행 미반영), 새 revision r3 에서만 반영된다.")
    @Test
    void restartsFromCheckpointAndMatchesCleanRun() throws Exception {
        // arrange
        support.insertFixture(PERIOD, 0);

        // act (0) 비교용 정상 1회 실행 r2 — 늦은 행이 섞이지 않게 먼저 돌린다
        String r2 = RankingTestSupport.snapshotId(PERIOD, 2);
        JobExecution clean = jobLauncher.run(job, RankingTestSupport.params(PERIOD, 2));
        print("clean-r2", clean, stepFromDb(clean, WeeklyScoreStepComponents.STEP_NAME));
        assertThat(clean.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> rev2 = support.revision(r2);
        List<Map<String, Object>> scores2 = support.scores(r2);
        assertThat(rev2.get("input_checksum")).isEqualTo(RankingTestSupport.FIXTURE_INPUT_CHECKSUM);
        assertThat(rev2.get("result_checksum")).isEqualTo(RankingTestSupport.FIXTURE_RESULT_CHECKSUM);

        // act (1) r1 첫 실행 — 상품 9 직전 실패
        String r1 = RankingTestSupport.snapshotId(PERIOD, 1);
        JobParameters r1Params = RankingTestSupport.params(PERIOD, 1);
        hook.armFailOnce(9L);
        JobExecution first = jobLauncher.run(job, r1Params);

        // assert (2)~(4): Step 1 commit, Step 2 첫 chunk 만 commit, pointer 는 r2 그대로
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
        assertThat(support.publication(PERIOD)).singleElement().satisfies(p -> assertThat(p.get("snapshot_id")).isEqualTo(r2));

        // arrange — 실패와 재시작 사이에 같은 주의 늦은 구매 1행이 도착한다 (상품 8 × 4)
        support.insertActivity(45, 8, 4, RankingTestSupport.utc("2026-08-19T02:00:00Z"), null);

        // act (5) 같은 파라미터로 재시작
        JobExecution second = jobLauncher.run(job, r1Params);

        // assert (5)(6): 같은 instance·다른 execution, Step 1 재실행 없음, 상품 9 만 처리
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getId()).isEqualTo(first.getJobInstance().getId());
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getStepExecutions()).extracting(StepExecution::getStepName)
            .containsExactly(WeeklyScoreStepComponents.STEP_NAME, OpenWeeklyRankingTasklet.STEP_NAME);
        StepExecution secondScoreStep = stepFromDb(second, WeeklyScoreStepComponents.STEP_NAME);
        print("second", second, secondScoreStep);
        assertThat(secondScoreStep.getReadCount()).isEqualTo(1);
        assertThat(secondScoreStep.getWriteCount()).isEqualTo(1);

        // assert (보강 1) 입력 불변: r1 은 여전히 4행, 늦은 주문 45 는 없다
        assertThat(support.countInputs(r1)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM weekly_ranking_inputs WHERE snapshot_id = ? AND order_id = 45", Integer.class, r1)).isZero();

        // assert (보강 2)(3) checksum·결과 불변, r2 와 동일. 상품 8 은 여전히 1점
        Map<String, Object> rev1Done = support.revision(r1);
        assertThat(rev1Done.get("status")).isEqualTo("COMPLETED");
        assertThat(rev1Done.get("input_checksum")).isEqualTo(RankingTestSupport.FIXTURE_INPUT_CHECKSUM).isEqualTo(rev1.get("input_checksum"));
        assertThat(rev1Done.get("result_checksum")).isEqualTo(RankingTestSupport.FIXTURE_RESULT_CHECKSUM).isEqualTo(rev2.get("result_checksum"));
        assertThat(support.scores(r1)).extracting(m -> m.get("product_id"), m -> m.get("score"), m -> m.get("ranking"))
            .containsExactly(tuple(7L, 5L, 1), tuple(8L, 1L, 3), tuple(9L, 5L, 2));
        assertThat(support.scores(r1)).usingRecursiveComparison().isEqualTo(scores2);
        // pointer: r2(revision 2) 가 이미 공개돼 r1 은 거절(D8). r2 그대로
        assertThat(support.publication(PERIOD)).singleElement().satisfies(p -> {
            assertThat(p.get("snapshot_id")).isEqualTo(r2);
            assertThat(p.get("revision")).isEqualTo(2);
        });

        // act/assert (보강 4) 대조군 r3: 늦은 행이 반영된다
        String r3 = RankingTestSupport.snapshotId(PERIOD, 3);
        JobExecution late = jobLauncher.run(job, RankingTestSupport.params(PERIOD, 3));
        print("late-r3", late, stepFromDb(late, WeeklyScoreStepComponents.STEP_NAME));
        assertThat(late.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> rev3 = support.revision(r3);
        assertThat(rev3.get("input_count")).isEqualTo(5);
        assertThat(rev3.get("input_checksum")).isNotEqualTo(rev1Done.get("input_checksum"));
        assertThat(rev3.get("result_checksum")).isNotEqualTo(rev1Done.get("result_checksum"));
        assertThat(support.scores(r3)).extracting(m -> m.get("product_id"), m -> m.get("score"), m -> m.get("ranking"))
            .containsExactly(tuple(7L, 5L, 1), tuple(8L, 5L, 2), tuple(9L, 5L, 3));
        assertThat(support.publication(PERIOD)).singleElement().satisfies(p -> assertThat(p.get("snapshot_id")).isEqualTo(r3));
        System.out.println("[restart] r1 input=" + rev1Done.get("input_checksum") + " result=" + rev1Done.get("result_checksum")
            + " | r3 input=" + rev3.get("input_checksum") + " result=" + rev3.get("result_checksum"));
        // R7: r1·r2 행은 그대로
        assertThat(support.countInputs(r1)).isEqualTo(4);
        assertThat(support.scores(r1)).hasSize(3);
        assertThat(support.scores(r2)).usingRecursiveComparison().isEqualTo(scores2);
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
