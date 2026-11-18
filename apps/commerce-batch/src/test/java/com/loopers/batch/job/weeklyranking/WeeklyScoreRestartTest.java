package com.loopers.batch.job.weeklyranking;

import com.loopers.batch.domain.ProductTotal;
import com.loopers.batch.fixture.RankingTables;
import com.loopers.batch.fixture.RankingTables.ScoreRow;
import com.loopers.batch.fixture.StuckExecutionRecovery;
import com.loopers.batch.fixture.TestProductPurchaseActivity;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.loopers.batch.fixture.WeeklyRankingFixtures.R1;
import static com.loopers.batch.fixture.WeeklyRankingFixtures.R1_RESULT_CHECKSUM;
import static com.loopers.batch.fixture.WeeklyRankingFixtures.WEEK;
import static com.loopers.batch.fixture.WeeklyRankingFixtures.latePurchase;
import static com.loopers.batch.fixture.WeeklyRankingFixtures.lecturePurchases;
import static com.loopers.batch.fixture.WeeklyRankingFixtures.params;
import static com.loopers.batch.fixture.WeeklyRankingFixtures.stepOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 점수 계산 step의 재시작 (W9 D-4·D-7, 발제 §6). chunk 크기 2: 첫 chunk = 상품 7·8, 둘째 chunk = 상품 9.
 * 실패는 processor가 상품 9를 처리할 때 한 번만 낸다 (테스트에서만, job parameter가 아니다).
 */
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "weekly-ranking.chunk-size=2"
})
@SpringBatchTest
class WeeklyScoreRestartTest {

    private static final String READ_COUNT_KEY = WeeklyRankingJobConfig.SCORE_READER + ".read.count";

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DataSource dataSource;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoSpyBean
    private WeeklyScoreProcessor processor;

    private RankingTables tables;
    private StuckExecutionRecovery recovery;

