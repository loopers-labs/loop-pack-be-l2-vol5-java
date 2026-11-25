package com.loopers.batch.job.weeklyranking;

import com.loopers.batch.domain.ProductTotal;
import com.loopers.batch.fixture.RankingTables;
import com.loopers.batch.fixture.RankingTables.PublicationRow;
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
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static com.loopers.batch.fixture.WeeklyRankingFixtures.R1;
import static com.loopers.batch.fixture.WeeklyRankingFixtures.R1_RESULT_CHECKSUM;
import static com.loopers.batch.fixture.WeeklyRankingFixtures.R2;
import static com.loopers.batch.fixture.WeeklyRankingFixtures.WEEK;
import static com.loopers.batch.fixture.WeeklyRankingFixtures.latePurchase;
import static com.loopers.batch.fixture.WeeklyRankingFixtures.lecturePurchases;
import static com.loopers.batch.fixture.WeeklyRankingFixtures.params;
import static com.loopers.batch.fixture.WeeklyRankingFixtures.stepOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * 공개 step (W9 D-5, 발제 §5). 완성본만 공개하고, 실패하면 이전 공개본이 그대로 보이며, 더 새 revision을 덮지 않는다.
 */
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "weekly-ranking.chunk-size=2"
})
@SpringBatchTest
class PublishStepTest {

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

    @MockitoSpyBean
    private RankingPublishJdbcRepository publishRepository;

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
    void 공개하면_순위가_매겨지고_snapshot이_COMPLETED가_되며_pointer가_그_snapshot을_가리킨다() throws Exception {
        // given
        givenPurchases(lecturePurchases());

        // when
        JobExecution execution = jobLauncherTestUtils.launchJob(params(WEEK, 1L));

        // then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        assertThat(tables.scores(R1)).containsExactly(new ScoreRow(7L, 5L, 1), new ScoreRow(8L, 1L, 3), new ScoreRow(9L, 5L, 2));
        assertThat(tables.snapshotStatus(R1)).contains("COMPLETED");
        assertThat(tables.publication(WEEK)).get().extracting(PublicationRow::snapshotId, PublicationRow::revision).containsExactly(R1, 1L);
    }

