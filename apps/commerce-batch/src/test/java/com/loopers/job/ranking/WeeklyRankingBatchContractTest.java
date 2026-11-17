package com.loopers.job.ranking;

import com.loopers.application.ranking.RankingInputService;
import com.loopers.application.ranking.RankingPublicationService;
import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.weeklyranking.WeeklyRankingParameters;
import com.loopers.batch.job.weeklyranking.step.PublishWeeklyRankingTasklet;
import com.loopers.domain.ranking.PublicationOutcome;
import com.loopers.domain.ranking.RankingInputException;
import com.loopers.domain.ranking.RankingProductScore;
import com.loopers.domain.ranking.RankingScoreRepository;
import com.loopers.domain.ranking.WeeklyRankingRequest;
import com.loopers.job.ranking.BatchMetadataFaultSupport.Point;
import com.loopers.job.ranking.process.WeeklyRankingFaultSupport;
import com.loopers.job.ranking.process.WeeklyRankingFaultSupport.FaultPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.loopers.job.ranking.RankingTestDb.countBySnapshot;
import static com.loopers.job.ranking.RankingTestDb.countSourceOrders;
import static com.loopers.job.ranking.RankingTestDb.freshJdbc;
import static com.loopers.job.ranking.RankingTestDb.insertSource;
import static com.loopers.job.ranking.RankingTestDb.manifest;
import static com.loopers.job.ranking.RankingTestDb.persistedJobStatus;
import static com.loopers.job.ranking.RankingTestDb.persistedStep;
import static com.loopers.job.ranking.RankingTestDb.persistedStepStatuses;
import static com.loopers.job.ranking.RankingTestDb.publication;
import static com.loopers.job.ranking.RankingTestDb.rankedResults;
import static com.loopers.job.ranking.RankingTestDb.stageScores;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * weeklyRanking 의 쓰기·입력 고정·공개 경계 계약(랭킹 설계 11.3). 시나리오마다 고정된 주를 쓰고 그 주의 데이터만 단언한다.
 */
@SpringBootTest(properties = {
    "spring.batch.job.name=weeklyRanking",
    "spring.batch.job.enabled=false"
})
@Import({WeeklyRankingFaultSupport.class, BatchMetadataFaultSupport.class})
class WeeklyRankingBatchContractTest {

    private static final List<String> ORIGINAL_RANKING = List.of("7:5:1", "9:5:2", "8:1:3");
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job weeklyRankingJob;

    @Autowired
    private RankingScoreRepository scoreRepository;

    @Autowired
    private RankingInputService inputService;

    @Autowired
    private RankingPublicationService publicationService;

    @Autowired
    private FaultPlan processorFault;

