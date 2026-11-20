package com.loopers.batch.job.ranking.coordination;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.support.preservation.PreservedMySql;
import com.loopers.support.preservation.PreservationContexts;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;

class WeeklyRankingCommonInputTest {
    @Test
    @DisplayName("늦게 시작한 주문 파티션과 빈 파티션도 계획 시점에 함께 고정한 구매만 계산한다")
    void delayedWorkersReadOnlyTheCommonSnapshot() throws Exception {
        var timeout = Duration.ofMinutes(1);
        try (var database = new PreservedMySql()) {
            database.prepareCommerceSchema();
            try (var connection = database.connect()) {
                for (var script : List.of("001-batch-metadata-mysql.sql", "014-weekly-ranking-snapshots.sql",
                    "015-weekly-ranking-scores.sql", "016-weekly-ranking-publication.sql",
                    "017-weekly-ranking-plans.sql", "018-weekly-ranking-partial-scores.sql")) {
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource("preservation/" + script));
                }
            }
            try (var app = PreservationContexts.start(OrderPartitionRestartBoundsTest.Configuration.class, database.arguments(
                "--spring.batch.job.name=weeklyRankingOrderPartition", "--ranking.weekly.chunk-size=1", "--ranking.weekly.local-workers=1"))) {
                var jdbc = new JdbcTemplate(app.getBean(DataSource.class));
                var plans = new WeeklyPlanStore(jdbc, app.getBean(PlatformTransactionManager.class));
                var launcher = app.getBean(JobLauncher.class);
                var job = app.getBean("weeklyRankingOrderPartition", Job.class);
                for (var owner : List.of("a", "b", "c")) {
                    plans.register(owner, 2, timeout);
                }
                for (long order : List.of(42L, 44L)) {
                    jdbc.update("""
                        INSERT INTO order_confirmation_inbox(id,event_id,order_id,event_type,schema_version,confirmed_at,
                            normalized_payload,processed_at) VALUES (?,UUID(),?,'OrderConfirmed',1,'2026-08-18','{}','2026-08-18')
                        """, order, order);
                }
                jdbc.update("""
                    INSERT INTO product_purchase_activities(inbox_id,order_id,product_id,quantity,confirmed_at)
                    VALUES (42,42,7,2,'2026-08-18'),(44,44,7,3,'2026-08-18')
                    """);
                var id = plans.ensurePlan(LocalDate.of(2026, 8, 17), 1, timeout, false);
                var before = observedInputs(jdbc, id);
                var atPlanning = jdbc.queryForMap("""
                    SELECT state,input_contract_version,frozen_at,input_count,input_checksum
                    FROM weekly_ranking_snapshots WHERE snapshot_id=?
                    """, id.toString());
                run(launcher, job, plans.claim("a", timeout).orElseThrow());
                jdbc.update("""
                    INSERT INTO product_purchase_activities(inbox_id,order_id,product_id,quantity,confirmed_at)
                    VALUES (42,42,8,20,'2026-08-18'),(44,44,8,30,'2026-08-18')
                    """);
                run(launcher, job, plans.claim("b", timeout).orElseThrow());
                run(launcher, job, plans.claim("c", timeout).orElseThrow());
                var after = observedInputs(jdbc, id);
                var partials = jdbc.queryForList("""
                    SELECT CONCAT(order_partition_id,':',product_id,':',score) FROM weekly_ranking_partial_scores
                    WHERE snapshot_id=? ORDER BY order_partition_id,product_id
                    """, String.class, id.toString());
                var commonTime = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM weekly_ranking_order_partitions s JOIN weekly_ranking_snapshots m USING(snapshot_id)
                    WHERE s.snapshot_id=? AND s.frozen_at=m.frozen_at
                    """, Integer.class, id.toString());
                System.out.printf("COMMON snapshot=%s before=%s parent=%s after=%s partials=%s equalFreeze=%d%n",
                    id, before, atPlanning, after, partials, commonTime);
                assertThat(before).containsExactly("42:7:2", "44:7:3");
                assertThat(after).isEqualTo(before);
                assertThat(atPlanning.get("state")).isEqualTo("FROZEN");
                assertThat(atPlanning.get("input_contract_version")).isEqualTo(3);
                assertThat(((Number) atPlanning.get("input_count")).longValue()).isEqualTo(2L);
                assertThat(atPlanning.get("frozen_at")).isNotNull();
                assertThat(atPlanning.get("input_checksum")).asString().hasSize(64);
                assertThat(commonTime).isEqualTo(3);
                assertThat(partials).containsExactly("0:7:2", "1:7:3");
                assertThat(jdbc.queryForList("""
                    SELECT input_count FROM weekly_ranking_order_partitions WHERE snapshot_id=? ORDER BY order_partition_id
                    """, Long.class, id.toString())).containsExactly(1L, 1L, 0L);
            }
        }
    }

    private List<String> observedInputs(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForList("""
            SELECT CONCAT(order_id,':',product_id,':',quantity) FROM weekly_ranking_snapshot_inputs
            WHERE snapshot_id=? ORDER BY order_id,product_id
            """, String.class, id.toString());
    }

    private void run(JobLauncher launcher, Job job, WeeklyPlanStore.Claim claim) throws Exception {
        var parameters = new JobParametersBuilder().addString("snapshotId", claim.snapshot().toString())
            .addLong("orderPartitionId", (long) claim.orderPartitionId()).addString("periodStart", claim.period().toString(), false)
            .addLong("revision", (long) claim.revision(), false).addString("ownerId", claim.owner(), false)
            .addLong("generation", claim.generation(), false).toJobParameters();
        assertThat(launcher.run(job, parameters).getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }
}
