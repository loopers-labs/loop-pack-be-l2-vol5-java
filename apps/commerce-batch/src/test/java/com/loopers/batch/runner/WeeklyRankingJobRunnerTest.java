package com.loopers.batch.runner;

import com.loopers.batch.domain.ProductTotal;
import com.loopers.batch.fixture.RankingTables;
import com.loopers.batch.fixture.RankingTables.PublicationRow;
import com.loopers.batch.fixture.RankingTables.ScoreRow;
import com.loopers.batch.fixture.StuckExecutionRecovery;
import com.loopers.batch.fixture.TestProductPurchaseActivity;
import com.loopers.batch.job.weeklyranking.RankingSnapshotJdbcRepository;
import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.weeklyranking.WeeklyScoreProcessor;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.loopers.batch.fixture.WeeklyRankingFixtures.R1;
import static com.loopers.batch.fixture.WeeklyRankingFixtures.R1_RESULT_CHECKSUM;
import static com.loopers.batch.fixture.WeeklyRankingFixtures.R2;
import static com.loopers.batch.fixture.WeeklyRankingFixtures.WEEK;
import static com.loopers.batch.fixture.WeeklyRankingFixtures.latePurchase;
import static com.loopers.batch.fixture.WeeklyRankingFixtures.lecturePurchases;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 실행기가 부르는 명령의 입구 (W9 D-8). 사람은 revision 번호를 넣지 않는다 — periodStart와 "재계산인가"만 준다.
 * 테스트는 자동 실행을 끄므로 runner를 직접 만들어 명령 인자 그대로 run(...)을 부른다.
 */
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "weekly-ranking.chunk-size=2"
})
@SpringBatchTest
class WeeklyRankingJobRunnerTest {

    private static final String COMMAND = "periodStart=2026-08-17";

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    @Autowired
    private WeeklyRankingRevisionResolver revisionResolver;

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
    private RankingSnapshotJdbcRepository snapshotRepository;

    private RankingTables tables;
    private StuckExecutionRecovery recovery;

