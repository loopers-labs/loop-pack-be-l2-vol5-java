package com.loopers.batch.job.ranking.coordination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.loopers.support.preservation.PreservedMySql;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class WeeklyPlanStoreTest {
    @Test
    @DisplayName("동시 주간 계획은 한 스냅샷과 고정된 멤버별 주문 파티션을 만들고 실행 중인 주문 파티션을 빼앗지 않는다")
    void concurrentPlanningAndClaims() throws Exception {
        var week = LocalDate.of(2026, 8, 17);
        var timeout = Duration.ofMinutes(1);
        try (var database = new PreservedMySql(); var threads = Executors.newFixedThreadPool(2)) {
            database.prepareCommerceSchema();
            try (var connection = database.connect()) {
                for (String script : List.of("001-batch-metadata-mysql.sql", "014-weekly-ranking-snapshots.sql",
                    "015-weekly-ranking-scores.sql", "016-weekly-ranking-publication.sql", "017-weekly-ranking-plans.sql",
                    "018-weekly-ranking-partial-scores.sql")) {
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource("preservation/" + script));
                }
            }
            var source = new DriverManagerDataSource(database.jdbcUrl(), "application", "application");
            var jdbc = new JdbcTemplate(source);
            var store = new WeeklyPlanStore(jdbc, new DataSourceTransactionManager(source));
            store.register("a", 2, timeout);
            store.register("b", 3, timeout);
            var anotherProcess = new WeeklyPlanStore(jdbc, new DataSourceTransactionManager(source));
            assertThatThrownBy(() -> anotherProcess.register("a", 2, timeout)).isInstanceOf(IllegalStateException.class);
            var a = threads.submit(() -> store.ensurePlan(week, 1, timeout, false));
            var b = threads.submit(() -> store.ensurePlan(week, 1, timeout, false));
            assertThat(a.get()).isEqualTo(b.get());
            assertThat(jdbc.queryForList("SELECT local_grid FROM weekly_ranking_order_partitions ORDER BY order_partition_id", Integer.class))
                .containsExactly(2, 3);
            assertThat(jdbc.queryForList("SELECT is_empty FROM weekly_ranking_order_partitions ORDER BY order_partition_id", Integer.class))
                .containsExactly(0, 1);
            var first = store.claim("a", timeout).orElseThrow();
            assertThat(first.orderPartitionId()).isZero();
            assertThat(store.claim("a", timeout)).isEmpty();
            jdbc.update("UPDATE weekly_ranking_members SET heartbeat_at='2000-01-01' WHERE node_id='a'");
            var second = store.claim("b", timeout).orElseThrow();
            assertThat(second.orderPartitionId()).isEqualTo(1);
            assertThat(store.claim("b", timeout)).isEmpty();
            assertThatThrownBy(() -> store.ensurePlan(week, 2, timeout, false)).isInstanceOf(IllegalStateException.class);
            jdbc.update("""
                INSERT INTO weekly_ranking_score_conflicts(snapshot_id,order_partition_id,product_id,stored_score,attempted_score,
                    owner_id,generation,recorded_at) VALUES (?,0,7,2,3,'a',1,UTC_TIMESTAMP(6))
                """, a.get().toString());
            var runs = new WeeklyOrderPartitionExecutions(mock(Job.class), mock(JobLauncher.class), mock(JobExplorer.class),
                mock(JobRepository.class), jdbc, new DataSourceTransactionManager(source), mock(OrderPartitionRestartEvidence.class), timeout);
            runs.reconcileAll();
            assertThat(jdbc.queryForList("SELECT state FROM weekly_ranking_order_partitions WHERE snapshot_id=? ORDER BY order_partition_id",
                String.class, a.get().toString())).containsExactly("CONFLICT", "BLOCKED");
            assertThatThrownBy(() -> store.ensurePlan(week, 2, timeout, true)).isInstanceOf(IllegalStateException.class);
            assertThat(store.ensurePlan(week, 2, timeout, false)).isNotEqualTo(a.get());
            assertThat(jdbc.queryForObject("SELECT state FROM weekly_ranking_plans WHERE snapshot_id=?",
                String.class, a.get().toString())).isEqualTo("CONFLICT");
            store.heartbeat("a", 2);
            verifyWeightedBoundaries(store, jdbc, week.minusWeeks(3), timeout);
            var legacyWeek = week.minusWeeks(1);
            jdbc.update("""
                INSERT INTO weekly_ranking_snapshots(snapshot_id,period_start,period_end,revision,state,input_contract_version)
                VALUES(UUID(),?,?,1,'FREEZING',1)
                """, legacyWeek, week);
            assertThatThrownBy(() -> store.ensurePlan(legacyWeek, 1, timeout, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("기존");
        }
    }

    private void verifyWeightedBoundaries(WeeklyPlanStore store, JdbcTemplate jdbc, LocalDate week, Duration timeout) {
        for (long order = 10; order < 20; order++) {
            jdbc.update("""
                INSERT INTO order_confirmation_inbox(event_id,order_id,event_type,schema_version,confirmed_at,normalized_payload,
                processed_at) VALUES(UUID(),?,'OrderConfirmed',1,?,'{}',?)
                """, order, week.atStartOfDay(), week.atStartOfDay());
        }
        jdbc.update("""
            INSERT INTO product_purchase_activities(inbox_id,order_id,product_id,quantity,confirmed_at)
            WITH RECURSIVE products(product_id) AS (SELECT 1 UNION ALL SELECT product_id+1 FROM products WHERE product_id<90)
            SELECT i.id,i.order_id,p.product_id,1,i.confirmed_at FROM order_confirmation_inbox i JOIN products p
            ON (i.order_id=10 OR p.product_id=1) WHERE i.order_id BETWEEN 10 AND 19
            """);
        var snapshot = store.ensurePlan(week, 1, timeout, true).toString();
        assertThat(jdbc.queryForList("SELECT order_max FROM weekly_ranking_order_partitions WHERE snapshot_id=? ORDER BY order_partition_id",
            Long.class, snapshot)).containsExactly(11L, null);
        assertThat(jdbc.queryForList("SELECT order_min FROM weekly_ranking_order_partitions WHERE snapshot_id=? ORDER BY order_partition_id",
            Long.class, snapshot)).containsExactly(null, 11L);
        assertThat(store.firstDue(week.plusWeeks(3))).isEqualTo(week.plusWeeks(1));
    }
}
