package com.loopers.job.ranking;

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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 MySQL·영속 JobRepository 에서의 작은 재시작 probe(Implementation Quest 3절). 점수·순위·공개 검증이 아니다.
 * 두 실행 사이 입력·확인 행·Batch metadata 를 유지하며, 테스트를 자동 rollback TX 로 감싸지 않는다.
 */
@SpringBootTest(properties = {
    "spring.batch.job.name=weeklyRanking",
    "spring.batch.job.enabled=false"
})
class RankingJobRestartTest {

    private static final String PROBE_SNAPSHOT_ID = "probe-1";
    private static final String JDBC_CHECK_SNAPSHOT_ID = "probe-jdbc-1";
    private static final String READ_COUNT_KEY = "purchaseProductProbe.read.count";

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

        StepExecution firstStep = persistedStep(first);
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

        StepExecution secondStep = persistedStep(second);
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

    /** 메모리의 실행 객체가 아니라 JobExplorer 로 영속 StepExecution 을 다시 읽는다. */
    private StepExecution persistedStep(JobExecution execution) {
        long stepExecutionId = execution.getStepExecutions().iterator().next().getId();
        return jobExplorer.getStepExecution(execution.getId(), stepExecutionId);
    }

    private List<Long> probeRows() {
        return freshJdbc().queryForList("SELECT product_id FROM batch_restart_probe WHERE snapshot_id = ? "
            + "ORDER BY product_id", Long.class, PROBE_SNAPSHOT_ID);
    }

    private String persistedJobStatus(long executionId) {
        return freshJdbc().queryForObject("SELECT STATUS FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = ?",
            String.class, executionId);
    }

    /** 애플리케이션 pool 이 아닌 새 JDBC 연결. */
    private static JdbcTemplate freshJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
            System.getProperty("datasource.mysql-jpa.main.jdbc-url"),
            System.getProperty("datasource.mysql-jpa.main.username"),
            System.getProperty("datasource.mysql-jpa.main.password")));
    }

    private static long countBySnapshot(JdbcTemplate jdbc, String table, String snapshotId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE snapshot_id = ?", Long.class, snapshotId);
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
