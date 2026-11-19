package com.loopers.batch.job.ranking.coordination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loopers.infrastructure.persistence.ranking.JdbcWeeklyRankingOrderPartitionInputs;
import com.loopers.support.preservation.PreservedMySql;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.explore.support.JobExplorerFactoryBean;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class WeeklyOrderPartitionLaunchFailureTest {
    @Test
    @DisplayName("상주 주문 파티션의 Batch 생성 트랜잭션은 다른 주문 파티션의 부재 키까지 잠그지 않도록 READ_COMMITTED를 사용한다")
    void scopesJobCreationIsolationToSchedulerProfile() throws Exception {
        var properties = new YamlPropertySourceLoader().load("scheduler", new ClassPathResource("application-ranking-scheduler.yml"));
        assertThat(properties.getFirst().getProperty("spring.batch.jdbc.isolation-level-for-create")).isEqualTo("READ_COMMITTED");
    }

    @Test
    @DisplayName("실행 metadata가 없는 MySQL 교착 롤백만 세 번까지 재시도하고 타임아웃과 기존 증거는 보류한다")
    void boundsRolledBackLaunchesAndRetainsUncertainClaims() throws Exception {
        try (var database = new PreservedMySql()) {
            database.prepareCommerceSchema();
            try (var connection = database.connect()) {
                for (var name : List.of("001-batch-metadata-mysql.sql", "014-weekly-ranking-snapshots.sql",
                    "015-weekly-ranking-scores.sql", "016-weekly-ranking-publication.sql", "017-weekly-ranking-plans.sql",
                    "018-weekly-ranking-partial-scores.sql")) {
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource("preservation/" + name));
                }
            }
            DataSource source = new DriverManagerDataSource(database.jdbcUrl(), "application", "application");
            var jdbc = new JdbcTemplate(source);
            var manager = new DataSourceTransactionManager(source);
            var factory = new JobRepositoryFactoryBean();
            factory.setDataSource(source);
            factory.setTransactionManager(manager);
            factory.setDatabaseType("MYSQL");
            factory.afterPropertiesSet();
            var jobs = factory.getObject();
            var store = new WeeklyPlanStore(jdbc, manager);
            var timeout = Duration.ofMinutes(5);
            store.register("node-a", 2, timeout);
            var job = mock(Job.class);
            when(job.getName()).thenReturn("weeklyRankingOrderPartition");
            var launcher = mock(JobLauncher.class);
            var deadlock = new CannotAcquireLockException("job instance insert", new SQLException("deadlock", "40001", 1213));
            var ambiguous = new CannotAcquireLockException("unconfirmed outcome", new SQLException("lock wait timeout", "HY000", 1205));
            when(launcher.run(eq(job), any())).thenThrow(deadlock, deadlock, deadlock, ambiguous, deadlock, deadlock);
            var explorer = new JobExplorerFactoryBean();
            explorer.setDataSource(source);
            explorer.setTransactionManager(manager);
            explorer.afterPropertiesSet();
            var runs = new WeeklyOrderPartitionExecutions(job, launcher, explorer.getObject(), jobs, jdbc, manager,
                new OrderPartitionRestartEvidence(jdbc, jobs, new JdbcWeeklyRankingOrderPartitionInputs(source)), timeout);
            var week = LocalDate.of(2026, 8, 17);
            var snapshot = store.ensurePlan(week, 1, timeout, false);
            for (int attempt = 1; attempt <= 3; attempt++) {
                var claim = store.claim("node-a", timeout).orElseThrow();
                assertThat(claim.snapshot()).isEqualTo(snapshot);
                assertThat(claim.generation()).isEqualTo(attempt);
                runs.run(claim);
                assertThat(state(jdbc, claim)).isEqualTo(attempt < 3 ? "READY" : "RECOVERY_REQUIRED");
            }
            store.ensurePlan(week.plusWeeks(1), 1, timeout, false);
            var uncertain = store.claim("node-a", timeout).orElseThrow();
            runs.run(uncertain);
            assertThat(state(jdbc, uncertain)).isEqualTo("RECOVERY_REQUIRED");

            store.ensurePlan(week.plusWeeks(2), 1, timeout, false);
            var bound = store.claim("node-a", timeout).orElseThrow();
            jdbc.update("UPDATE weekly_ranking_order_partitions SET execution_id=42 WHERE snapshot_id=?", bound.snapshot().toString());
            runs.run(bound);
            assertThat(state(jdbc, bound)).isEqualTo("RECOVERY_REQUIRED");

            store.ensurePlan(week.plusWeeks(3), 1, timeout, false);
            var frozen = store.claim("node-a", timeout).orElseThrow();
            jdbc.update("""
                UPDATE weekly_ranking_order_partitions SET frozen_at=UTC_TIMESTAMP(6),input_count=0,input_checksum=REPEAT('0',64)
                WHERE snapshot_id=?
                """, frozen.snapshot().toString());
            runs.run(frozen);
            assertThat(state(jdbc, frozen)).isEqualTo("RECOVERY_REQUIRED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM BATCH_JOB_INSTANCE", Integer.class)).isZero();

            store.ensurePlan(week.plusWeeks(4), 1, timeout, false);
            var started = store.claim("node-a", timeout).orElseThrow();
            doAnswer(call -> {
                jobs.createJobExecution(job.getName(), call.getArgument(1, JobParameters.class));
                throw deadlock;
            }).when(launcher).run(eq(job), any());
            runs.run(started);
            assertThat(state(jdbc, started)).isEqualTo("RECOVERY_REQUIRED");
            jdbc.update("UPDATE weekly_ranking_members SET heartbeat_at='2000-01-01' WHERE node_id='node-a'");
            runs.reconcileAll();
            assertThat(state(jdbc, started)).isEqualTo("RECOVERY_REQUIRED");
            assertThat(jdbc.queryForList("SELECT STATUS FROM BATCH_JOB_EXECUTION", String.class)).containsExactly("STARTING");
            assertThat(jdbc.queryForObject("SELECT execution_id FROM weekly_ranking_order_partitions WHERE snapshot_id=?",
                Long.class, started.snapshot().toString())).isEqualTo(jdbc.queryForObject(
                    "SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION", Long.class));

            store.heartbeat("node-a", 2);
            store.ensurePlan(week.plusWeeks(5), 1, timeout, false);
            var obsolete = store.claim("node-a", timeout).orElseThrow();
            doAnswer(call -> {
                jdbc.update("UPDATE weekly_ranking_order_partitions SET generation=generation+1 WHERE snapshot_id=?",
                    obsolete.snapshot().toString());
                throw deadlock;
            }).when(launcher).run(eq(job), any());
            runs.run(obsolete);
            assertThat(state(jdbc, obsolete)).isEqualTo("RUNNING");
        }
    }

    private String state(JdbcTemplate jdbc, WeeklyPlanStore.Claim claim) {
        return jdbc.queryForObject("SELECT state FROM weekly_ranking_order_partitions WHERE snapshot_id=? AND order_partition_id=?",
            String.class, claim.snapshot().toString(), claim.orderPartitionId());
    }
}
