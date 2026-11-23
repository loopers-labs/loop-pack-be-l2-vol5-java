package com.loopers.job.ranking;

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.weeklyranking.WeeklyRankingParameters;
import com.loopers.batch.job.weeklyranking.step.PublishWeeklyRankingTasklet;
import com.loopers.domain.ranking.WeeklyRankingRequest;
import com.loopers.job.ranking.process.WeeklyRankingFaultSupport;
import com.loopers.job.ranking.process.WeeklyRankingFaultSupport.FaultPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.support.DefaultBatchConfiguration;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
 * 실제 MySQL·영속 JobRepository 에서의 재시작 검증. 작은 probe(Implementation Quest 3절)와 weeklyRanking 의
 * 입력 고정·계산 재시작(랭킹 설계 11.3)을 다룬다. 두 실행 사이 입력·stage·Batch metadata 를 유지하며,
 * 테스트를 자동 rollback TX 로 감싸지 않는다.
 */
@SpringBootTest(properties = {
    "spring.batch.job.name=weeklyRanking",
    "spring.batch.job.enabled=false"
})
@Import(WeeklyRankingFaultSupport.class)
class RankingJobRestartTest {

    private static final String PROBE_SNAPSHOT_ID = "probe-1";
    private static final String JDBC_CHECK_SNAPSHOT_ID = "probe-jdbc-1";
    private static final String READ_COUNT_KEY = "purchaseProductProbe.read.count";
    private static final String RANKING_READ_COUNT_KEY = WeeklyRankingJobConfig.PRODUCT_READER + ".read.count";
    /** 원문 4행을 3.5 규칙으로 직렬화한 SHA-256. 제품 코드와 별도로 shasum 으로 계산한 값이다. */
    private static final String SOURCE_INPUT_CHECKSUM = "3e90276ec1f9123f308cd18d155e1dde6bf811ef3aa741105ff5c766c357a298";
    /** 기대 순위 7:5:1, 9:5:2, 8:1:3 을 3.5 규칙으로 직렬화한 SHA-256(shasum 계산). */
    private static final String SOURCE_RESULT_CHECKSUM = "745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2";

    @Autowired
    @Qualifier("mySqlMainDataSource")
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DefaultBatchConfiguration batchConfiguration;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job weeklyRankingJob;

    @Autowired
    private FaultPlan faultPlan;

    @DisplayName("최초 준비: TX manager·DataSource 연결, 스키마, strict mode, 날짜·큰 정수 JDBC 경로가 설계와 맞다.")
    @Test
    void initialPreparationMatchesDesign() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
        assertThat(((JpaTransactionManager) transactionManager).getDataSource()).isSameAs(dataSource);
        assertThat((Object) ReflectionTestUtils.invokeMethod(batchConfiguration, "getDataSource")).isSameAs(dataSource);
        assertThat((Object) ReflectionTestUtils.invokeMethod(batchConfiguration, "getTransactionManager"))
            .isSameAs(transactionManager);

        boolean sourceCreated = RankingSchemaSupport.createIfAbsent(
            dataSource, RankingSchemaSupport.SOURCE_TABLE, "fixtures/weekly-ranking-source-schema.sql");
        RankingSchemaSupport.RANKING_TABLES.forEach(spec -> RankingSchemaSupport.assertMatches(jdbc, spec));
        RankingSchemaSupport.assertMatches(jdbc, RankingSchemaSupport.SOURCE);

        String sqlMode = jdbc.queryForObject("SELECT @@SESSION.sql_mode", String.class);
        assertThat(sqlMode).contains("STRICT_TRANS_TABLES");
        assertThat(jdbc.queryForObject("SELECT @@SESSION.transaction_isolation", String.class))
            .isEqualTo("REPEATABLE-READ");