    @Test
    void 새_revision의_점수_계산이_실패해도_이전_공개본이_그대로_보인다() throws Exception {
        // given: r1 공개 뒤 늦은 구매가 들어왔다
        givenPurchases(lecturePurchases());
        jobLauncherTestUtils.launchJob(params(WEEK, 1L));
        givenPurchases(latePurchase());
        failOnceWhileProcessing(9L);

        // when: r2 작업이 점수 계산 중에 실패한다
        JobExecution r2 = jobLauncherTestUtils.launchJob(params(WEEK, 2L));

        // then: pointer는 r1 그대로, r2 행은 순위가 없다
        assertThat(r2.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(tables.publication(WEEK)).get().extracting(PublicationRow::snapshotId).isEqualTo(R1);
        assertThat(tables.scores(R2)).extracting(ScoreRow::rankNo).containsOnlyNulls();
        assertThat(tables.snapshotStatus(R2)).contains("FROZEN");
    }

    @Test
    void 공개_step의_TX가_실패하면_순위_상태_pointer가_모두_rollback되어_이전_공개본이_남는다() throws Exception {
        // given: r1 공개 뒤 늦은 구매 → r2 작업의 공개 step이 pointer를 바꾸는 순간 실패한다
        givenPurchases(lecturePurchases());
        jobLauncherTestUtils.launchJob(params(WEEK, 1L));
        givenPurchases(latePurchase());
        doThrow(new IllegalStateException("pointer 교체 실패 (테스트)"))
            .when(publishRepository).updatePublication(any(), any(), anyLong(), any());

        // when
        JobExecution r2 = jobLauncherTestUtils.launchJob(params(WEEK, 2L));

        // then: 같은 TX에서 먼저 한 순위 매기기·COMPLETED도 함께 rollback되었다
        assertThat(r2.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(stepOf(r2, WeeklyRankingJobConfig.PUBLISH_STEP).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(tables.publication(WEEK)).get().extracting(PublicationRow::snapshotId).isEqualTo(R1);
        assertThat(tables.scores(R2)).extracting(ScoreRow::rankNo).containsOnlyNulls();
        assertThat(tables.snapshotStatus(R2)).contains("FROZEN");
    }

    @Test
    void 공개_step이_실패한_작업을_재시작하면_공개_step만_다시_돌아_새_revision이_공개된다() throws Exception {
        // given: r2 작업의 공개 step이 한 번 실패했다
        givenPurchases(lecturePurchases());
        jobLauncherTestUtils.launchJob(params(WEEK, 1L));
        givenPurchases(latePurchase());
        AtomicBoolean failed = new AtomicBoolean(false);
        doAnswer(invocation -> {
            if (failed.compareAndSet(false, true)) {
                throw new IllegalStateException("pointer 교체 실패 (테스트)");
            }
            return invocation.callRealMethod();
        }).when(publishRepository).updatePublication(any(), any(), anyLong(), any());
        jobLauncherTestUtils.launchJob(params(WEEK, 2L));

        // when
        JobExecution restarted = jobLauncherTestUtils.launchJob(params(WEEK, 2L));

        // then: 끝난 step(입력 고정·점수 계산)은 건너뛰고 공개 step만 돌았다. r2에서는 상품 8 = 11점·1위
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted.getStepExecutions()).extracting(StepExecution::getStepName).containsExactly(WeeklyRankingJobConfig.PUBLISH_STEP);
        assertThat(tables.publication(WEEK)).get().extracting(PublicationRow::snapshotId, PublicationRow::revision).containsExactly(R2, 2L);
        assertThat(tables.scores(R2)).containsExactly(new ScoreRow(7L, 5L, 2), new ScoreRow(8L, 11L, 1), new ScoreRow(9L, 5L, 3));
    }

    @Test
    void 공개_commit_뒤_기록만_UNKNOWN으로_남은_작업을_정리하고_재시작해도_같은_공개로_수렴한다() throws Exception {
        // given: r1 공개는 commit됐는데 최종 실행 기록 저장이 실패해 UNKNOWN으로 남았다 (D-7 실패 지도 #9)
        givenPurchases(lecturePurchases());
        JobExecution first = jobLauncherTestUtils.launchJob(params(WEEK, 1L));
        PublicationRow published = tables.publication(WEEK).orElseThrow();
        recovery.leaveAsUnknown(first.getId(), WeeklyRankingJobConfig.PUBLISH_STEP);

        // when: 사람이 공개 상태를 확인하고 정리 SQL을 돌린 뒤 같은 parameter로 재시작한다
        recovery.markFailed(first.getId());
        JobExecution restarted = jobLauncherTestUtils.launchJob(params(WEEK, 1L));

        // then: 공개 step만 다시 돌았고, pointer·순위·결과 checksum이 그대로다 (정상 공개본을 지우지 않는다)
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted.getStepExecutions()).extracting(StepExecution::getStepName).containsExactly(WeeklyRankingJobConfig.PUBLISH_STEP);
        assertThat(tables.publication(WEEK)).contains(published);
        assertThat(tables.resultChecksum(R1)).isEqualTo(R1_RESULT_CHECKSUM);
    }

    @Test
    void 더_새_revision이_공개된_뒤_옛_작업을_재시작하면_pointer를_바꾸지_않고_REPLACED로_끝난다() throws Exception {
        // given: r1 작업이 점수 계산 중 실패 → 늦은 구매 → r2 작업이 공개됐다
        givenPurchases(lecturePurchases());
        failOnceWhileProcessing(9L);
        jobLauncherTestUtils.launchJob(params(WEEK, 1L));
        givenPurchases(latePurchase());
        jobLauncherTestUtils.launchJob(params(WEEK, 2L));

        // when: 누군가 r1 작업을 재시작한다
        JobExecution r1Restarted = jobLauncherTestUtils.launchJob(params(WEEK, 1L));

        // then: r1은 완성본이 되지만 pointer는 r2 그대로다
        assertThat(r1Restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(r1Restarted.getExitStatus().getExitCode()).isEqualTo(PublishTasklet.REPLACED);
        assertThat(tables.publication(WEEK)).get().extracting(PublicationRow::snapshotId, PublicationRow::revision).containsExactly(R2, 2L);
        assertThat(tables.snapshotStatus(R1)).contains("COMPLETED");
        assertThat(tables.resultChecksum(R1)).isEqualTo(R1_RESULT_CHECKSUM);
    }

    @Test
    void 점수_행이_고정_입력의_상품_수보다_적으면_공개하지_않고_실패한다() throws Exception {
        // given: 점수 계산 step이 상품 9 전에 실패해 점수 행이 2개(7·8)뿐이다
        givenPurchases(lecturePurchases());
        failOnceWhileProcessing(9L);
        jobLauncherTestUtils.launchJob(params(WEEK, 1L));

        // when: 공개 step만 따로 돌린다 (재시작 없이 공개 조건만 본다)
        JobExecution publishOnly = jobLauncherTestUtils.launchStep(WeeklyRankingJobConfig.PUBLISH_STEP,
            new JobParametersBuilder(params(WEEK, 1L)).addString("test.run", "publish-only").toJobParameters());

        // then
        assertThat(publishOnly.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(publishOnly.getAllFailureExceptions()).extracting(Throwable::getMessage)
            .anySatisfy(message -> assertThat(message).contains("점수 행 = 2, 상품 수 = 3"));
        assertThat(tables.publication(WEEK)).isEmpty();
        assertThat(tables.scores(R1)).extracting(ScoreRow::rankNo).containsOnlyNulls();
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


    private void givenPurchases(TestProductPurchaseActivity... rows) {
        transactionTemplate.executeWithoutResult(status -> Arrays.stream(rows).forEach(entityManager::persist));
    }

    private void cleanUp() {
        jobRepositoryTestUtils.removeJobExecutions();
        databaseCleanUp.truncateAllTables();
    }
}
