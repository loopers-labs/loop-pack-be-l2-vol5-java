package com.loopers.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.domain.ranking.model.vo.RankingPeriod;
import com.loopers.support.preservation.PreservedMySql;
import com.loopers.support.preservation.PreservationContexts;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WeeklyRankingOrderPartitionJobTest {
    private PreservedMySql database;

    @BeforeAll
    void prepare() throws Exception {
        database = new PreservedMySql();
        database.prepareCommerceSchema();
        try (var connection = database.connect()) {
            for (String script : List.of("preservation/001-batch-metadata-mysql.sql",
                "preservation/014-weekly-ranking-snapshots.sql", "preservation/015-weekly-ranking-scores.sql",
                "preservation/016-weekly-ranking-publication.sql", "preservation/017-weekly-ranking-plans.sql",
                "preservation/018-weekly-ranking-partial-scores.sql", "ranking/purchase-input-probe.sql")) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(script));
            }
        }
    }

    @AfterAll
    void close() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("고정된 주문 파티션의 상품을 병렬 처리하고 실패한 로컬 상품 파티션만 새 소유자로 이어서 계산한다")
    void restartsOnlyIncompleteLocalPartition() throws Exception {
        var id = UUID.randomUUID().toString();
        JobExecution failed;
        try (var first = start()) {
            var job = first.getBean("weeklyRankingOrderPartition", Job.class);
            var jdbc = new JdbcTemplate(first.getBean(DataSource.class));
            jdbc.update("""
                INSERT INTO weekly_ranking_snapshots(snapshot_id,period_start,period_end,revision,state,input_contract_version)
                VALUES (?, '2026-08-17', '2026-08-24', 1, 'FREEZING', 3)
                """, id);
            jdbc.update("""
                INSERT INTO weekly_ranking_plans(snapshot_id,period_start,revision,order_partition_count,state)
                VALUES (?, '2026-08-17', 1, 1, 'OPEN')
                """, id);
            jdbc.update("""
                INSERT INTO weekly_ranking_order_partitions
                    (snapshot_id,order_partition_id,order_min,order_max,is_empty,preferred_node,local_grid,state,owner_id,generation)
                VALUES (?,0,NULL,NULL,FALSE,'node-a',2,'RUNNING','node-a',1)
                """, id);
            CommonRankingInputFixture.copyAndSeal(jdbc, id, new RankingPeriod(LocalDate.parse("2026-08-17")), List.of(), 1);
            try (var admin = database.connectAsAdministrator(); var ddl = admin.createStatement()) {
                ddl.execute("""
                    CREATE TRIGGER fail_checkpoint BEFORE UPDATE ON BATCH_STEP_EXECUTION_CONTEXT FOR EACH ROW
                    BEGIN
                      IF EXISTS(SELECT 1 FROM BATCH_STEP_EXECUTION WHERE STEP_EXECUTION_ID=NEW.STEP_EXECUTION_ID
                                AND STEP_NAME='scoreWeeklyRankingLocalProductPartition:localProductPartition-0')
                         AND EXISTS(SELECT 1 FROM weekly_ranking_partial_scores WHERE snapshot_id='%s' AND order_partition_id=0 AND product_id=8)
                      THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fail after score before checkpoint commit'; END IF;
                    END
                    """.formatted(id));
            }
            failed = first.getBean(JobLauncher.class).run(job, parameters(id, "node-a", 1));
            assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(products(jdbc, id)).containsExactly(7L, 9L);
            var incomplete = failed.getStepExecutions().stream().filter(step -> step.getStepName().equals("scoreWeeklyRankingLocalProductPartition:localProductPartition-0"))
                .findFirst().orElseThrow();
            var persisted = first.getBean(JobExplorer.class).getStepExecution(failed.getId(), incomplete.getId());
            assertThat(persisted.getExecutionContext().getInt("weeklyRankingLocalProductPartitionProducts.read.count")).isOne();
            assertThat(jdbc.queryForObject("SELECT input_count FROM weekly_ranking_order_partitions WHERE snapshot_id=? AND order_partition_id=0",
                Long.class, id)).isEqualTo(4);
            try (var admin = database.connectAsAdministrator(); var ddl = admin.createStatement()) {
                ddl.execute("DROP TRIGGER fail_checkpoint");
            }
            jdbc.update("""
                INSERT INTO product_purchase_activities(inbox_id,order_id,product_id,quantity,confirmed_at)
                VALUES (44,44,10,20,'2026-08-19 01:00:00')
                """);
            jdbc.update("UPDATE weekly_ranking_order_partitions SET owner_id='node-b',generation=2,execution_id=NULL WHERE snapshot_id=? AND order_partition_id=0", id);
        }
        try (var second = start()) {
            var jdbc = new JdbcTemplate(second.getBean(DataSource.class));
            var run = second.getBean(JobLauncher.class).run(second.getBean("weeklyRankingOrderPartition", Job.class), parameters(id, "node-b", 2));
            assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(run.getJobInstance().getId()).isEqualTo(failed.getJobInstance().getId());
            var durable = second.getBean(JobExplorer.class).getJobExecution(run.getId());
            assertThat(durable.getStepExecutions()).extracting(StepExecution::getStepName)
                .contains("scoreWeeklyRankingLocalProductPartition:localProductPartition-0").doesNotContain("scoreWeeklyRankingLocalProductPartition:localProductPartition-1", "verifyWeeklyRankingOrderPartitionInputs");
            var resumed = durable.getStepExecutions().stream().filter(step -> step.getStepName().equals("scoreWeeklyRankingLocalProductPartition:localProductPartition-0"))
                .findFirst().orElseThrow();
            assertThat(resumed.getReadCount()).isOne();
            assertThat(resumed.getWriteCount()).isOne();
            assertThat(products(jdbc, id)).containsExactly(7L, 8L, 9L);
            assertThat(jdbc.queryForList("SELECT score FROM weekly_ranking_partial_scores WHERE snapshot_id=? ORDER BY product_id", Long.class, id))
                .containsExactly(5L, 1L, 5L);
        }
    }

    @Test
    @DisplayName("이전 독립 고정 계약의 미완료 주문 파티션은 새 Job이 입력을 복사하거나 실행권을 연결할 수 없다")
    void rejectsOpenLegacyPlan() throws Exception {
        var id = UUID.randomUUID().toString();
        try (var context = start()) {
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            jdbc.update("""
                INSERT INTO weekly_ranking_snapshots(snapshot_id,period_start,period_end,revision,state,input_contract_version)
                VALUES (?, '2026-08-31', '2026-09-07', 1, 'FREEZING', 2)
                """, id);
            jdbc.update("""
                INSERT INTO weekly_ranking_plans(snapshot_id,period_start,revision,order_partition_count,state)
                VALUES (?, '2026-08-31', 1, 1, 'OPEN')
                """, id);
            jdbc.update("""
                INSERT INTO weekly_ranking_order_partitions(snapshot_id,order_partition_id,preferred_node,local_grid,state,owner_id,generation)
                VALUES (?,0,'a',1,'RUNNING','a',1)
                """, id);
            var run = context.getBean(JobLauncher.class).run(context.getBean("weeklyRankingOrderPartition", Job.class),
                parameters(id, 0, "2026-08-31", "a", 1));
            assertThat(run.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(run.getStepExecutions()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT execution_id FROM weekly_ranking_order_partitions WHERE snapshot_id=?", Long.class, id))
                .isNull();
            assertThat(jdbc.queryForObject("SELECT state FROM weekly_ranking_plans WHERE snapshot_id=?", String.class, id)).isEqualTo("OPEN");
            assertThat(jdbc.queryForObject("SELECT frozen_at FROM weekly_ranking_snapshots WHERE snapshot_id=?", String.class, id)).isNull();
        }
    }

    private List<Long> products(JdbcTemplate jdbc, String id) {
        return jdbc.queryForList("SELECT product_id FROM weekly_ranking_partial_scores WHERE snapshot_id=? ORDER BY product_id", Long.class, id);
    }

    private JobParameters parameters(String id, String owner, long generation) {
        return parameters(id, 0, "2026-08-17", owner, generation);
    }

    private JobParameters parameters(String id, long orderPartitionId, String start, String owner, long generation) {
        return new JobParametersBuilder().addString("snapshotId", id).addLong("orderPartitionId", orderPartitionId)
            .addString("periodStart", start, false).addLong("revision", 1L, false)
            .addString("ownerId", owner, false).addLong("generation", generation, false).toJobParameters();
    }

    private ConfigurableApplicationContext start() {
        var args = Arrays.stream(database.arguments("--spring.batch.job.name=weeklyRankingOrderPartition", "--ranking.weekly.chunk-size=1",
            "--ranking.weekly.local-workers=2")).map(argument -> argument.startsWith("--datasource.mysql-jpa.main.maximum-pool-size=")
                ? "--datasource.mysql-jpa.main.maximum-pool-size=8" : argument).toArray(String[]::new);
        return PreservationContexts.start(Configuration.class, args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ComponentScan({"com.loopers.batch.job.ranking.orderpartition", "com.loopers.config.jpa", "com.loopers.application.ranking",
        "com.loopers.infrastructure.persistence.ranking"})
    static class Configuration {}
}
