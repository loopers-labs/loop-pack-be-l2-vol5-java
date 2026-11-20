package com.loopers.batch.job.weeklyranking;

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
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * 입력 고정 step (W9 D-3, 구현 ①). 실제 MySQL에서 이 step만 띄워(launchStep) 고정 입력·snapshot 행을 본다.
 * job 전체를 돌리면 점수 계산·공개 step까지 이어지므로 이 step의 결과만 보려고 나눈다.
 * (JobExecution·StepExecution을 돌려주는 helper를 테스트 클래스에 두면 @SpringBatchTest가 실행 생성 메서드로 오인하므로 두지 않는다.)
 */
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME
})
@SpringBatchTest
class InputFreezeStepTest {

    private static final Instant NOW = Instant.parse("2026-08-24T01:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.parse("2026-08-24T01:00:00");
    private static final String R1 = "weekly-20260817-r1";
    private static final String FIXTURE_CHECKSUM = "3e90276ec1f9123f308cd18d155e1dde6bf811ef3aa741105ff5c766c357a298";
    private static final String EMPTY_CHECKSUM = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

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
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoSpyBean
    private RankingSnapshotJdbcRepository repository;

    @BeforeEach
    void setUp() {
        jobLauncherTestUtils.setJob(job);
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void 그_주_구매가_snapshot에_고정되고_건수_수량_합_checksum이_기록된다() throws Exception {
        // given: 발제 §6 fixture 4행 + 같은 주에 삭제된 행 1개
        givenPurchases(fixture());
        givenPurchases(TestProductPurchaseActivity.of(45L, 7L, 9, "2026-08-19T02:00:00Z").deleted());

        // when
        JobExecution execution = jobLauncherTestUtils.launchStep(WeeklyRankingJobConfig.INPUT_FREEZE_STEP, params("2026-08-17", 1L));

        // then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(frozenInput(R1)).containsExactly(
            new InputRow(42L, 7L, 2, LocalDateTime.parse("2026-08-17T01:00:00")),
            new InputRow(42L, 8L, 1, LocalDateTime.parse("2026-08-17T01:00:00")),
            new InputRow(43L, 7L, 3, LocalDateTime.parse("2026-08-18T01:00:00")),
            new InputRow(44L, 9L, 5, LocalDateTime.parse("2026-08-19T01:00:00"))
        );
        assertThat(snapshot(R1)).containsExactly(new SnapshotRow(LocalDate.parse("2026-08-17"), 1L, "FROZEN", NOW_UTC, 4, 11, FIXTURE_CHECKSUM));
        assertThat(jdbc.queryForList("SELECT DISTINCT created_at FROM ranking_snapshot_input WHERE snapshot_id = ?", LocalDateTime.class, R1)).containsExactly(NOW_UTC);
        assertThat(jdbc.queryForObject("SELECT created_at FROM ranking_snapshot WHERE snapshot_id = ?", LocalDateTime.class, R1)).isEqualTo(NOW_UTC);
    }

    @Test
    void 그_주_구매가_없어도_0건_snapshot이_만들어진다() throws Exception {
        // given: 다른 주(2026-08-10 주)의 구매만 있다
        givenPurchases(TestProductPurchaseActivity.of(50L, 7L, 3, "2026-08-12T01:00:00Z"));

        // when
        JobExecution execution = jobLauncherTestUtils.launchStep(WeeklyRankingJobConfig.INPUT_FREEZE_STEP, params("2026-08-17", 1L));

        // then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(frozenInput(R1)).isEmpty();
        assertThat(snapshot(R1)).containsExactly(new SnapshotRow(LocalDate.parse("2026-08-17"), 1L, "FROZEN", NOW_UTC, 0, 0, EMPTY_CHECKSUM));
    }

    @Test
    void 주의_시작_시각은_포함하고_끝_시각은_제외하며_UTC로_자른다() throws Exception {
        // given: 주 = [2026-08-17T00:00Z, 2026-08-24T00:00Z). JVM 기본 시간대가 Asia/Seoul이어야 한국 시간·UTC가 갈리는 행이 의미가 있다
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Seoul");
        givenPurchases(
            TestProductPurchaseActivity.of(61L, 1L, 1, "2026-08-17T00:00:00Z"),        // 시작 시각 → 포함
            TestProductPurchaseActivity.of(62L, 1L, 1, "2026-08-23T23:59:59.999999Z"), // 끝 직전 → 포함
            TestProductPurchaseActivity.of(63L, 1L, 1, "2026-08-23T15:00:00Z"),        // 한국 시간 08-24 00:00이지만 UTC로는 이번 주 → 포함
            TestProductPurchaseActivity.of(64L, 1L, 1, "2026-08-16T23:59:59.999999Z"), // 시작 직전 → 제외
            TestProductPurchaseActivity.of(65L, 1L, 1, "2026-08-24T00:00:00Z"),        // 끝 시각 → 제외
            TestProductPurchaseActivity.of(66L, 1L, 1, "2026-08-16T15:00:00Z")         // 한국 시간 08-17 00:00이지만 UTC로는 지난주 → 제외
        );

        // when
        JobExecution execution = jobLauncherTestUtils.launchStep(WeeklyRankingJobConfig.INPUT_FREEZE_STEP, params("2026-08-17", 1L));

        // then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(frozenInput(R1)).extracting(InputRow::orderId).containsExactly(61L, 62L, 63L);
    }

    @Test
    void 같은_snapshot이_이미_고정돼_있으면_복사하지_않고_통과한다() throws Exception {
        // given: tasklet의 "이미 고정된 snapshot" 분기만 본다. 이 step을 다시 돌리려고 실행 기록을 JobInstance까지 지운다
        //        (재시작이 아니라 새 작업이다. 재시작 경로 — D-7 실패 지도 #3 — 는 WeeklyScoreRestartTest에서 본다).
        //        그사이 원본에 같은 주 늦은 구매가 1행 들어왔다
        givenPurchases(fixture());
        jobLauncherTestUtils.launchStep(WeeklyRankingJobConfig.INPUT_FREEZE_STEP, params("2026-08-17", 1L));
        jobRepositoryTestUtils.removeJobExecutions();
        givenPurchases(TestProductPurchaseActivity.of(46L, 8L, 10, "2026-08-20T01:00:00Z"));

        // when
        JobExecution execution = jobLauncherTestUtils.launchStep(WeeklyRankingJobConfig.INPUT_FREEZE_STEP, params("2026-08-17", 1L));

        // then: 늦은 구매는 고정 입력에 들어가지 않는다
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(frozenInput(R1)).extracting(InputRow::orderId).containsExactly(42L, 42L, 43L, 44L);
        assertThat(snapshot(R1)).extracting(SnapshotRow::inputCount, SnapshotRow::inputQuantitySum, SnapshotRow::inputChecksum)
            .containsExactly(tuple(4L, 11L, FIXTURE_CHECKSUM));
    }

    @Test
    void 고정_입력이_저장된_요약과_다르면_실패하고_행은_그대로_둔다() throws Exception {
        // given: r1 고정 뒤 고정 입력의 수량이 옮겨 갔다 (42/7 2→1, 44/9 5→6). 건수·합은 4건·11 그대로라 checksum만 다르다 (D-3)
        givenPurchases(fixture());
        jobLauncherTestUtils.launchStep(WeeklyRankingJobConfig.INPUT_FREEZE_STEP, params("2026-08-17", 1L));
        jobRepositoryTestUtils.removeJobExecutions();
        jdbc.update("UPDATE ranking_snapshot_input SET quantity = 1 WHERE snapshot_id = ? AND order_id = 42 AND product_id = 7", R1);
        jdbc.update("UPDATE ranking_snapshot_input SET quantity = 6 WHERE snapshot_id = ? AND order_id = 44", R1);

        // when
        JobExecution execution = jobLauncherTestUtils.launchStep(WeeklyRankingJobConfig.INPUT_FREEZE_STEP, params("2026-08-17", 1L));

        // then: 저장값과 다시 계산한 값을 함께 알린다. 행은 고치지 않는다
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failureMessages(execution)).anySatisfy(message -> assertThat(message)
            .contains("고정 입력이 달라졌습니다")
            .contains("저장: 4건·수량 11·3e90276e")
            .contains("다시 계산: 4건·수량 11·7c808198"));
        assertThat(snapshot(R1)).extracting(SnapshotRow::inputChecksum).containsExactly(FIXTURE_CHECKSUM);
        assertThat(frozenInput(R1)).extracting(InputRow::quantity).containsExactly(1, 1, 3, 6);
    }