    @Autowired
    private BatchMetadataFaultSupport.Plan metadataFault;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        RankingSchemaSupport.createIfAbsent(
            dataSource, RankingSchemaSupport.SOURCE_TABLE, "fixtures/weekly-ranking-source-schema.sql");
        RankingSchemaSupport.assertMatches(jdbc, RankingSchemaSupport.SOURCE);
        assertThat(processorFault.isArmed()).isFalse();
        assertThat(metadataFault.isArmed()).isFalse();
        processorFault.takeDelegated();
    }

    @DisplayName("2026-08-24: 같은 점수 재기록은 불변, chunk commit 전 실패는 점수·위치를 함께 rollback, 잘못된 요청·고아·손상 입력은 덮어쓰지 않고 실패한다.")
    @Test
    void writerAndInputGuards() throws Exception {
        LocalDate week = LocalDate.of(2026, 8, 24);
        insertOriginalRows(week, 2401);
        WeeklyRankingRequest r1 = request(week, 1);

        // 같은 상품 절대 점수 재기록
        List<RankingProductScore> sameScore = List.of(new RankingProductScore(7L, BigInteger.valueOf(5)));
        runInTransaction(() -> {
            scoreRepository.upsertScores("weekly-20260824-r9", sameScore);
            scoreRepository.upsertScores("weekly-20260824-r9", sameScore);
        });
        assertThat(stageScores("weekly-20260824-r9")).isEqualTo(Map.of(7L, BigInteger.valueOf(5)));

        // 첫 chunk 의 점수 쓰기 뒤 같은 TX 의 metadata 저장 실패 → 점수·위치 모두 rollback
        metadataFault.arm(WeeklyRankingJobConfig.CALCULATE_STEP, Point.IN_CHUNK);
        JobExecution failed = run(r1);
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(processorFault.takeDelegated()).containsExactly(7L, 8L);
        assertThat(stageScores(r1.snapshotId())).isEmpty();
        assertThat(persistedStep(jobExplorer, failed, WeeklyRankingJobConfig.CALCULATE_STEP).getExecutionContext()
            .getInt(WeeklyRankingJobConfig.PRODUCT_READER + ".read.count", 0)).isZero();
        JobExecution restarted = run(r1);
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted.getJobInstance().getId()).isEqualTo(failed.getJobInstance().getId());
        assertThat(processorFault.takeDelegated()).as("이전 위치(처음)부터 다시 처리").containsExactly(7L, 8L, 9L);
        assertThat(rankedResults(r1.snapshotId())).isEqualTo(ORIGINAL_RANKING);

        // 잘못된 요청은 실행 등록 전에 거절한다
        for (JobParameters invalid : List.of(params("2026-08-25", 1L, "weekly-20260825-r1"),
            params("2026-08-24", 2L, "weekly-20260824-r3"), params("2026-08-24", 0L, "weekly-20260824-r0"))) {
            assertThatThrownBy(() -> jobLauncher.run(weeklyRankingJob, invalid)).isInstanceOf(JobParametersInvalidException.class);
        }

        // snapshot 정보 없이 남은 점수가 있으면 새 입력을 만들지 않는다
        WeeklyRankingRequest orphan = request(week, 3);
        runInTransaction(() -> scoreRepository.upsertScores(orphan.snapshotId(),
            List.of(new RankingProductScore(99L, BigInteger.ONE))));
        assertThat(run(orphan).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(manifest(orphan.snapshotId())).isEmpty();
        assertThat(stageScores(orphan.snapshotId())).isEqualTo(Map.of(99L, BigInteger.ONE));

        // 같은 snapshotId 가 다른 revision 행을 가리키면 덮어쓰지 않는다
        WeeklyRankingRequest mismatch = request(week, 5);
        jdbc.update("INSERT INTO ranking_snapshot (snapshot_id, period_start, period_end, revision, status, input_fixed_at, "
                + "input_count, product_count, input_checksum, created_at, updated_at) "
                + "VALUES (?, ?, ?, 6, 'READY', UTC_TIMESTAMP(6), 0, 0, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
            mismatch.snapshotId(), week, week.plusDays(7), EMPTY_SHA256);
        assertThat(run(mismatch).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT revision FROM ranking_snapshot WHERE snapshot_id = ?", Long.class,
            mismatch.snapshotId())).isEqualTo(6L);

        // 저장 입력이 manifest 와 다르면 재사용하지 않는다
        jdbc.update("DELETE FROM ranking_snapshot_input WHERE snapshot_id = ? AND product_id = 9", r1.snapshotId());
        assertThatThrownBy(() -> callInTransaction(() -> inputService.freezeOrReuseInput(r1)))
            .isInstanceOfSatisfying(RankingInputException.class,
                e -> assertThat(e.getReason()).isEqualTo(RankingInputException.Reason.INCONSISTENT_INPUT));
        log("08-24 chunk rollback instanceId=" + failed.getJobInstance().getId() + " failed=" + failed.getId()
            + " restarted=" + restarted.getId() + " ranking=" + rankedResults(r1.snapshotId()));
    }

    @DisplayName("2026-08-31: 입력 고정 TX 의 commit 전 실패는 복사 입력과 manifest 를 함께 rollback 하고, 재시작에서 다시 고정한다.")
    @Test
    void freezeRollsBackInputAndManifestTogether() throws Exception {
        LocalDate week = LocalDate.of(2026, 8, 31);
        insertOriginalRows(week, 3101);
        WeeklyRankingRequest r1 = request(week, 1);

        metadataFault.arm(WeeklyRankingJobConfig.FREEZE_STEP, Point.IN_CHUNK);
        JobExecution failed = run(r1);
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(countBySnapshot(jdbc, "ranking_snapshot_input", r1.snapshotId())).isZero();
        assertThat(manifest(r1.snapshotId())).isEmpty();

        JobExecution restarted = run(r1);
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(countBySnapshot(jdbc, "ranking_snapshot_input", r1.snapshotId())).isEqualTo(4);
        assertThat(rankedResults(r1.snapshotId())).isEqualTo(ORIGINAL_RANKING);
        log("08-31 freeze rollback failed=" + failed.getId() + " restarted=" + restarted.getId());
    }

    @DisplayName("2026-07-20: 주 시작은 포함하고 끝은 제외하며, UTC 마이크로초 값을 그대로 고정·직렬화한다.")
    @Test
    void periodBoundaryIncludesStartAndExcludesEnd() throws Exception {
        // §11.3.2 예시의 2026-09-07 은 경계 행이 08-31·09-14 시나리오 주에 들어가므로, 이웃 주가 비어 있는 주로 옮겼다.
        LocalDate week = LocalDate.of(2026, 7, 20);
        assertThat(countSourceOrders(jdbc, 2001, 2002, 2003, 2004)).isZero();
        insertSource(jdbc, 2001L, 7L, 1L, LocalDateTime.of(2026, 7, 19, 23, 59, 59, 999_999_000));
        insertSource(jdbc, 2002L, 7L, 2L, LocalDateTime.of(2026, 7, 20, 0, 0));
        insertSource(jdbc, 2003L, 8L, 3L, LocalDateTime.of(2026, 7, 26, 23, 59, 59, 999_999_000));
        insertSource(jdbc, 2004L, 9L, 4L, LocalDateTime.of(2026, 7, 27, 0, 0));
        WeeklyRankingRequest r1 = request(week, 1);

        assertThat(run(r1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(freshJdbc().queryForList("SELECT order_id FROM ranking_snapshot_input WHERE snapshot_id = ? "
            + "ORDER BY order_id", Long.class, r1.snapshotId())).containsExactly(2002L, 2003L);
        // 2002/7/2/2026-07-20T00:00:00.000000Z, 2003/8/3/2026-07-26T23:59:59.999999Z 를 3.5 규칙으로 shasum 계산
        assertThat(manifest(r1.snapshotId()).get(3))
            .isEqualTo("53295d0a3f968f4c114888e3feb1b35ffcd4893a92ee8b9553b43e52a35a6b67");
        assertThat(rankedResults(r1.snapshotId())).containsExactly("8:3:1", "7:2:2");
    }

    @DisplayName("2026-09-14: 구매가 없는 주도 READY → COMPLETED 와 빈 결과로 공개한다.")
    @Test
    void emptyWeekPublishesEmptyResult() throws Exception {
        LocalDate week = LocalDate.of(2026, 9, 14);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_purchase_activity WHERE confirmed_at >= ? "
            + "AND confirmed_at < ?", Long.class, week.atStartOfDay(), week.plusDays(7).atStartOfDay())).isZero();
        WeeklyRankingRequest r1 = request(week, 1);

        JobExecution execution = run(r1);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(manifest(r1.snapshotId())).isEqualTo(List.of("COMPLETED", 0L, 0L, EMPTY_SHA256, EMPTY_SHA256));
        assertThat(rankedResults(r1.snapshotId())).isEmpty();
        assertThat(publication(week)).startsWith(r1.snapshotId() + "@");
    }

    @DisplayName("2026-09-21: 공개 TX 의 commit 전 실패는 순위·COMPLETED·pointer 를 모두 rollback 하고 기존 공개본을 유지한다.")
    @Test
    void publishRollbackKeepsPreviousPublication() throws Exception {
        LocalDate week = LocalDate.of(2026, 9, 21);
        insertOriginalRows(week, 2101);
        WeeklyRankingRequest r1 = request(week, 1);
        WeeklyRankingRequest r2 = request(week, 2);
        assertThat(run(r1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        String r1Publication = publication(week);

        metadataFault.arm(WeeklyRankingJobConfig.PUBLISH_STEP, Point.IN_CHUNK);
        JobExecution failed = run(r2);
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(manifest(r2.snapshotId()).get(0)).isEqualTo("READY");
        assertThat(manifest(r2.snapshotId()).get(4)).isNull();
        assertThat(rankedResults(r2.snapshotId())).isEmpty();
        assertThat(publication(week)).isEqualTo(r1Publication);

        JobExecution restarted = run(r2);
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(persistedStepStatuses(jobExplorer, restarted))
            .containsExactly(Map.entry(WeeklyRankingJobConfig.PUBLISH_STEP, BatchStatus.COMPLETED));
        assertThat(rankedResults(r2.snapshotId())).isEqualTo(ORIGINAL_RANKING);
        assertThat(publication(week)).startsWith(r2.snapshotId() + "@");
        log("09-21 publish rollback kept " + r1Publication + "; restart published " + publication(week));
    }

    @DisplayName("2026-09-28: 같은 snapshot 공개 재진입은 결과·공개 시각을 바꾸지 않고, 더 새 revision 공개 뒤의 오래된 대상은 완성만 하고 공개를 생략한다.")
    @Test
    void republishIsIdempotentAndOlderRevisionDoesNotOverwrite() throws Exception {
        LocalDate week = LocalDate.of(2026, 9, 28);
        insertOriginalRows(week, 2801);
        WeeklyRankingRequest r1 = request(week, 1);
        WeeklyRankingRequest r2 = request(week, 2);
        assertThat(run(r2).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        String r2Publication = publication(week);
        List<Object> r2Manifest = manifest(r2.snapshotId());

        assertThat(callInTransaction(() -> publicationService.publish(r2)).outcome()).isEqualTo(PublicationOutcome.ALREADY_PUBLISHED);
        assertThat(publication(week)).isEqualTo(r2Publication);
        assertThat(manifest(r2.snapshotId())).isEqualTo(r2Manifest);
        assertThat(rankedResults(r2.snapshotId())).isEqualTo(ORIGINAL_RANKING);

        JobExecution older = run(r1);
        assertThat(older.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(persistedStep(jobExplorer, older, WeeklyRankingJobConfig.PUBLISH_STEP).getExecutionContext()
            .getString(PublishWeeklyRankingTasklet.OUTCOME_KEY)).isEqualTo("SKIPPED_NEWER");
        assertThat(manifest(r1.snapshotId()).get(0)).isEqualTo("COMPLETED");
        assertThat(rankedResults(r1.snapshotId())).isEqualTo(ORIGINAL_RANKING);
        assertThat(publication(week)).isEqualTo(r2Publication);
    }

    @DisplayName("2026-10-05: 공개 commit 뒤 최종 Step metadata 저장만 실패해도 완성본·pointer 는 남고, 같은 대상 공개 재진입은 멱등이다.")
    @Test
    void publishCommitSurvivesFinalMetadataFailure() throws Exception {
        LocalDate week = LocalDate.of(2026, 10, 5);
        insertOriginalRows(week, 1051);
        WeeklyRankingRequest r1 = request(week, 1);

        metadataFault.arm(WeeklyRankingJobConfig.PUBLISH_STEP, Point.FINAL);
        JobExecution execution = run(r1);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.UNKNOWN);
        assertThat(manifest(r1.snapshotId()).get(0)).isEqualTo("COMPLETED");
        assertThat(rankedResults(r1.snapshotId())).isEqualTo(ORIGINAL_RANKING);
        String committedPublication = publication(week);
        assertThat(committedPublication).startsWith(r1.snapshotId() + "@");

        // UNKNOWN Job 을 restart 하지 않고, 공개 서비스만 별도 TX 로 다시 호출한다
        assertThat(callInTransaction(() -> publicationService.publish(r1)).outcome()).isEqualTo(PublicationOutcome.ALREADY_PUBLISHED);
        assertThat(publication(week)).isEqualTo(committedPublication);
        log("10-05 executionId=" + execution.getId() + " status=" + execution.getStatus()
            + " persistedJobStatus=" + persistedJobStatus(execution.getId())
            + " persistedPublishStep=" + persistedStep(jobExplorer, execution, WeeklyRankingJobConfig.PUBLISH_STEP).getStatus()
            + " publication=" + committedPublication);
    }

    /** 원문 6절 4행을 같은 요일·시각으로 다른 주와 주문 ID 영역에 옮긴다. */
    private void insertOriginalRows(LocalDate week, long orderBase) {
        assertThat(countSourceOrders(jdbc, orderBase, orderBase + 1, orderBase + 2)).isZero();
        LocalDateTime start = week.atStartOfDay();
        insertSource(jdbc, orderBase, 7L, 2L, start.plusHours(1));
        insertSource(jdbc, orderBase, 8L, 1L, start.plusHours(1));
        insertSource(jdbc, orderBase + 1, 7L, 3L, start.plusDays(1).plusHours(1));
        insertSource(jdbc, orderBase + 2, 9L, 5L, start.plusDays(2).plusHours(1));
    }

    private static WeeklyRankingRequest request(LocalDate week, long revision) {
        return WeeklyRankingRequest.of(week.toString(), revision, WeeklyRankingRequest.canonicalId(week, revision));
    }

    private static JobParameters params(String periodStart, long revision, String snapshotId) {
        return new JobParametersBuilder()
            .addString(WeeklyRankingParameters.PERIOD_START, periodStart, true)
            .addLong(WeeklyRankingParameters.REVISION, revision, true)
            .addString(WeeklyRankingParameters.SNAPSHOT_ID, snapshotId, true)
            .toJobParameters();
    }

    private JobExecution run(WeeklyRankingRequest request) throws Exception {
        return jobLauncher.run(weeklyRankingJob, WeeklyRankingParameters.toJobParameters(request));
    }

    private <T> T callInTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private void runInTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private static void log(String message) {
        System.out.println("[ranking-contract] " + message);
    }
}