        // DATE·DATETIME(6): setObject/getObject 로 9시간 이동·마이크로초 손실이 없어야 한다.
        LocalDate date = LocalDate.of(2026, 8, 17);
        assertThat(jdbc.queryForObject("SELECT CAST(? AS DATE)", LocalDate.class, date)).isEqualTo(date);
        assertThat(countBySnapshot(jdbc, "ranking_snapshot_input", JDBC_CHECK_SNAPSHOT_ID)).isZero();
        LocalDateTime confirmedAt = LocalDateTime.of(2026, 8, 23, 23, 59, 59, 999_999_000);
        insertInput(jdbc, JDBC_CHECK_SNAPSHOT_ID, 1L, 1L, Long.MAX_VALUE, confirmedAt);
        insertInput(jdbc, JDBC_CHECK_SNAPSHOT_ID, 2L, 1L, Long.MAX_VALUE, confirmedAt);
        String inputWhere = " FROM ranking_snapshot_input WHERE snapshot_id = ? AND order_id = 1";
        assertThat(jdbc.queryForObject("SELECT confirmed_at" + inputWhere, LocalDateTime.class, JDBC_CHECK_SNAPSHOT_ID))
            .isEqualTo(confirmedAt);
        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(confirmed_at, '%Y-%m-%d %H:%i:%s.%f')" + inputWhere,
            String.class, JDBC_CHECK_SNAPSHOT_ID)).isEqualTo("2026-08-23 23:59:59.999999");

        // SUM 은 long 을 거치지 않고, DECIMAL(65,0) 은 65자리까지 정확히 저장하고 그 이상은 거절한다.
        BigInteger sum = jdbc.queryForObject("SELECT SUM(quantity) FROM ranking_snapshot_input "
            + "WHERE snapshot_id = ? AND product_id = 1", BigDecimal.class, JDBC_CHECK_SNAPSHOT_ID).toBigIntegerExact();
        assertThat(sum).isEqualTo(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO));
        BigInteger maxScore = BigInteger.TEN.pow(65).subtract(BigInteger.ONE);
        insertStage(jdbc, 1L, maxScore);
        assertThat(jdbc.queryForObject("SELECT score FROM ranking_score_stage WHERE snapshot_id = ?",
            BigDecimal.class, JDBC_CHECK_SNAPSHOT_ID).toBigIntegerExact()).isEqualTo(maxScore);
        assertThatThrownBy(() -> insertStage(jdbc, 2L, BigInteger.TEN.pow(65))).isInstanceOf(DataAccessException.class);

        jdbc.update("DELETE FROM ranking_snapshot_input WHERE snapshot_id = ?", JDBC_CHECK_SNAPSHOT_ID);
        jdbc.update("DELETE FROM ranking_score_stage WHERE snapshot_id = ?", JDBC_CHECK_SNAPSHOT_ID);
        assertThat(countBySnapshot(jdbc, "ranking_snapshot_input", JDBC_CHECK_SNAPSHOT_ID)).isZero();
        assertThat(countBySnapshot(jdbc, "ranking_score_stage", JDBC_CHECK_SNAPSHOT_ID)).isZero();
        log("transactionManager=" + transactionManager.getClass().getSimpleName() + " sameDataSource/TX=true"
            + " sourceCreated=" + sourceCreated + " sql_mode=" + sqlMode + " sum=" + sum + " cleanup ok");
    }

    @DisplayName("상품 7·8 저장과 reader 위치가 함께 commit 되고, 9 직전 실패 뒤 같은 JobInstance 의 새 실행이 9부터 이어간다.")
    @Test
    void restartsFromThirdProductInSameJobInstance() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        JobParameters params = new JobParametersBuilder()
            .addString("snapshotId", PROBE_SNAPSHOT_ID, true)
            .toJobParameters();

        // 남은 이력이 있으면 지우지 않고 실패한다.
        assertThat(jobExplorer.getJobInstance("purchaseInputProbe", params)).isNull();
        assertThat(countBySnapshot(jdbc, "ranking_snapshot_input", PROBE_SNAPSHOT_ID)).isZero();
        boolean probeTableCreated = RankingSchemaSupport.createIfAbsent(
            dataSource, RankingSchemaSupport.PROBE_TABLE, "fixtures/batch-restart-probe-schema.sql");
        RankingSchemaSupport.assertMatches(jdbc, RankingSchemaSupport.PROBE);
        assertThat(probeRows()).isEmpty();
        // 원문 6절 fixture(UTC)
        insertInput(jdbc, PROBE_SNAPSHOT_ID, 42L, 7L, 2L, LocalDateTime.of(2026, 8, 17, 1, 0));
        insertInput(jdbc, PROBE_SNAPSHOT_ID, 42L, 8L, 1L, LocalDateTime.of(2026, 8, 17, 1, 0));
        insertInput(jdbc, PROBE_SNAPSHOT_ID, 43L, 7L, 3L, LocalDateTime.of(2026, 8, 18, 1, 0));
        insertInput(jdbc, PROBE_SNAPSHOT_ID, 44L, 9L, 5L, LocalDateTime.of(2026, 8, 19, 1, 0));

        Observation firstRun = new Observation();
        JobExecution first = jobLauncher.run(probe(params, true, firstRun), params);

        StepExecution firstStep = persistedStep(jobExplorer, first, "probeProducts");
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(firstRun.processed).containsExactly(7L, 8L, 9L);
        assertThat(firstRun.written).containsExactly(7L, 8L);
        assertThat(firstRun.writerInTransaction).containsOnly(true);
        assertThat(firstStep.getExecutionContext().getInt(READ_COUNT_KEY)).isEqualTo(2);
        assertThat(probeRows()).containsExactly(7L, 8L);
        assertThat(persistedJobStatus(first.getId())).isEqualTo("FAILED");
        log("first: instanceId=" + first.getJobInstance().getId() + " executionId=" + first.getId()
            + " status=" + first.getStatus() + " " + READ_COUNT_KEY + "=" + firstStep.getExecutionContext().getInt(READ_COUNT_KEY)
            + " processed=" + firstRun.processed + " probeRows=" + probeRows());

        Observation secondRun = new Observation();
        JobExecution second = jobLauncher.run(probe(params, false, secondRun), params);

        StepExecution secondStep = persistedStep(jobExplorer, second, "probeProducts");
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getId()).isEqualTo(first.getJobInstance().getId());
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(secondRun.processed).containsExactly(9L);
        assertThat(secondRun.written).containsExactly(9L);
        assertThat(secondRun.writerInTransaction).containsOnly(true);
        assertThat(probeRows()).containsExactly(7L, 8L, 9L);
        assertThat(persistedJobStatus(second.getId())).isEqualTo("COMPLETED");
        assertThat(countBySnapshot(jdbc, "ranking_snapshot_input", PROBE_SNAPSHOT_ID)).isEqualTo(4);
        log("second: instanceId=" + second.getJobInstance().getId() + " executionId=" + second.getId()
            + " status=" + second.getStatus() + " " + READ_COUNT_KEY + "=" + secondStep.getExecutionContext().getInt(READ_COUNT_KEY)
            + " processed=" + secondRun.processed + " probeRows=" + probeRows());

        // 증거 확인 뒤 probe 가 소유한 범위만 정리한다.
        long instanceId = first.getJobInstance().getId();
        jobRepository.deleteJobInstance(jobExplorer.getJobInstance(instanceId));
        jdbc.update("DELETE FROM ranking_snapshot_input WHERE snapshot_id = ?", PROBE_SNAPSHOT_ID);
        if (probeTableCreated) {
            jdbc.execute("DROP TABLE " + RankingSchemaSupport.PROBE_TABLE);
        } else {
            jdbc.update("DELETE FROM " + RankingSchemaSupport.PROBE_TABLE + " WHERE snapshot_id = ?", PROBE_SNAPSHOT_ID);
        }
        assertThat(jobExplorer.getJobInstance(instanceId)).isNull();
        assertThat(countBySnapshot(jdbc, "ranking_snapshot_input", PROBE_SNAPSHOT_ID)).isZero();
        log("cleanup: instance " + instanceId + " deleted, probe input removed, probeTableDropped=" + probeTableCreated);
    }

    @DisplayName("weeklyRanking: 원문 4행의 정상 r1 과, 상품 9 직전 실패 뒤 같은 instance 로 9부터 이어간 r2 의 입력·점수·순위가 같고 실패 중에는 r1 공개가 유지된다.")
    @Test
    void weeklyRankingRestartsCalculationFromThirdProduct() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        RankingSchemaSupport.createIfAbsent(
            dataSource, RankingSchemaSupport.SOURCE_TABLE, "fixtures/weekly-ranking-source-schema.sql");
        RankingSchemaSupport.assertMatches(jdbc, RankingSchemaSupport.SOURCE);
        WeeklyRankingRequest normal = WeeklyRankingRequest.of("2026-08-17", 1L, "weekly-20260817-r1");
        WeeklyRankingRequest restartTarget = WeeklyRankingRequest.of("2026-08-17", 2L, "weekly-20260817-r2");

        // 남은 source·snapshot 이 있으면 지우지 않고 실패한다.
        assertThat(countSourceOrders(jdbc, 42, 43, 44)).isZero();
        assertThat(publication(normal.periodStart())).isNull();
        assertThat(manifest(normal.snapshotId())).isEmpty();
        assertThat(faultPlan.isArmed()).isFalse();
        // 원문 6절 fixture(UTC)
        insertSource(jdbc, 42L, 7L, 2L, LocalDateTime.of(2026, 8, 17, 1, 0));
        insertSource(jdbc, 42L, 8L, 1L, LocalDateTime.of(2026, 8, 17, 1, 0));
        insertSource(jdbc, 43L, 7L, 3L, LocalDateTime.of(2026, 8, 18, 1, 0));
        insertSource(jdbc, 44L, 9L, 5L, LocalDateTime.of(2026, 8, 19, 1, 0));
        Map<Long, BigInteger> expectedScores = Map.of(7L, BigInteger.valueOf(5), 8L, BigInteger.ONE, 9L, BigInteger.valueOf(5));
        List<String> expectedRanking = List.of("7:5:1", "9:5:2", "8:1:3");
        List<Object> completedManifest = List.of("COMPLETED", 4L, 3L, SOURCE_INPUT_CHECKSUM, SOURCE_RESULT_CHECKSUM);

        // 정상 비교본 r1
        faultPlan.takeDelegated();
        JobExecution normalRun = jobLauncher.run(weeklyRankingJob, WeeklyRankingParameters.toJobParameters(normal));
        assertThat(normalRun.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(faultPlan.takeDelegated()).containsExactly(7L, 8L, 9L);
        assertThat(manifest(normal.snapshotId())).isEqualTo(completedManifest);
        assertThat(stageScores(normal.snapshotId())).isEqualTo(expectedScores);
        assertThat(rankedResults(normal.snapshotId())).isEqualTo(expectedRanking);
        String r1Publication = publication(normal.periodStart());
        assertThat(r1Publication).startsWith(normal.snapshotId() + "@");

        // r2: 입력 고정 뒤 상품 7·8 chunk commit, 9 직전 한 번 실패
        faultPlan.armBeforeProduct(9L);
        JobParameters restartParams = WeeklyRankingParameters.toJobParameters(restartTarget);
        JobExecution failed = jobLauncher.run(weeklyRankingJob, restartParams);

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(faultPlan.isArmed()).isFalse();
        assertThat(faultPlan.takeDelegated()).containsExactly(7L, 8L);
        assertThat(persistedStepStatuses(jobExplorer, failed)).containsExactly(
            Map.entry(WeeklyRankingJobConfig.FREEZE_STEP, BatchStatus.COMPLETED),
            Map.entry(WeeklyRankingJobConfig.CALCULATE_STEP, BatchStatus.FAILED));
        int failedReadCount = persistedStep(jobExplorer, failed, WeeklyRankingJobConfig.CALCULATE_STEP)
            .getExecutionContext().getInt(RANKING_READ_COUNT_KEY);
        assertThat(failedReadCount).isEqualTo(2);
        assertThat(stageScores(restartTarget.snapshotId())).isEqualTo(Map.of(7L, BigInteger.valueOf(5), 8L, BigInteger.ONE));
        assertThat(manifest(restartTarget.snapshotId()))
            .isEqualTo(Arrays.asList("READY", 4L, 3L, SOURCE_INPUT_CHECKSUM, null));
        assertThat(rankedResults(restartTarget.snapshotId())).isEmpty();
        assertThat(publication(normal.periodStart())).as("실패 중에는 기존 공개본 유지").isEqualTo(r1Publication);
        assertThat(persistedJobStatus(failed.getId())).isEqualTo("FAILED");
        log("weeklyRanking normal r1 executionId=" + normalRun.getId() + " COMPLETED publication=" + r1Publication
            + "; r2 first instanceId=" + failed.getJobInstance().getId() + " executionId=" + failed.getId()
            + " status=" + failed.getStatus() + " " + RANKING_READ_COUNT_KEY + "=" + failedReadCount
            + " stage=" + stageScores(restartTarget.snapshotId()));

        // 같은 식별 parameter 로 재실행. 입력·stage·metadata 는 그대로 둔다
        JobExecution restarted = jobLauncher.run(weeklyRankingJob, restartParams);

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted.getJobInstance().getId()).isEqualTo(failed.getJobInstance().getId());
        assertThat(restarted.getId()).isNotEqualTo(failed.getId());
        assertThat(faultPlan.takeDelegated()).containsExactly(9L);
        assertThat(persistedStepStatuses(jobExplorer, restarted)).as("완료한 입력 고정 Step 은 다시 실행하지 않는다")
            .containsExactly(
                Map.entry(WeeklyRankingJobConfig.CALCULATE_STEP, BatchStatus.COMPLETED),
                Map.entry(WeeklyRankingJobConfig.PUBLISH_STEP, BatchStatus.COMPLETED));
        assertThat(persistedStep(jobExplorer, restarted, WeeklyRankingJobConfig.PUBLISH_STEP).getExecutionContext()
            .getString(PublishWeeklyRankingTasklet.OUTCOME_KEY)).isEqualTo("PUBLISHED");
        assertThat(stageScores(restartTarget.snapshotId())).isEqualTo(expectedScores);
        assertThat(rankedResults(restartTarget.snapshotId())).isEqualTo(expectedRanking)
            .isEqualTo(rankedResults(normal.snapshotId()));
        assertThat(manifest(restartTarget.snapshotId())).isEqualTo(completedManifest);
        assertThat(countBySnapshot(jdbc, "ranking_snapshot_input", restartTarget.snapshotId())).isEqualTo(4);
        assertThat(publication(normal.periodStart())).startsWith(restartTarget.snapshotId() + "@");
        assertThat(persistedJobStatus(restarted.getId())).isEqualTo("COMPLETED");
        log("weeklyRanking r2 restart instanceId=" + restarted.getJobInstance().getId() + " executionId="
            + restarted.getId() + " status=" + restarted.getStatus() + " steps=" + persistedStepStatuses(jobExplorer, restarted)
            + " ranking=" + rankedResults(restartTarget.snapshotId()) + " publication=" + publication(normal.periodStart())
            + " inputChecksum=" + SOURCE_INPUT_CHECKSUM + " resultChecksum=" + SOURCE_RESULT_CHECKSUM);
    }

    /** 원문 3절 helper 구성. 호출마다 새 reader·step·job 을 만들며 실패 스위치는 parameter 가 아니다. */
    private Job probe(JobParameters params, boolean failOnNine, Observation observation) {
        String snapshot = params.getString("snapshotId");
        var reader = new JdbcCursorItemReaderBuilder<Long>()
            .name("purchaseProductProbe").saveState(true)
            .dataSource(dataSource)
            .sql("select distinct product_id from ranking_snapshot_input "
                + "where snapshot_id = ? order by product_id")
            .preparedStatementSetter(ps -> ps.setString(1, snapshot))
            .rowMapper((rs, n) -> rs.getLong("product_id"))
            .build();
        var jdbc = new JdbcTemplate(dataSource);
        var step = new StepBuilder("probeProducts", jobRepository)
            .<Long, Long>chunk(2, transactionManager)
            .reader(reader)
            .processor((ItemProcessor<Long, Long>) id -> {
                observation.processed.add(id);
                if (failOnNine && id == 9L) {
                    throw new IllegalStateException("before third product");
                }
                return id;
            })
            .writer(items -> {
                observation.writerInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.hasResource(dataSource));
                for (Long id : items) {
                    jdbc.update("insert into batch_restart_probe "
                        + "(snapshot_id, product_id) values (?, ?)", snapshot, id);
                    observation.written.add(id);
                }
            })
            .build();
        return new JobBuilder("purchaseInputProbe", jobRepository).start(step).build();
    }

    private List<Long> probeRows() {
        return freshJdbc().queryForList("SELECT product_id FROM batch_restart_probe WHERE snapshot_id = ? "
            + "ORDER BY product_id", Long.class, PROBE_SNAPSHOT_ID);
    }

    private static void insertInput(
        JdbcTemplate jdbc, String snapshotId, long orderId, long productId, long quantity, LocalDateTime confirmedAt
    ) {
        jdbc.update("INSERT INTO ranking_snapshot_input "
            + "(snapshot_id, order_id, product_id, quantity, confirmed_at, created_at, updated_at, deleted_at) "
            + "VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL)",
            snapshotId, orderId, productId, quantity, confirmedAt);
    }

    private static void insertStage(JdbcTemplate jdbc, long productId, BigInteger score) {
        jdbc.update("INSERT INTO ranking_score_stage (snapshot_id, product_id, score, created_at, updated_at, deleted_at) "
            + "VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL)",
            JDBC_CHECK_SNAPSHOT_ID, productId, new BigDecimal(score));
    }

    private static void log(String message) {
        System.out.println("[ranking-probe] " + message);
    }

    private static final class Observation {
        private final List<Long> processed = new ArrayList<>();
        private final List<Long> written = new ArrayList<>();
        private final List<Boolean> writerInTransaction = new ArrayList<>();
    }
}
