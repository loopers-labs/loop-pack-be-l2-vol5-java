package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.ProductScore;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingStore;
import com.loopers.batch.job.ranking.step.SnapshotProductPagingReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.loopers.job.ranking.PurchaseActivityFixture.purchase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * 과제 6절 입력(상품 7·8·9, chunk=2)으로 같은 JobInstance의 실패 → 재시작을 확인한다.
 * 실패 스위치는 job parameter가 아니라 테스트의 spy이며, 두 실행 사이에 입력·stage·Batch metadata를 지우지 않는다.
 */
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "commerce.ranking.weekly.chunk-size=2"
})
class RankingJobRestartTest {

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.FREEZE_STEP)
    private Step freezeStep;

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

    @DisplayName("상품 9를 저장하기 직전에 실패하면, 같은 parameter의 재실행은 같은 JobInstance에서 입력을 다시 만들지 않고 상품 9부터 이어 간다.")
    @Test
    void restartsFromLastCommittedProductWithFrozenInput() throws Exception {
        activities.insert(
            purchase(42, 7, 2, "2026-08-17T01:00:00"),
            purchase(42, 8, 1, "2026-08-17T01:00:00"),
            purchase(43, 7, 3, "2026-08-18T01:00:00"),
            purchase(44, 9, 5, "2026-08-19T01:00:00"));
        String snapshotId = "weekly-20260817-r1";
        JobParameters params = params("2026-08-17", snapshotId);
        assertThat(jobExplorer.getJobInstance(WeeklyRankingJobConfig.JOB_NAME, params)).isNull();

        failOnceBeforeWritingProductNine();

        // 첫 실행: 입력 고정 완료, 계산은 상품 7·8 chunk만 commit
        JobExecution first = jobLauncher.run(job, params);

        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(reloadStep(first, WeeklyRankingJobConfig.FREEZE_STEP).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        StepExecution firstScore = reloadStep(first, WeeklyRankingJobConfig.SCORE_STEP);
        assertThat(firstScore.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(firstScore.getCommitCount()).isEqualTo(1);
        assertThat(firstScore.getExecutionContext().getInt(WeeklyRankingJobConfig.PRODUCT_READER + ".read.count"))
            .isEqualTo(2);
        assertThat(firstScore.getExecutionContext().get(WeeklyRankingJobConfig.PRODUCT_READER + ".start.after"))
            .isEqualTo(Map.of("product_id", 8L));
        assertThat(stageScores(snapshotId)).containsExactly(Map.entry(7L, 5L), Map.entry(8L, 1L));
        var manifest = store.findSnapshot(snapshotId).orElseThrow();
        assertThat(manifest.inputCount()).isEqualTo(4);
        assertThat(manifest.productCount()).isEqualTo(3);
        assertThat(manifest.inputChecksum()).isEqualTo("3e90276ec1f9123f308cd18d155e1dde6bf811ef3aa741105ff5c766c357a298");
        assertThat(publishedSnapshot("2026-08-17")).isNull();  // 계산 실패는 아무것도 공개하지 않는다
        assertThat(entries(snapshotId)).isEmpty();

        // 재시작 전에 같은 주의 늦은 구매가 원본에 도착한다. 고정한 입력에는 들어가지 않아야 한다.
        activities.insert(purchase(45, 9, 2, "2026-08-20T10:00:00"));

        // 재실행: 같은 식별 parameter, 같은 DB metadata
        JobExecution second = jobLauncher.run(job, params);

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getStepExecutions()).extracting(StepExecution::getStepName)
            .containsExactly(WeeklyRankingJobConfig.SCORE_STEP, WeeklyRankingJobConfig.PUBLISH_STEP);
        StepExecution secondScore = reloadStep(second, WeeklyRankingJobConfig.SCORE_STEP);
        assertThat(secondScore.getReadCount()).isEqualTo(1);
        assertThat(secondScore.getWriteCount()).isEqualTo(1);
        assertThat(stageScores(snapshotId)).containsExactly(Map.entry(7L, 5L), Map.entry(8L, 1L), Map.entry(9L, 5L));
        assertThat(store.summarizeInputs(snapshotId))
            .isEqualTo(new WeeklyRankingStore.InputSummary(4, 3, manifest.inputChecksum()));
        assertThat(jobExplorer.getJobExecutions(first.getJobInstance())).hasSize(2);

        // 공개: 손으로 계산한 기대 점수·순위(7 → 9 → 8)와 결과 checksum
        assertThat(entries(snapshotId)).containsExactly("7:5:1", "9:5:2", "8:1:3");
        assertThat(publishedSnapshot("2026-08-17")).isEqualTo(snapshotId);
        String restartedChecksum = store.findSnapshot(snapshotId).orElseThrow().resultChecksum();
        assertThat(restartedChecksum).isEqualTo("745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2");

        // 비교용 정상 실행: 다른 주(별도 snapshot)에 같은 구매 구성을 넣고 실패 없이 한 번에 실행한다.
        // 재시작 대상의 stage·metadata는 건드리지 않는다.
        activities.insert(
            purchase(542, 7, 2, "2027-01-11T01:00:00"),
            purchase(542, 8, 1, "2027-01-11T01:00:00"),
            purchase(543, 7, 3, "2027-01-12T01:00:00"),
            purchase(544, 9, 5, "2027-01-13T01:00:00"));
        JobExecution normal = jobLauncher.run(job, params("2027-01-11", "weekly-20270111-r1"));

        assertThat(normal.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jobExplorer.getJobExecutions(normal.getJobInstance())).hasSize(1);
        assertThat(entries("weekly-20270111-r1")).containsExactly("7:5:1", "9:5:2", "8:1:3");
        assertThat(store.findSnapshot("weekly-20270111-r1").orElseThrow().resultChecksum()).isEqualTo(restartedChecksum);

        // 완료된 같은 작업은 다시 실행하지 않는다.
        assertThatThrownBy(() -> jobLauncher.run(job, params)).isInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    @DisplayName("writer가 상품 9를 쓴 뒤 commit 전에 실패하면 그 chunk의 점수와 reader 위치가 함께 되돌아가고, 재시작이 다시 쓴다.")
    @Test
    void writerFailureRollsBackScoresWithCheckpoint() throws Exception {
        activities.insert(
            purchase(142, 7, 2, "2026-07-06T01:00:00"),
            purchase(142, 8, 1, "2026-07-06T01:00:00"),
            purchase(143, 7, 3, "2026-07-07T01:00:00"),
            purchase(144, 9, 5, "2026-07-08T01:00:00"));
        String snapshotId = "weekly-20260706-r1";
        JobParameters params = params("2026-07-06", snapshotId);

        AtomicBoolean failed = new AtomicBoolean();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            List<ProductScore> scores = invocation.getArgument(1);
            if (scores.stream().anyMatch(s -> s.productId() == 9L) && failed.compareAndSet(false, true)) {
                throw new IllegalStateException("상품 9 점수를 쓴 뒤 commit 전 실패");
            }
            return null;
        }).when(store).insertScores(anyString(), anyList());

        JobExecution first = jobLauncher.run(job, params);

        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        StepExecution firstScore = reloadStep(first, WeeklyRankingJobConfig.SCORE_STEP);
        assertThat(firstScore.getExecutionContext().getInt(WeeklyRankingJobConfig.PRODUCT_READER + ".read.count"))
            .isEqualTo(2);
        assertThat(stageScores(snapshotId)).containsExactly(Map.entry(7L, 5L), Map.entry(8L, 1L));

        JobExecution second = jobLauncher.run(job, params);

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(stageScores(snapshotId)).containsExactly(Map.entry(7L, 5L), Map.entry(8L, 1L), Map.entry(9L, 5L));
    }

    @DisplayName("입력 고정 복사는 READ COMMITTED TX에서, 점수 저장은 기본 REPEATABLE READ TX에서 실행된다.")
    @Test
    void freezeRunsInReadCommitted() throws Exception {
        activities.insert(purchase(242, 7, 1, "2026-12-08T01:00:00"));
        List<String> isolations = new ArrayList<>();
        // 같은 스레드의 JdbcTemplate은 Step TX에 묶인 연결을 쓰므로, 그 연결의 세션 격리 수준을 읽는다.
        doAnswer(invocation -> {
            isolations.add("copy=" + jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
            return invocation.callRealMethod();
        }).when(store).copyInputs(any());
        doAnswer(invocation -> {
            isolations.add("score=" + jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
            return invocation.callRealMethod();
        }).when(store).insertScores(anyString(), anyList());

        JobExecution execution = jobLauncher.run(job, params("2026-12-07", "weekly-20261207-r1"));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(isolations).containsExactly("copy=READ-COMMITTED", "score=REPEATABLE-READ");
    }

    @DisplayName("입력을 복사한 뒤 snapshot 정보 저장 전에 실패하면 반쪽 snapshot이 남지 않고, 같은 parameter의 재실행이 처음부터 다시 고정한다.")
    @Test
    void freezeFailureLeavesNoPartialSnapshot() throws Exception {
        activities.insert(
            purchase(342, 7, 2, "2026-12-22T01:00:00"),
            purchase(343, 8, 1, "2026-12-23T01:00:00"));
        String snapshotId = "weekly-20261221-r1";
        JobParameters params = params("2026-12-21", snapshotId);

        AtomicBoolean failed = new AtomicBoolean();
        doAnswer(invocation -> {
            if (failed.compareAndSet(false, true)) {
                throw new IllegalStateException("복사 뒤 snapshot 정보 저장 전 실패");
            }
            return invocation.callRealMethod();
        }).when(store).insertSnapshot(any(), any(), any());

        JobExecution first = jobLauncher.run(job, params);

        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(reloadStep(first, WeeklyRankingJobConfig.FREEZE_STEP).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(first.getStepExecutions()).extracting(StepExecution::getStepName)
            .containsExactly(WeeklyRankingJobConfig.FREEZE_STEP);
        assertThat(store.findSnapshot(snapshotId)).isEmpty();
        assertThat(inputRows(snapshotId)).isZero();  // 복사한 행도 함께 rollback
        assertThat(stageScores(snapshotId)).isEmpty();

        // 아직 고정되지 않은 입력이라, 재실행 전에 도착한 같은 주 구매는 다시 고정할 때 들어간다(ADR 0081).
        activities.insert(purchase(344, 9, 4, "2026-12-24T01:00:00"));

        JobExecution second = jobLauncher.run(job, params);

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(second.getStepExecutions()).extracting(StepExecution::getStepName)
            .containsExactly(WeeklyRankingJobConfig.FREEZE_STEP, WeeklyRankingJobConfig.SCORE_STEP,
                WeeklyRankingJobConfig.PUBLISH_STEP);
        assertThat(store.findSnapshot(snapshotId).orElseThrow().inputCount()).isEqualTo(3);
        assertThat(stageScores(snapshotId)).containsExactly(Map.entry(7L, 2L), Map.entry(8L, 1L), Map.entry(9L, 4L));
    }

    @DisplayName("같은 고정 입력으로 실패·재시작한 r1과, 원본이 그대로일 때 실패 없이 한 번에 실행한 r2는 입력·결과 checksum과 점수·순위가 같다.")
    @Test
    void restartedAndUninterruptedRunsOnSameInputProduceSameResult() throws Exception {
        activities.insert(
            purchase(842, 7, 2, "2027-08-02T01:00:00"),
            purchase(842, 8, 1, "2027-08-02T01:00:00"),
            purchase(843, 7, 3, "2027-08-03T01:00:00"),
            purchase(844, 9, 5, "2027-08-04T01:00:00"));
        failOnceBeforeWritingProductNine();

        // r1: 상품 9 저장 직전 실패 → 같은 parameter로 재시작
        JobParameters r1 = params("2027-08-02", "weekly-20270802-r1");
        assertThat(jobLauncher.run(job, r1).getStatus()).isEqualTo(BatchStatus.FAILED);
        JobExecution restarted = jobLauncher.run(job, r1);
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(reloadStep(restarted, WeeklyRankingJobConfig.SCORE_STEP).getReadCount()).isEqualTo(1);

        // r2: 원본에 구매를 더 넣지 않고 실패 없이 한 번에 실행 → r1과 같은 입력을 새로 고정한다
        JobExecution uninterrupted = jobLauncher.run(job, params("2027-08-02", "weekly-20270802-r2"));
        assertThat(uninterrupted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jobExplorer.getJobExecutions(uninterrupted.getJobInstance())).hasSize(1);

        var restartedManifest = store.findSnapshot("weekly-20270802-r1").orElseThrow();
        var uninterruptedManifest = store.findSnapshot("weekly-20270802-r2").orElseThrow();
        assertThat(uninterruptedManifest.inputSummary()).isEqualTo(restartedManifest.inputSummary());
        assertThat(uninterruptedManifest.resultChecksum()).isEqualTo(restartedManifest.resultChecksum());
        assertThat(entries("weekly-20270802-r1")).containsExactly("7:5:1", "9:5:2", "8:1:3");
        assertThat(entries("weekly-20270802-r2")).containsExactly("7:5:1", "9:5:2", "8:1:3");
        // 결과 checksum은 실행 ID·날짜 없이 순위 순서의 productId·score·rank라 과제 6절 기대값과도 같다
        assertThat(restartedManifest.resultChecksum())
            .isEqualTo("745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2");
    }

    @DisplayName("chunk 크기를 바꾼 채 재시작하면 읽기 전에 실패하고, 원래 크기로 다시 실행하면 이어서 끝난다.")
    @Test
    void rejectsRestartWithDifferentChunkSize() throws Exception {
        String periodStart = "2027-10-18";
        activities.insert(
            purchase(962, 7, 2, periodStart + "T01:00:00"),
            purchase(962, 8, 1, periodStart + "T01:00:00"),
            purchase(963, 7, 3, periodStart + "T01:00:00"),
            purchase(964, 9, 5, periodStart + "T01:00:00"));
        String snapshotId = "weekly-20271018-r1";
        JobParameters params = params(periodStart, snapshotId);
        failOnceBeforeWritingProductNine();
        assertThat(jobLauncher.run(job, params).getStatus()).isEqualTo(BatchStatus.FAILED);

        var biggerPages = new SnapshotProductPagingReader(WeeklyRankingJobConfig.PRODUCT_READER, dataSource, snapshotId, 3);
        biggerPages.afterPropertiesSet();
        Step biggerChunk = new StepBuilder(WeeklyRankingJobConfig.SCORE_STEP, jobRepository)
            .<Long, Long>chunk(3, transactionManager)
            .reader(biggerPages)
            .writer(chunk -> {
                throw new IllegalStateException("읽기 전에 실패해야 한다");
            })
            .build();
        JobExecution changed = jobLauncher.run(
            new JobBuilder(WeeklyRankingJobConfig.JOB_NAME, jobRepository).start(freezeStep).next(biggerChunk).build(), params);

        assertThat(changed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(changed.getAllFailureExceptions())
            .anyMatch(e -> String.valueOf(e.getMessage()).contains("원래 chunk 크기로"));
        assertThat(stageScores(snapshotId)).containsExactly(Map.entry(7L, 5L), Map.entry(8L, 1L));

        JobExecution resumed = jobLauncher.run(job, params);

        assertThat(resumed.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(reloadStep(resumed, WeeklyRankingJobConfig.SCORE_STEP).getReadCount()).isEqualTo(1);
        assertThat(entries(snapshotId)).containsExactly("7:5:1", "9:5:2", "8:1:3");
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

    private List<String> entries(String snapshotId) {
        return jdbc.query(
            "SELECT product_id, score, ranking FROM ranking_entry WHERE snapshot_id = ? ORDER BY ranking",
            (rs, n) -> rs.getLong("product_id") + ":" + rs.getLong("score") + ":" + rs.getInt("ranking"),
            snapshotId);
    }

    private String publishedSnapshot(String periodStart) {
        return jdbc.query("SELECT snapshot_id FROM ranking_publication WHERE period_start = ?",
            (rs, n) -> rs.getString("snapshot_id"), periodStart).stream().findFirst().orElse(null);
    }

    private int inputRows(String snapshotId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM ranking_snapshot_input WHERE snapshot_id = ?", Integer.class, snapshotId);
    }

    private static JobParameters params(String periodStart, String snapshotId) {
        return new JobParametersBuilder()
            .addString("periodStart", periodStart, true)
            .addString("snapshotId", snapshotId, true)
            .toJobParameters();
    }

    /** 메모리의 실행 객체가 아니라 JobRepository에 저장된 StepExecution을 다시 읽는다. */
    private StepExecution reloadStep(JobExecution execution, String stepName) {
        StepExecution inMemory = execution.getStepExecutions().stream()
            .filter(step -> step.getStepName().equals(stepName))
            .findFirst()
            .orElseThrow();
        return jobExplorer.getStepExecution(execution.getId(), inMemory.getId());
    }

    /** 배치와 별도 연결로 commit된 stage 점수만 읽는다. */
    private List<Map.Entry<Long, Long>> stageScores(String snapshotId) {
        return jdbc.query(
            "SELECT product_id, score FROM ranking_score_stage WHERE snapshot_id = ? ORDER BY product_id",
            (rs, n) -> Map.entry(rs.getLong("product_id"), rs.getLong("score")),
            snapshotId);
    }
}