    @BeforeEach
    void setUp() {
        jobLauncherTestUtils.setJob(job);
        tables = new RankingTables(dataSource);
        recovery = new StuckExecutionRecovery(dataSource, transactionTemplate);
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void 정상_1회_실행은_7_9_8_순위와_정해진_결과_checksum을_낸다() throws Exception {
        // given
        givenPurchases(lecturePurchases());

        // when
        JobExecution execution = jobLauncherTestUtils.launchJob(params(WEEK, 1L));

        // then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(tables.scores(R1)).containsExactly(new ScoreRow(7L, 5L, 1), new ScoreRow(8L, 1L, 3), new ScoreRow(9L, 5L, 2));
        assertThat(tables.resultChecksum(R1)).isEqualTo(R1_RESULT_CHECKSUM);
    }

    @Test
    void 상품_9를_처리하다_실패하면_7_8_점수와_reader_위치_2까지만_commit돼_있다() throws Exception {
        // given
        givenPurchases(lecturePurchases());
        failOnceWhileProcessing(9L);

        // when
        JobExecution first = jobLauncherTestUtils.launchJob(params(WEEK, 1L));

        // then: 첫 chunk(7·8)의 점수와 reader 위치는 같은 TX로 commit됐고, 순위·공개는 없다
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        StepExecution scoreStep = stepOf(first, WeeklyRankingJobConfig.SCORE_STEP);
        assertThat(scoreStep.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(scoreStep.getExecutionContext().getInt(READ_COUNT_KEY)).isEqualTo(2);
        assertThat(tables.scores(R1)).containsExactly(new ScoreRow(7L, 5L, null), new ScoreRow(8L, 1L, null));
        assertThat(tables.publication(WEEK)).isEmpty();
    }

    @Test
    void 같은_parameter로_다시_실행하면_같은_작업의_새_실행이_상품_9부터_이어_가고_정상_실행과_결과가_같다() throws Exception {
        // given: 상품 9에서 실패한 첫 실행
        givenPurchases(lecturePurchases());
        failOnceWhileProcessing(9L);
        JobExecution first = jobLauncherTestUtils.launchJob(params(WEEK, 1L));

        // when
        JobExecution second = jobLauncherTestUtils.launchJob(params(WEEK, 1L));

        // then: 같은 JobInstance, 새 JobExecution. 점수 계산 step은 item 1개(상품 9)만 읽었다
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(second.getId()).isNotEqualTo(first.getId());
        StepExecution resumed = stepOf(second, WeeklyRankingJobConfig.SCORE_STEP);
        assertThat(resumed.getReadCount()).isEqualTo(1L);
        // 끝을 확인하는 마지막 읽기(결과 없음)도 1로 센다: item 3개 + 1 = 4 (Spring Batch 5.2.2 AbstractItemCountingItemStreamItemReader)
        assertThat(resumed.getExecutionContext().getInt(READ_COUNT_KEY)).isEqualTo(4);
        assertThat(tables.scores(R1)).containsExactly(new ScoreRow(7L, 5L, 1), new ScoreRow(8L, 1L, 3), new ScoreRow(9L, 5L, 2));
        assertThat(tables.resultChecksum(R1)).isEqualTo(R1_RESULT_CHECKSUM);
    }

    @Test
    void 강제_종료로_STARTED에_남은_실행이_있으면_재시작이_거절된다() throws Exception {
        // given: 상품 9에서 실패한 실행을 kill -9처럼 아무도 FAILED를 쓰지 못한 상태로 되돌린다
        givenPurchases(lecturePurchases());
        failOnceWhileProcessing(9L);
        JobExecution first = jobLauncherTestUtils.launchJob(params(WEEK, 1L));
        recovery.leaveAsStarted(first.getId());

        // when
        Throwable thrown = catchThrowable(() -> jobLauncherTestUtils.launchJob(params(WEEK, 1L)));

        // then
        assertThat(thrown).isInstanceOf(JobExecutionAlreadyRunningException.class);
    }

    @Test
    void 멈춘_실행을_정리_SQL로_FAILED로_바꾸면_같은_작업이_이어서_끝난다() throws Exception {
        // given: STARTED에 남은 실행
        givenPurchases(lecturePurchases());
        failOnceWhileProcessing(9L);
        JobExecution first = jobLauncherTestUtils.launchJob(params(WEEK, 1L));
        recovery.leaveAsStarted(first.getId());

        // when: 운영 절차의 정리 SQL(runbook/mark-execution-failed.sql)을 돌리고 같은 parameter로 다시 실행한다
        recovery.markFailed(first.getId());
        JobExecution second = jobLauncherTestUtils.launchJob(params(WEEK, 1L));

        // then
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(stepOf(second, WeeklyRankingJobConfig.SCORE_STEP).getReadCount()).isEqualTo(1L);
        assertThat(tables.resultChecksum(R1)).isEqualTo(R1_RESULT_CHECKSUM);
    }

    @Test
    void 정리한_뒤_살아_있던_옛_실행이_chunk를_commit하려_하면_실패하고_그_chunk의_점수는_남지_않는다() throws Exception {
        // given: 옛 실행이 둘째 chunk(상품 9)를 처리하는 도중 멈춰 있다 — 사람은 죽은 줄 알았다
        givenPurchases(lecturePurchases());
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            ProductTotal item = invocation.getArgument(0);
            if (item.productId() == 9L) {
                reached.countDown();
                assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(processor).process(any());
        CompletableFuture<JobExecution> oldRun = CompletableFuture.supplyAsync(() -> {
            try {
                return jobLauncherTestUtils.launchJob(params(WEEK, 1L));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(reached.await(30, TimeUnit.SECONDS)).isTrue();
        long oldExecutionId = latestExecutionId();

        // when: 정리 SQL로 VERSION을 올린 뒤 옛 실행을 풀어 준다
        recovery.markFailed(oldExecutionId);
        release.countDown();
        Throwable oldRunOutcome = catchThrowable(() -> oldRun.get(60, TimeUnit.SECONDS));

        // then: 옛 실행은 COMPLETED로 끝나지 못했고, 상품 9의 점수는 rollback되어 없다. 첫 chunk(7·8)는 그대로다
        if (oldRunOutcome == null) {
            assertThat(oldRun.get().getStatus()).isNotEqualTo(BatchStatus.COMPLETED);
        }
        assertThat(tables.scores(R1)).containsExactly(new ScoreRow(7L, 5L, null), new ScoreRow(8L, 1L, null));
        assertThat(tables.publication(WEEK)).isEmpty();
    }

    @Test
    void 입력_고정_commit_뒤_기록이_UNKNOWN으로_남으면_정리하기_전에는_재시작이_거절된다() throws Exception {
        // given: 입력 고정은 commit됐는데 그 step의 최종 기록 저장이 실패해 UNKNOWN으로 남았다 (D-7 실패 지도 #3)
        givenPurchases(lecturePurchases());
        failOnceWhileProcessing(7L);
        JobExecution first = jobLauncherTestUtils.launchJob(params(WEEK, 1L));
        recovery.leaveAsUnknown(first.getId(), WeeklyRankingJobConfig.INPUT_FREEZE_STEP);

        // when
        Throwable thrown = catchThrowable(() -> jobLauncherTestUtils.launchJob(params(WEEK, 1L)));

        // then
        assertThat(thrown).isInstanceOf(JobRestartException.class);
    }

    @Test
    void 입력_고정_commit_뒤_UNKNOWN으로_남은_작업을_정리하고_재시작하면_입력을_다시_복사하지_않고_끝난다() throws Exception {
        // given: 실패 지도 #3 상태. 그 뒤 원본에 같은 주 늦은 구매가 들어왔다
        givenPurchases(lecturePurchases());
        failOnceWhileProcessing(7L);
        JobExecution first = jobLauncherTestUtils.launchJob(params(WEEK, 1L));
        recovery.leaveAsUnknown(first.getId(), WeeklyRankingJobConfig.INPUT_FREEZE_STEP);
        givenPurchases(latePurchase());

        // when: 정리 SQL로 FAILED로 바꾸고 같은 parameter로 재시작한다
        recovery.markFailed(first.getId());
        JobExecution second = jobLauncherTestUtils.launchJob(params(WEEK, 1L));

        // then: 같은 작업. 입력 고정 step이 다시 돌았지만 "이미 고정됨, 같음"으로 통과해 늦은 구매가 섞이지 않았다
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(second.getStepExecutions()).extracting(StepExecution::getStepName)
            .containsExactly(WeeklyRankingJobConfig.INPUT_FREEZE_STEP, WeeklyRankingJobConfig.SCORE_STEP, WeeklyRankingJobConfig.PUBLISH_STEP);
        assertThat(tables.resultChecksum(R1)).isEqualTo(R1_RESULT_CHECKSUM);
    }

    private void failOnceWhileProcessing(long productId) throws Exception {
        AtomicBoolean failed = new AtomicBoolean(false);
        doAnswer(invocation -> {
            ProductTotal item = invocation.getArgument(0);
            if (item.productId() == productId && failed.compareAndSet(false, true)) {
                throw new IllegalStateException("상품 " + productId + " 처리 중 실패 (테스트)");
            }
            return invocation.callRealMethod();
        }).when(processor).process(any());
    }

    private long latestExecutionId() {
        Long id = new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject("SELECT MAX(JOB_EXECUTION_ID) FROM BATCH_JOB_EXECUTION", Long.class);
        return id == null ? 0 : id;
    }


    private void givenPurchases(TestProductPurchaseActivity... rows) {
        transactionTemplate.executeWithoutResult(status -> Arrays.stream(rows).forEach(entityManager::persist));
    }

    private void cleanUp() {
        jobRepositoryTestUtils.removeJobExecutions();
        databaseCleanUp.truncateAllTables();
    }
}
