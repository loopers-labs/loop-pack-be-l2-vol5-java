package com.loopers.batch.job.ranking.coordination;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.support.preservation.PreservedMySql;
import com.loopers.support.preservation.PreservationContexts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class WeeklyRankingIntegrityRecoveryTest {
    private static final LocalDate WEEK = LocalDate.of(2026, 8, 17);
    private static final String NODE = "integrity-node";
    private static final Duration TIMEOUT = Duration.ofHours(2);

    @Test
    @DisplayName("손상된 revision 2의 재실행을 차단하고 운영자 요청으로 원본을 다시 고정한 revision 3을 공개한다")
    void quarantinesFrozenInputMismatchAndPublishesRequestedNextRevision() throws Exception {
        try (var database = new PreservedMySql()) {
            database.prepareCommerceSchema();
            try (var connection = database.connect()) {
                for (String file : List.of("001-batch-metadata-mysql.sql", "014-weekly-ranking-snapshots.sql",
                    "015-weekly-ranking-scores.sql", "016-weekly-ranking-publication.sql", "017-weekly-ranking-plans.sql",
                    "018-weekly-ranking-partial-scores.sql")) {
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource("preservation/" + file));
                }
            }
            try (var context = start(database, "--ranking.scheduler.mode=resident")) {
                var jdbc = context.getBean(JdbcTemplate.class);
                var plans = context.getBean(WeeklyPlanStore.class);
                var resident = context.getBean(WeeklySchedulerConfiguration.Resident.class);
                addPurchase(jdbc, 42, 2);
                String first = plans.ensurePlan(WEEK, 1, TIMEOUT, false).toString();
                resident.work();
                resident.maintain();
                var original = observe(jdbc, first, "revision-1-published");
                assertThat(original.plan()).isEqualTo("PUBLISHED");
                assertThat(original.rankings()).containsExactly("7:2:1");
                assertThat(original.pointer()).isEqualTo("1:" + first);

                addPurchase(jdbc, 43, 3);
                String second = plans.ensurePlan(WEEK, 2, TIMEOUT, false).toString();
                var claim = plans.claim(NODE, TIMEOUT).orElseThrow();
                assertThat(claim.snapshot().toString()).isEqualTo(second);
                // Stop between the real worker's terminal write and the resident's reconciliation.
                long execution = runFailedWorker(database, context, claim);
                var failed = observe(jdbc, second, "revision-2-worker-failed");
                assertThat(failed.plan()).isEqualTo("OPEN");
                assertThat(failed.partition()).isEqualTo("RUNNING");
                assertThat(failed.executions()).containsExactly(execution + ":FAILED");
                assertThat(failed.boundExecution()).isEqualTo(execution);
                assertThat(failed.owner()).isEqualTo(NODE);
                assertThat(failed.generation()).isOne();
                assertThat(failed.attempts()).isOne();
                assertThat(failed.frozenAt()).isNotNull();
                assertThat(failed.manifestCount()).isEqualTo(2);
                assertThat(failed.inputs()).containsExactly("42:7:2", "43:7:3");
                assertThat(failed.inputHash()).isEqualTo(failed.manifestHash());
                assertThat(failed.source()).isEqualTo(failed.inputs());
                assertThat(failed.pointer()).isEqualTo(original.pointer());

                assertThat(jdbc.update("""
                    UPDATE weekly_ranking_snapshot_inputs SET quantity=30
                    WHERE snapshot_id=? AND order_id=43 AND product_id=7 AND quantity=3
                    """, second)).isOne();
                var damaged = observe(jdbc, second, "revision-2-input-corrupted");
                assertThat(damaged.inputs()).containsExactly("42:7:2", "43:7:30");
                assertThat(damaged.source()).isEqualTo(failed.source());
                assertThat(damaged.inputHash()).isNotEqualTo(failed.inputHash());
                assertThat(damaged.manifestHash()).isEqualTo(failed.manifestHash());

                resident.maintain();
                resident.work();
                var tickOne = observe(jdbc, second, "revision-2-tick-1");
                resident.maintain();
                resident.work();
                var tickTwo = observe(jdbc, second, "revision-2-tick-2");
                assertThat(tickTwo).isEqualTo(tickOne);
                assertThat(tickTwo.plan()).isEqualTo("CONFLICT");
                assertThat(tickTwo.partition()).isEqualTo("CONFLICT");
                assertThat(tickTwo.reason()).startsWith("FROZEN_INPUT_INTEGRITY_MISMATCH")
                    .contains("expectedCount=2", "actualCount=2", "expectedSha256=" + failed.manifestHash(),
                        "actualSha256=" + damaged.inputHash());
                assertThat(tickTwo.frozenAt()).isEqualTo(failed.frozenAt());
                assertThat(tickTwo.manifestHash()).isEqualTo(failed.manifestHash());
                assertThat(tickTwo.manifestCount()).isEqualTo(failed.manifestCount());
                assertThat(tickTwo.inputHash()).isEqualTo(damaged.inputHash());
                assertThat(tickTwo.executions()).containsExactly(execution + ":FAILED");
                assertThat(tickTwo.generation()).isEqualTo(failed.generation());
                assertThat(tickTwo.attempts()).isEqualTo(failed.attempts());
                assertThat(tickTwo.partial()).isEmpty();
                assertThat(tickTwo.stage()).isEmpty();
                assertThat(tickTwo.rankings()).isEmpty();
                assertThat(tickTwo.completions()).isZero();
                assertThat(tickTwo.pointer()).isEqualTo(original.pointer());

                // Boot the same request-mode ApplicationRunner used by the operator CLI.
                try (var operator = start(database, "--ranking.scheduler.mode=request",
                    "--ranking.scheduler.request-period=" + WEEK, "--ranking.scheduler.request-revision=3")) {
                    assertThat(operator.getBeansOfType(WeeklySchedulerConfiguration.Resident.class)).isEmpty();
                }
                String third = jdbc.queryForObject("SELECT snapshot_id FROM weekly_ranking_plans WHERE period_start=? AND revision=3",
                    String.class, WEEK);
                assertThat(third).isNotIn(first, second);
                var requested = observe(jdbc, third, "revision-3-requested");
                assertThat(requested.plan()).isEqualTo("OPEN");
                assertThat(requested.inputs()).isEmpty();
                assertThat(requested.executions()).isEmpty();
                assertThat(requested.pointer()).isEqualTo(original.pointer());
                resident.work();
                resident.maintain();
                var published = observe(jdbc, third, "revision-3-published");
                assertThat(published.plan()).isEqualTo("PUBLISHED");
                assertThat(published.partition()).isEqualTo("DONE");
                assertThat(published.executions()).singleElement().asString().endsWith(":COMPLETED");
                assertThat(published.manifestCount()).isEqualTo(2);
                assertThat(published.frozenAt()).isNotNull();
                assertThat(published.inputs()).isEqualTo(failed.source());
                assertThat(published.source()).isEqualTo(failed.source());
                assertThat(published.manifestHash()).isEqualTo(failed.manifestHash());
                assertThat(published.inputHash()).isEqualTo(failed.manifestHash());
                assertThat(published.partial()).containsExactly("0:7:5");
                assertThat(published.stage()).containsExactly("7:5");
                assertThat(published.rankings()).containsExactly("7:5:1");
                assertThat(published.completions()).isOne();
                assertThat(published.pointer()).isEqualTo("3:" + third);
                var afterPublication = observe(jdbc, second, "revision-2-after-revision-3");
                assertThat(afterPublication).usingRecursiveComparison().ignoringFields("pointer").isEqualTo(tickTwo);
                var historical = observe(jdbc, first, "revision-1-after-revision-3");
                assertThat(historical).usingRecursiveComparison().ignoringFields("source", "pointer").isEqualTo(original);
            }
        }
    }

    private static void addPurchase(JdbcTemplate jdbc, long order, int quantity) {
        jdbc.update("""
            INSERT INTO order_confirmation_inbox(id,event_id,order_id,event_type,schema_version,confirmed_at,normalized_payload,processed_at)
            VALUES (?,UUID(),?,'OrderConfirmed',1,'2026-08-18 01:00:00','{}','2026-08-18 01:00:00')
            """, order, order);
        jdbc.update("""
            INSERT INTO product_purchase_activities(inbox_id,order_id,product_id,quantity,confirmed_at)
            VALUES (?,?,7,?,'2026-08-18 01:00:00')
            """, order, order, quantity);
    }

    private static long runFailedWorker(PreservedMySql database, ConfigurableApplicationContext context,
                                        WeeklyPlanStore.Claim claim) throws Exception {
        var parameters = new JobParametersBuilder().addString("snapshotId", claim.snapshot().toString())
            .addLong("orderPartitionId", (long) claim.orderPartitionId()).addString("periodStart", claim.period().toString(), false)
            .addLong("revision", (long) claim.revision(), false).addString("ownerId", claim.owner(), false)
            .addLong("generation", claim.generation(), false).toJobParameters();
        try (var admin = database.connectAsAdministrator(); var ddl = admin.createStatement()) {
            ddl.execute("""
                CREATE TRIGGER fail_integrity_test_worker BEFORE INSERT ON weekly_ranking_partial_scores FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='integrity test worker failure before score commit'
                """);
            try {
                var job = context.getBean("weeklyRankingOrderPartition", Job.class);
                var run = context.getBean(JobLauncher.class).run(job, parameters);
                assertThat(run.getStatus()).isEqualTo(BatchStatus.FAILED);
                var steps = context.getBean(JdbcTemplate.class).queryForList("""
                    SELECT CONCAT(STEP_NAME,':',STATUS) FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID=? ORDER BY STEP_EXECUTION_ID
                    """, String.class, run.getId());
                System.out.printf("integrity worker execution=%s steps=%s%n", run.getId(), steps);
                assertThat(steps).contains("freezeWeeklyRankingOrderPartition:COMPLETED",
                    "scoreWeeklyRankingLocalProductPartition:localProductPartition-0:FAILED");
                var errors = context.getBean(JdbcTemplate.class).queryForList("""
                    SELECT EXIT_MESSAGE FROM BATCH_STEP_EXECUTION
                    WHERE JOB_EXECUTION_ID=? AND STEP_NAME='scoreWeeklyRankingLocalProductPartition:localProductPartition-0'
                    """, String.class, run.getId());
                assertThat(errors).singleElement().asString().contains("integrity test worker failure before score commit");
                return run.getId();
            } finally {
                ddl.execute("DROP TRIGGER fail_integrity_test_worker");
            }
        }
    }

    private static Observation observe(JdbcTemplate jdbc, String snapshot, String phase) {
        var partition = jdbc.queryForMap("""
            SELECT p.state AS plan,o.state AS part,o.last_error,o.owner_id,o.generation,o.attempts,o.execution_id,
                   CAST(o.frozen_at AS CHAR) AS frozen_at,o.input_count,o.input_checksum
            FROM weekly_ranking_plans p JOIN weekly_ranking_order_partitions o USING(snapshot_id)
            WHERE p.snapshot_id=? AND o.order_partition_id=0
            """, snapshot);
        var inputHash = jdbc.queryForObject("""
            SELECT SHA2(GROUP_CONCAT(CONCAT(order_id,CHAR(9),product_id,CHAR(9),quantity,CHAR(9),
                DATE_FORMAT(confirmed_at,'%Y-%m-%dT%H:%i:%s.%fZ'),CHAR(10)) ORDER BY order_id,product_id SEPARATOR ''),256)
            FROM weekly_ranking_snapshot_inputs WHERE snapshot_id=?
            """, String.class, snapshot);
        var observation = new Observation((String) partition.get("plan"), (String) partition.get("part"),
            (String) partition.get("last_error"), (String) partition.get("owner_id"), ((Number) partition.get("generation")).longValue(),
            ((Number) partition.get("attempts")).intValue(), (Long) partition.get("execution_id"), (String) partition.get("frozen_at"),
            (Long) partition.get("input_count"), (String) partition.get("input_checksum"), inputHash,
            jdbc.queryForList("SELECT CONCAT(order_id,':',product_id,':',quantity) FROM product_purchase_activities "
                + "WHERE confirmed_at>=? AND confirmed_at<? ORDER BY order_id,product_id", String.class, WEEK, WEEK.plusWeeks(1)),
            jdbc.queryForList("SELECT CONCAT(order_id,':',product_id,':',quantity) FROM weekly_ranking_snapshot_inputs "
                + "WHERE snapshot_id=? ORDER BY order_id,product_id", String.class, snapshot),
            jdbc.queryForList("""
                SELECT CONCAT(b.JOB_EXECUTION_ID,':',b.STATUS) FROM BATCH_JOB_EXECUTION b JOIN BATCH_JOB_EXECUTION_PARAMS p USING(JOB_EXECUTION_ID)
                WHERE p.PARAMETER_NAME='snapshotId' AND p.PARAMETER_VALUE=? ORDER BY b.JOB_EXECUTION_ID
                """, String.class, snapshot),
            jdbc.queryForList("SELECT CONCAT(order_partition_id,':',product_id,':',score) FROM weekly_ranking_partial_scores "
                + "WHERE snapshot_id=? ORDER BY order_partition_id,product_id", String.class, snapshot),
            jdbc.queryForList("SELECT CONCAT(product_id,':',score) FROM weekly_ranking_score_stage WHERE snapshot_id=? ORDER BY product_id",
                String.class, snapshot),
            jdbc.queryForList("SELECT CONCAT(product_id,':',score,':',ranking_position) FROM weekly_rankings "
                + "WHERE snapshot_id=? ORDER BY ranking_position", String.class, snapshot),
            jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_completions WHERE snapshot_id=?", Integer.class, snapshot),
            jdbc.queryForObject("SELECT CONCAT(revision,':',snapshot_id) FROM weekly_ranking_publications WHERE period_start=?",
                String.class, WEEK));
        System.out.printf("integrity phase=%s snapshot=%s %s%n", phase, snapshot, observation);
        return observation;
    }

    private record Observation(String plan, String partition, String reason, String owner, long generation, int attempts,
                               Long boundExecution, String frozenAt, Long manifestCount, String manifestHash, String inputHash,
                               List<String> source, List<String> inputs, List<String> executions, List<String> partial,
                               List<String> stage, List<String> rankings, int completions, String pointer) {}

    private static ConfigurableApplicationContext start(PreservedMySql database, String... mode) {
        var args = new ArrayList<>(Arrays.asList(database.arguments("--spring.profiles.include=ranking-scheduler",
            "--spring.batch.job.name=weeklyRankingOrderPartition", "--ranking.scheduler.node-id=" + NODE,
            "--ranking.scheduler.auto-plan=false", "--ranking.scheduler.local-grid=1", "--ranking.weekly.local-workers=1",
            "--ranking.scheduler.poll-delay=PT1H", "--ranking.scheduler.heartbeat-delay=PT1H", "--ranking.scheduler.member-timeout=PT2H")));
        args.replaceAll(arg -> arg.startsWith("--datasource.mysql-jpa.main.maximum-pool-size=")
            ? "--datasource.mysql-jpa.main.maximum-pool-size=7" : arg);
        args.addAll(List.of(mode));
        return PreservationContexts.start(Configuration.class, args.toArray(String[]::new));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ComponentScan({"com.loopers.batch.job.ranking.orderpartition", "com.loopers.config.jpa", "com.loopers.application.ranking",
        "com.loopers.infrastructure.persistence.ranking"})
    @Import({WeeklySchedulerConfiguration.class, OrderPartitionedWeeklyRankingFinalizer.class})
    static class Configuration {
        @Bean
        @Primary
        Clock closedWeekClock() {
            return Clock.fixed(Instant.parse("2026-08-24T01:00:00Z"), ZoneOffset.UTC);
        }
    }
}