    @Test
    void 그_주에_snapshot이_없는데_revision_2로_실행하면_실패하고_아무것도_남지_않는다() throws Exception {
        // given
        givenPurchases(fixture());

        // when
        JobExecution execution = jobLauncherTestUtils.launchStep(WeeklyRankingJobConfig.INPUT_FREEZE_STEP, params("2026-08-17", 2L));

        // then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failureMessages(execution)).anySatisfy(message -> assertThat(message).contains("가장 큰 revision + 1"));
        assertThat(count("ranking_snapshot")).isZero();
        assertThat(count("ranking_snapshot_input")).isZero();
    }

    @Test
    void r1이_있는데_revision_3으로_실행하면_실패하고_r3은_만들지_않는다() throws Exception {
        // given: r1이 고정돼 있다
        givenPurchases(fixture());
        assertThat(jobLauncherTestUtils.launchStep(WeeklyRankingJobConfig.INPUT_FREEZE_STEP, params("2026-08-17", 1L)).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // when
        JobExecution execution = jobLauncherTestUtils.launchStep(WeeklyRankingJobConfig.INPUT_FREEZE_STEP, params("2026-08-17", 3L));

        // then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failureMessages(execution)).anySatisfy(message -> assertThat(message).contains("가장 큰 revision = 1"));
        assertThat(snapshot("weekly-20260817-r3")).isEmpty();
        assertThat(frozenInput("weekly-20260817-r3")).isEmpty();
        assertThat(frozenInput(R1)).hasSize(4);
    }

    @Test
    void r1이_있으면_revision_2로_늦은_구매까지_새로_고정하고_r1은_그대로_둔다() throws Exception {
        // given: r1 고정 뒤 같은 주 늦은 구매(주문 46, 상품 8, 10개)
        givenPurchases(fixture());
        assertThat(jobLauncherTestUtils.launchStep(WeeklyRankingJobConfig.INPUT_FREEZE_STEP, params("2026-08-17", 1L)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        givenPurchases(TestProductPurchaseActivity.of(46L, 8L, 10, "2026-08-20T01:00:00Z"));

        // when
        JobExecution execution = jobLauncherTestUtils.launchStep(WeeklyRankingJobConfig.INPUT_FREEZE_STEP, params("2026-08-17", 2L));

        // then: r2 = 5건·합 21 (셸로 계산한 checksum), r1 = 4건·합 11 그대로
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(snapshot("weekly-20260817-r2")).extracting(SnapshotRow::inputCount, SnapshotRow::inputQuantitySum, SnapshotRow::inputChecksum)
            .containsExactly(tuple(5L, 21L, "4cf7f26190585ca4f0f38df4bde2224bd73da2ae5f0bc45258f88adecd9c05db"));
        assertThat(snapshot(R1)).extracting(SnapshotRow::inputCount, SnapshotRow::inputQuantitySum, SnapshotRow::inputChecksum)
            .containsExactly(tuple(4L, 11L, FIXTURE_CHECKSUM));
        assertThat(frozenInput(R1)).extracting(InputRow::orderId).containsExactly(42L, 42L, 43L, 44L);
    }

    @Test
    void 복사_뒤_snapshot_저장에서_실패하면_복사한_입력까지_rollback된다() throws Exception {
        // given: 복사(④)는 되고 snapshot 저장(⑥)에서 실패하게 한다
        givenPurchases(fixture());
        doThrow(new IllegalStateException("snapshot 저장 실패 (테스트)"))
            .when(repository).insertFrozenSnapshot(any(), any(), anyLong(), any(), any());

        // when
        JobExecution execution = jobLauncherTestUtils.launchStep(WeeklyRankingJobConfig.INPUT_FREEZE_STEP, params("2026-08-17", 1L));

        // then: ⑥에서 실패했다는 것은 ④ 복사가 끝났다는 뜻이다. 그래도 TX 하나라 복사한 입력이 남지 않는다
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failureMessages(execution)).anySatisfy(message -> assertThat(message).contains("snapshot 저장 실패 (테스트)"));
        assertThat(count("ranking_snapshot_input")).isZero();
        assertThat(count("ranking_snapshot")).isZero();
    }

    @Test
    void 입력_고정_step은_DB_기본값과_달리_READ_COMMITTED로_돈다() throws Exception {
        // given: DB 기본 격리 수준은 REPEATABLE-READ다
        assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
        givenPurchases(fixture());

        // when
        JobExecution execution = jobLauncherTestUtils.launchStep(WeeklyRankingJobConfig.INPUT_FREEZE_STEP, params("2026-08-17", 1L));

        // then: step TX 안에서 읽은 값이 실행 기록에 남는다
        StepExecution inputFreeze = execution.getStepExecutions().iterator().next();
        assertThat(inputFreeze.getStepName()).isEqualTo(WeeklyRankingJobConfig.INPUT_FREEZE_STEP);
        assertThat(inputFreeze.getExecutionContext().getString(InputFreezeTasklet.ISOLATION_KEY)).isEqualTo("READ-COMMITTED");
    }

    @Test
    void 월요일이_아닌_periodStart로_job을_띄우면_실행_기록을_만들기_전에_거절한다() {
        // given: 2026-08-18은 화요일
        givenPurchases(fixture());

        // when
        Throwable thrown = catchThrowable(() -> jobLauncherTestUtils.launchJob(params("2026-08-18", 1L)));

        // then: 실행 기록도 snapshot도 남지 않는다 (D-1, 실패 지도 #1)
        assertThat(thrown).isInstanceOf(JobParametersInvalidException.class);
        assertThat(count("BATCH_JOB_INSTANCE")).isZero();
        assertThat(count("ranking_snapshot")).isZero();
    }

    private void cleanUp() {
        jobRepositoryTestUtils.removeJobExecutions();
        databaseCleanUp.truncateAllTables();
    }

    private static TestProductPurchaseActivity[] fixture() {
        return new TestProductPurchaseActivity[]{
            TestProductPurchaseActivity.of(42L, 7L, 2, "2026-08-17T01:00:00Z"),
            TestProductPurchaseActivity.of(42L, 8L, 1, "2026-08-17T01:00:00Z"),
            TestProductPurchaseActivity.of(43L, 7L, 3, "2026-08-18T01:00:00Z"),
            TestProductPurchaseActivity.of(44L, 9L, 5, "2026-08-19T01:00:00Z")
        };
    }

    private void givenPurchases(TestProductPurchaseActivity... rows) {
        transactionTemplate.executeWithoutResult(status -> Arrays.stream(rows).forEach(entityManager::persist));
    }

    private static JobParameters params(String periodStart, long revision) {
        return new JobParametersBuilder()
            .addLocalDate(WeeklyRankingJobParametersValidator.PERIOD_START, LocalDate.parse(periodStart))
            .addLong(WeeklyRankingJobParametersValidator.REVISION, revision)
            .toJobParameters();
    }

    private List<InputRow> frozenInput(String snapshotId) {
        return jdbc.query(
            "SELECT order_id, product_id, quantity, confirmed_at FROM ranking_snapshot_input WHERE snapshot_id = ? ORDER BY order_id, product_id",
            (rs, rowNum) -> new InputRow(rs.getLong("order_id"), rs.getLong("product_id"), rs.getInt("quantity"), rs.getObject("confirmed_at", LocalDateTime.class)),
            snapshotId);
    }

    private List<SnapshotRow> snapshot(String snapshotId) {
        return jdbc.query("""
            SELECT period_start, revision, status, frozen_at, input_count, input_quantity_sum, input_checksum
              FROM ranking_snapshot WHERE snapshot_id = ?
            """,
            (rs, rowNum) -> new SnapshotRow(
                rs.getObject("period_start", LocalDate.class), rs.getLong("revision"), rs.getString("status"),
                rs.getObject("frozen_at", LocalDateTime.class), rs.getLong("input_count"), rs.getLong("input_quantity_sum"), rs.getString("input_checksum")),
            snapshotId);
    }

    private long count(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    private static List<String> failureMessages(JobExecution execution) {
        return execution.getAllFailureExceptions().stream().map(Throwable::getMessage).toList();
    }

    record InputRow(long orderId, long productId, int quantity, LocalDateTime confirmedAt) {}

    record SnapshotRow(LocalDate periodStart, long revision, String status, LocalDateTime frozenAt, long inputCount, long inputQuantitySum, String inputChecksum) {}
}