    @BeforeEach
    void setUp() {
        tables = new RankingTables(dataSource);
        recovery = new StuckExecutionRecovery(dataSource, transactionTemplate);
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void 명령에_periodStart만_주면_그_주_r1_작업을_돌려_공개한다() throws Exception {
        // given
        givenPurchases(lecturePurchases());

        // when
        runner(false).run(COMMAND);

        // then
        assertThat(lastExecutions()).singleElement().satisfies(execution -> {
            assertThat(execution.getJobParameters().getLong("revision")).isEqualTo(1L);
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        });
        assertThat(tables.publication(WEEK)).get().extracting(PublicationRow::snapshotId).isEqualTo(R1);
        assertThat(tables.resultChecksum(R1)).isEqualTo(R1_RESULT_CHECKSUM);
    }

    @Test
    void 실패한_뒤_똑같은_명령을_다시_치면_같은_작업을_이어서_끝낸다() throws Exception {
        // given: 첫 명령이 상품 9를 처리하다 실패했다
        givenPurchases(lecturePurchases());
        failOnceWhileProcessing(9L);
        runner(false).run(COMMAND);

        // when
        runner(false).run(COMMAND);

        // then: 작업(JobInstance)은 하나, 실행은 둘. 마지막 실행이 끝까지 갔다
        List<JobInstance> instances = jobExplorer.getJobInstances(WeeklyRankingJobConfig.JOB_NAME, 0, 10);
        assertThat(instances).hasSize(1);
        assertThat(jobExplorer.getJobExecutions(instances.get(0))).extracting(JobExecution::getStatus)
            .containsExactlyInAnyOrder(BatchStatus.FAILED, BatchStatus.COMPLETED);
        assertThat(tables.resultChecksum(R1)).isEqualTo(R1_RESULT_CHECKSUM);
    }

    @Test
    void 재계산을_붙이면_늦은_구매를_r2로_다시_계산해_공개한다() throws Exception {
        // given: r1 공개 뒤 같은 주 늦은 구매
        givenPurchases(lecturePurchases());
        runner(false).run(COMMAND);
        givenPurchases(latePurchase());

        // when
        runner(true).run(COMMAND);

        // then: 사람이 번호를 넣지 않았는데 r2. r2에서는 상품 8 = 11점·1위
        assertThat(tables.publication(WEEK)).get().extracting(PublicationRow::snapshotId, PublicationRow::revision).containsExactly(R2, 2L);
        assertThat(tables.scores(R2)).containsExactly(new ScoreRow(7L, 5L, 2), new ScoreRow(8L, 11L, 1), new ScoreRow(9L, 5L, 3));
    }

    @Test
    void 끝난_주를_재계산_없이_다시_돌리면_이미_끝난_작업이라_거절된다() throws Exception {
        // given
        givenPurchases(lecturePurchases());
        runner(false).run(COMMAND);

        // when
        Throwable thrown = catchThrowable(() -> runner(false).run(COMMAND));

        // then
        assertThat(thrown).isInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    @Test
    void 입력_고정_전에_실패한_작업에_재계산을_붙이면_재시작하라고_거절한다() throws Exception {
        // given: r1 작업이 입력 고정 step에서 실패해 snapshot이 없다 (D-7 실패 지도 #2)
        givenPurchases(lecturePurchases());
        AtomicBoolean failed = new AtomicBoolean(false);
        doAnswer(invocation -> {
            if (failed.compareAndSet(false, true)) {
                throw new IllegalStateException("복사 실패 (테스트)");
            }
            return invocation.callRealMethod();
        }).when(snapshotRepository).copyInput(any(), any(), any(), any());
        runner(false).run(COMMAND);

        // when
        Throwable thrown = catchThrowable(() -> runner(true).run(COMMAND));

        // then: r2로 가면 입력 고정 step이 "가장 큰 revision + 1"로 계속 거절하므로, 여기서 먼저 멈추고 안내한다
        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("--recompute 없이 재시작하세요");
        assertThat(jobExplorer.getJobInstances(WeeklyRankingJobConfig.JOB_NAME, 0, 10)).hasSize(1);
    }

    @Test
    void 같은_주_작업이_멈춰_있으면_재계산을_거절한다() throws Exception {
        // given: r1 작업이 kill되어 STARTED로 남았다
        givenPurchases(lecturePurchases());
        failOnceWhileProcessing(9L);
        runner(false).run(COMMAND);
        recovery.leaveAsStarted(lastExecutions().get(0).getId());

        // when
        Throwable thrown = catchThrowable(() -> runner(true).run(COMMAND));

        // then
        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("실행 중이거나 멈춰 있습니다");
    }

    @Test
    void 그_주_작업이_없는데_재계산을_붙이면_거절한다() {
        // given: 아무 작업도 없다

        // when
        Throwable thrown = catchThrowable(() -> runner(true).run(COMMAND));

        // then
        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("재계산할 원래 작업이 없습니다");
    }

    @Test
    void periodStart가_날짜_형식이_아니면_거절한다() {
        // given
        String command = "periodStart=2026/08/17";

        // when
        Throwable thrown = catchThrowable(() -> runner(false).run(command));

        // then
        assertThat(thrown).isInstanceOf(JobParametersInvalidException.class).hasMessageContaining("yyyy-MM-dd");
    }

    private WeeklyRankingJobRunner runner(boolean recompute) {
        WeeklyRankingJobRunner runner = new WeeklyRankingJobRunner(jobLauncher, jobExplorer, jobRepository, revisionResolver, recompute);
        runner.setJobName(WeeklyRankingJobConfig.JOB_NAME);
        runner.setJobs(List.of(job));
        return runner;
    }

    /**
     * 그 주 작업의 실행들. JobExecution 하나를 돌려주는 helper를 두면 @SpringBatchTest가 실행 생성 메서드로 오인하므로 목록으로 돌려준다.
     */
    private List<JobExecution> lastExecutions() {
        return jobExplorer.getJobInstances(WeeklyRankingJobConfig.JOB_NAME, 0, 10).stream()
            .map(jobExplorer::getLastJobExecution)
            .toList();
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
