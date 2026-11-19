package com.loopers.batch.job.ranking.coordination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.loopers.infrastructure.persistence.ranking.JdbcWeeklyRankingOrderPartitionInputs;
import com.loopers.support.preservation.PreservedMySql;
import com.loopers.support.preservation.PreservationContexts;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderPartitionRestartBoundsTest {
    private static final Duration TIMEOUT = Duration.ofMinutes(30);
    private static final String WORKER = "scoreWeeklyRankingLocalProductPartition:localProductPartition-0";
    private static final String POSITION = "weeklyRankingLocalProductPartitionProducts.read.count";
    private PreservedMySql database;
    private ConfigurableApplicationContext application;
    private JdbcTemplate jdbc;
    private Job job;
    private JobLauncher launcher;
    private JobRepository jobs;
    private JobExplorer explorer;
    private WeeklyPlanStore store;
    private WeeklyOrderPartitionExecutions runs;
    private int revision;

    @BeforeAll
    void prepare() throws Exception {
        database = new PreservedMySql();
        database.prepareCommerceSchema();
        try (var connection = database.connectAsAdministrator(); var ddl = connection.createStatement()) {
            for (var script : List.of("preservation/001-batch-metadata-mysql.sql", "preservation/014-weekly-ranking-snapshots.sql",
                "preservation/015-weekly-ranking-scores.sql", "preservation/016-weekly-ranking-publication.sql",
                "preservation/017-weekly-ranking-plans.sql", "preservation/018-weekly-ranking-partial-scores.sql",
                "ranking/purchase-input-probe.sql")) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(script));
            }
            ddl.execute("CREATE TABLE bounds_test_failures(snapshot_id CHAR(36) PRIMARY KEY)");
            ddl.execute("""
                CREATE TRIGGER fail_second_product BEFORE INSERT ON weekly_ranking_partial_scores FOR EACH ROW
                BEGIN
                    IF NEW.product_id>7 AND EXISTS(SELECT 1 FROM bounds_test_failures WHERE snapshot_id=NEW.snapshot_id)
                    THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fail second product after first committed chunk'; END IF;
                END
                """);
        }

        var args = Arrays.stream(database.arguments("--spring.batch.job.name=weeklyRankingOrderPartition",
            "--ranking.weekly.chunk-size=1", "--ranking.weekly.local-workers=1"))
            .map(arg -> arg.startsWith("--datasource.mysql-jpa.main.maximum-pool-size=")
                ? "--datasource.mysql-jpa.main.maximum-pool-size=8" : arg).toArray(String[]::new);
        application = PreservationContexts.start(Configuration.class, args);
        jdbc = new JdbcTemplate(application.getBean(DataSource.class));
        job = application.getBean("weeklyRankingOrderPartition", Job.class);
        launcher = application.getBean(JobLauncher.class);
        jobs = application.getBean(JobRepository.class);
        explorer = application.getBean(JobExplorer.class);
        var transactions = application.getBean(PlatformTransactionManager.class);
        store = new WeeklyPlanStore(jdbc, transactions);
        store.register("other-node", 1, TIMEOUT);
        var inputs = application.getBean(JdbcWeeklyRankingOrderPartitionInputs.class);
        runs = new WeeklyOrderPartitionExecutions(job, launcher, explorer, jobs, jdbc, transactions,
            new OrderPartitionRestartEvidence(jdbc, jobs, inputs), TIMEOUT);
    }

    @AfterAll
    void close() {
        if (application != null) {
            application.close();
        }
        if (database != null) {
            database.close();
        }
    }

    static Stream<Arguments> boundsCases() {
        return Stream.of(0, 1).flatMap(partition -> Stream.concat(
            Stream.of(Route.AUTOMATIC, Route.MANUAL_HELD).flatMap(route ->
                Arrays.stream(Edit.values()).map(edit -> Arguments.of(partition, route, edit))),
            Stream.of(Arguments.of(partition, Route.MANUAL_RUNNING, Edit.MISSING))));
    }

    @ParameterizedTest(name = "주문 파티션 {0}, 복구 {1}, 체크포인트 {2}")
    @MethodSource("boundsCases")
    @DisplayName("실패한 로컬 상품 Step의 주문 경계가 영속 계획과 값·타입까지 일치할 때만 같은 JobInstance를 재실행한다")
    void verifiesPersistedOrderBounds(int partition, Route route, Edit edit) throws Exception {
        var snapshot = UUID.randomUUID();
        String id = snapshot.toString();
        String owner = "bounds-" + ++revision;
        createPlan(id, partition, owner);
        var initial = store.claim(owner, TIMEOUT).orElseThrow();
        assertThat(initial.snapshot()).isEqualTo(snapshot);
        assertThat(initial.orderPartitionId()).isEqualTo(partition);
        var failed = failSecondChunk(initial);
        var step = jobs.getLastStepExecution(failed.getJobInstance(), WORKER);
        assertThat(step.getStatus()).isEqualTo(BatchStatus.FAILED);
        var context = step.getExecutionContext();
        String finite = partition == 0 ? "maxOrderId" : "minOrderId";
        String open = partition == 0 ? "minOrderId" : "maxOrderId";
        assertThat(context.get(finite)).isEqualTo(43L);
        assertThat(context.containsKey(open)).isFalse();
        assertThat(context.getInt(POSITION)).isOne();
        assertThat(partialProducts(id)).containsExactly(7L);

        switch (edit) {
            case MISSING -> context.remove(finite);
            case WRONG_VALUE -> context.putLong(finite, 44L);
            case SAME_INTEGER -> context.putInt(finite, 43);
            case SAME_STRING -> context.putString(finite, "43");
            case UNEXPECTED_OPEN_VALUE -> context.putLong(open, 42L);
            case UNCHANGED -> { }
        }
        jobs.updateExecutionContext(step);
        var persisted = explorer.getStepExecution(failed.getId(), step.getId()).getExecutionContext();
        assertThat(persisted.toMap()).isEqualTo(context.toMap());
        long instance = failed.getJobInstance().getId();
        int before = executionCount(instance);
        assertThat(before).isOne();

        Throwable rejection = null;
        if (route == Route.AUTOMATIC) {
            runs.reconcileAll();
        } else {
            if (route == Route.MANUAL_HELD) {
                jdbc.update("""
                    UPDATE weekly_ranking_order_partitions SET state='RECOVERY_REQUIRED',last_error='OPERATOR_REVIEW'
                    WHERE snapshot_id=? AND order_partition_id=?
                    """, id, partition);
            }
            rejection = catchThrowable(() -> runs.recover(snapshot, partition, owner, initial.generation(), failed.getId(),
                true, "confirmed fixture termination"));
        }
        String stateAfterValidation = state(id, partition);
        var retry = store.claim(owner, TIMEOUT);
        retry.ifPresent(claim -> {
            assertThat(claim.snapshot()).isEqualTo(snapshot);
            assertThat(claim.orderPartitionId()).isEqualTo(partition);
            runs.run(claim);
        });
        int after = executionCount(instance);
        String finalState = state(id, partition);
        var products = partialProducts(id);
        System.out.printf("BOUNDS partition=%d route=%s edit=%s job=%d step=%d min=%s max=%s saved=%d "
                + "validation=%s final=%s executions=%d->%d products=%s%n",
            partition, route, edit, failed.getId(), step.getId(), typed(persisted, "minOrderId"),
            typed(persisted, "maxOrderId"), persisted.getInt(POSITION), stateAfterValidation, finalState, before, after, products);
        if (edit == Edit.UNCHANGED) {
            assertThat(rejection).isNull();
            assertThat(stateAfterValidation).isEqualTo("READY");
            assertThat(finalState).isEqualTo("DONE");
            assertThat(after - before).isOne();
            assertThat(products).containsExactly(7L, partition == 0 ? 8L : 9L);
        } else {
            assertThat(stateAfterValidation).isEqualTo("RECOVERY_REQUIRED");
            assertThat(retry).isEmpty();
            assertThat(after - before).isZero();
            assertThat(products).containsExactly(7L);
            assertThat(jdbc.queryForObject("SELECT state FROM weekly_ranking_plans WHERE snapshot_id=?", String.class, id))
                .isEqualTo("OPEN");
            assertThat(jdbc.queryForObject("""
                SELECT execution_id FROM weekly_ranking_order_partitions WHERE snapshot_id=? AND order_partition_id=?
                """, Long.class, id, partition)).isEqualTo(failed.getId());
            assertThat(jdbc.queryForObject("""
                SELECT last_error FROM weekly_ranking_order_partitions WHERE snapshot_id=? AND order_partition_id=?
                """, String.class, id, partition)).isEqualTo("CHECKPOINT_EVIDENCE_MISMATCH");
            if (route != Route.AUTOMATIC) {
                assertThat(rejection).isInstanceOf(IllegalStateException.class);
            }
        }
    }

    private void createPlan(String id, int partition, String owner) {
        store.register(owner, 1, TIMEOUT);
        jdbc.update("""
            INSERT INTO weekly_ranking_snapshots(snapshot_id,period_start,period_end,revision,state,input_contract_version)
            VALUES (?,'2026-08-17','2026-08-24',?,'FREEZING',2)
            """, id, revision);
        jdbc.update("""
            INSERT INTO weekly_ranking_plans(snapshot_id,period_start,revision,order_partition_count,state)
            VALUES (?,'2026-08-17',?,2,'OPEN')
            """, id, revision);
        jdbc.update("""
            INSERT INTO weekly_ranking_order_partitions(snapshot_id,order_partition_id,order_min,order_max,preferred_node,local_grid)
            VALUES (?,0,NULL,43,?,1),(?,1,43,NULL,?,1)
            """, id, partition == 0 ? owner : "other-node", id, partition == 1 ? owner : "other-node");
    }

    private JobExecution failSecondChunk(WeeklyPlanStore.Claim claim) throws Exception {
        String id = claim.snapshot().toString();
        jdbc.update("INSERT INTO bounds_test_failures(snapshot_id) VALUES (?)", id);
        var parameters = new JobParametersBuilder().addString("snapshotId", id).addLong("orderPartitionId", (long) claim.orderPartitionId())
            .addString("periodStart", claim.period().toString(), false).addLong("revision", (long) claim.revision(), false)
            .addString("ownerId", claim.owner(), false).addLong("generation", claim.generation(), false).toJobParameters();
        var failed = launcher.run(job, parameters);
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        jdbc.update("DELETE FROM bounds_test_failures WHERE snapshot_id=?", id);
        return failed;
    }

    private int executionCount(long instance) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM BATCH_JOB_EXECUTION WHERE JOB_INSTANCE_ID=?", Integer.class, instance);
    }

    private String state(String id, int partition) {
        return jdbc.queryForObject("SELECT state FROM weekly_ranking_order_partitions WHERE snapshot_id=? AND order_partition_id=?",
            String.class, id, partition);
    }

    private List<Long> partialProducts(String id) {
        return jdbc.queryForList("SELECT product_id FROM weekly_ranking_partial_scores WHERE snapshot_id=? ORDER BY product_id",
            Long.class, id);
    }

    private String typed(ExecutionContext context, String key) {
        Object value = context.get(key);
        return value == null ? "absent" : value + "/" + value.getClass().getSimpleName();
    }

    private enum Route { AUTOMATIC, MANUAL_HELD, MANUAL_RUNNING }

    private enum Edit { UNCHANGED, MISSING, WRONG_VALUE, SAME_INTEGER, SAME_STRING, UNEXPECTED_OPEN_VALUE }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ComponentScan({"com.loopers.batch.job.ranking.orderpartition", "com.loopers.config.jpa", "com.loopers.application.ranking",
        "com.loopers.infrastructure.persistence.ranking"})
    static class Configuration {}
}
