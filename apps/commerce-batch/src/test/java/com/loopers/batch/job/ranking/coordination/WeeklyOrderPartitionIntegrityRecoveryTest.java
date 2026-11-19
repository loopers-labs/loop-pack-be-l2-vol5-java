package com.loopers.batch.job.ranking.coordination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loopers.application.ranking.command.WeeklyRankingOrderPartitionCommand;
import com.loopers.domain.ranking.model.vo.RankingPeriod;
import com.loopers.infrastructure.persistence.ranking.JdbcWeeklyRankingOrderPartitionInputs;
import com.loopers.support.preservation.PreservedMySql;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.support.JobExplorerFactoryBean;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WeeklyOrderPartitionIntegrityRecoveryTest {
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final String JOB_NAME = "weeklyRankingOrderPartition";
    private final AtomicReference<Runnable> beforeEvidenceRead = new AtomicReference<>();
    private PreservedMySql database;
    private JdbcTemplate jdbc;
    private WeeklyPlanStore plans;
    private JobRepository jobs;
    private WeeklyOrderPartitionExecutions runs;
    private JdbcWeeklyRankingOrderPartitionInputs inputs;
    private LocalDate week = LocalDate.of(2026, 8, 17);

    @BeforeAll
    void prepare() throws Exception {
        database = new PreservedMySql();
        database.prepareCommerceSchema();
        try (var connection = database.connect()) {
            for (var ddl : List.of("001-batch-metadata-mysql.sql", "014-weekly-ranking-snapshots.sql",
                "015-weekly-ranking-scores.sql", "016-weekly-ranking-publication.sql", "017-weekly-ranking-plans.sql",
                "018-weekly-ranking-partial-scores.sql")) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("preservation/" + ddl));
            }
        }
        var source = new DriverManagerDataSource(database.jdbcUrl(), "application", "application");
        jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        var repository = new JobRepositoryFactoryBean();
        repository.setDataSource(source);
        repository.setTransactionManager(manager);
        repository.setDatabaseType("MYSQL");
        repository.afterPropertiesSet();
        jobs = repository.getObject();
        var explorer = new JobExplorerFactoryBean();
        explorer.setDataSource(source);
        explorer.setTransactionManager(manager);
        explorer.afterPropertiesSet();
        var proxy = new ProxyFactory(new JdbcWeeklyRankingOrderPartitionInputs(source));
        var transactionAdvice = new TransactionInterceptor();
        transactionAdvice.setTransactionManager(manager);
        transactionAdvice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(transactionAdvice);
        proxy.addAdvice((MethodInterceptor) call -> {
            if (call.getMethod().getName().equals("localProductPartitions")) {
                var action = beforeEvidenceRead.getAndSet(null);
                if (action != null) {
                    action.run();
                }
            }
            return call.proceed();
        });
        inputs = (JdbcWeeklyRankingOrderPartitionInputs) proxy.getProxy();
        plans = new WeeklyPlanStore(jdbc, manager);
        plans.register("node-a", 1, TIMEOUT);
        plans.register("node-b", 1, TIMEOUT);
        var job = mock(Job.class);
        when(job.getName()).thenReturn(JOB_NAME);
        runs = new WeeklyOrderPartitionExecutions(job, mock(JobLauncher.class), explorer.getObject(), jobs, jdbc, manager,
            new OrderPartitionRestartEvidence(jdbc, jobs, inputs), TIMEOUT);
    }

    @AfterEach
    void advanceWeek() {
        jdbc.update("UPDATE weekly_ranking_plans SET state='SUPERSEDED' WHERE state='OPEN'");
        beforeEvidenceRead.set(null);
        week = week.plusWeeks(1);
    }

    @AfterAll
    void close() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("FAILED 실행의 체크섬 훼손을 별도 트랜잭션에 기록하고 다음 tick에서는 같은 실행을 재시작하지 않는다")
    void quarantinesFailedExecutionOnRepeatedTicks() throws Exception {
        var failed = failedExecution(true);
        runs.reconcileAll();
        runs.reconcileAll();
        assertQuarantine(failed);
    }

    @Test
    @DisplayName("운영자가 종료를 확인해도 훼손된 manifest가 있는 실행은 보류 사유를 기록하고 재시작을 거부한다")
    void manualRecoveryPersistsTheReasonThenRejectsRestart() throws Exception {
        var failed = failedExecution(true);
        jdbc.update("UPDATE weekly_ranking_order_partitions SET state='RECOVERY_REQUIRED' WHERE snapshot_id=? AND order_partition_id=0", failed.id());
        var caller = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        caller.executeWithoutResult(status -> {
            assertThatThrownBy(() -> runs.recover(UUID.fromString(failed.id()), 0, "node-a", 1, failed.execution(), true, "종료 확인"))
                .isInstanceOf(RuntimeException.class);
            status.setRollbackOnly();
        });
        assertQuarantine(failed);
    }

    @Test
    @DisplayName("판정 도중 MySQL 연결이 끊기면 계획과 소유권을 유지하고 다음 tick에서 정상 체크포인트를 재확인한다")
    void retriesAfterRealConnectionLoss() throws Exception {
        var failed = failedExecution(false);
        var killedSession = new AtomicLong();
        beforeEvidenceRead.set(() -> {
            long session = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            try (var admin = database.connectAsAdministrator(); var command = admin.createStatement()) {
                command.execute("KILL CONNECTION " + session);
                killedSession.set(session);
            } catch (java.sql.SQLException error) {
                throw new AssertionError(error);
            }
        });
        runs.reconcileAll();
        assertThat(killedSession.get()).isPositive();
        assertThat(states(failed)).containsExactly("RUNNING", "READY");
        assertThat(plan(failed)).isEqualTo("OPEN");
        assertThat(lastError(failed)).isNull();
        assertThat(executionCount(failed)).isOne();
        runs.reconcileAll();
        assertThat(states(failed)).containsExactly("READY", "READY");
        assertThat(plan(failed)).isEqualTo("OPEN");
        assertThat(lastError(failed)).isEqualTo("RETRY_FAILED_EXECUTION");
        assertThat(executionCount(failed)).isOne();
        assertThat(jdbc.queryForObject("SELECT attempts FROM weekly_ranking_order_partitions WHERE snapshot_id=? AND order_partition_id=0",
            Integer.class, failed.id())).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"generation", "binding", "latest", "repaired"})
    @DisplayName("첫 판정 롤백 직후 소유권·연결 실행·최신 실행 또는 manifest가 달라지면 이전 관측으로 격리하지 않는다")
    void rejectsObsoleteAudit(String change) throws Exception {
        var failed = failedExecution(true);
        var intervened = new AtomicBoolean();
        beforeEvidenceRead.set(() -> TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) {
                    throw new AssertionError("first verification did not roll back");
                }
                CompletableFuture.runAsync(() -> updateBetweenTransactions(failed, change)).join();
                intervened.set(true);
            }
        }));
        runs.reconcileAll();
        assertThat(intervened).isTrue();
        assertThat(states(failed)).containsExactly("RUNNING", "READY");
        assertThat(plan(failed)).isEqualTo("OPEN");
        assertThat(lastError(failed)).isNull();
    }

    private void updateBetweenTransactions(Failed failed, String change) {
        switch (change) {
            case "generation" -> jdbc.update("UPDATE weekly_ranking_order_partitions SET generation=2,owner_id='node-b' WHERE snapshot_id=? AND order_partition_id=0", failed.id());
            case "binding" -> jdbc.update("UPDATE weekly_ranking_order_partitions SET execution_id=? WHERE snapshot_id=? AND order_partition_id=0", failed.execution() + 100, failed.id());
            case "latest" -> {
                try {
                    jobs.createJobExecution(JOB_NAME, failed.parameters());
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }
            case "repaired" -> jdbc.update("UPDATE weekly_ranking_order_partitions SET input_checksum=? WHERE snapshot_id=? AND order_partition_id=0", failed.checksum(), failed.id());
            default -> throw new AssertionError(change);
        }
    }

    private Failed failedExecution(boolean corrupt) throws Exception {
        var snapshot = plans.ensurePlan(week, 1, TIMEOUT, false);
        var id = snapshot.toString();
        var parameters = new JobParametersBuilder().addString("snapshotId", id).addLong("orderPartitionId", 0L)
            .addString("periodStart", week.toString(), false).addLong("revision", 1L, false)
            .addString("ownerId", "node-a", false).addLong("generation", 1L, false).toJobParameters();
        var execution = jobs.createJobExecution(JOB_NAME, parameters);
        execution.setStatus(BatchStatus.FAILED);
        execution.setEndTime(LocalDateTime.now());
        jobs.update(execution);
        jdbc.update("""
            UPDATE weekly_ranking_order_partitions SET state='RUNNING',owner_id='node-a',generation=1,execution_id=?,attempts=1
            WHERE snapshot_id=? AND order_partition_id=0
            """, execution.getId(), id);
        inputs.freeze(new WeeklyRankingOrderPartitionCommand(snapshot, 0, new RankingPeriod(week), 1, "node-a", 1));
        var checksum = jdbc.queryForObject("SELECT input_checksum FROM weekly_ranking_order_partitions WHERE snapshot_id=? AND order_partition_id=0", String.class, id);
        if (corrupt) {
            jdbc.update("UPDATE weekly_ranking_order_partitions SET input_checksum=REPEAT('0',64) WHERE snapshot_id=? AND order_partition_id=0", id);
        }
        return new Failed(id, execution.getId(), parameters, checksum);
    }

    private void assertQuarantine(Failed failed) {
        assertThat(states(failed)).containsExactly("CONFLICT", "BLOCKED");
        assertThat(plan(failed)).isEqualTo("CONFLICT");
        assertThat(lastError(failed)).startsWith("FROZEN_INPUT_INTEGRITY_MISMATCH").contains("0".repeat(64), failed.checksum());
        assertThat(executionCount(failed)).isOne();
    }

    private String lastError(Failed failed) {
        return jdbc.queryForObject("SELECT last_error FROM weekly_ranking_order_partitions WHERE snapshot_id=? AND order_partition_id=0",
            String.class, failed.id());
    }

    private long executionCount(Failed failed) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM BATCH_JOB_EXECUTION WHERE JOB_INSTANCE_ID=(
                SELECT JOB_INSTANCE_ID FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID=?)
            """, Long.class, failed.execution());
    }

    private List<String> states(Failed failed) {
        return jdbc.queryForList("SELECT state FROM weekly_ranking_order_partitions WHERE snapshot_id=? ORDER BY order_partition_id", String.class, failed.id());
    }

    private String plan(Failed failed) {
        return jdbc.queryForObject("SELECT state FROM weekly_ranking_plans WHERE snapshot_id=?", String.class, failed.id());
    }

    private record Failed(String id, long execution, JobParameters parameters, String checksum) {}
}