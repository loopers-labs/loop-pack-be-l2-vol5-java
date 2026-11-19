package com.loopers.batch.job.ranking.coordination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.ranking.model.vo.RankingPeriod;
import com.loopers.infrastructure.persistence.ranking.PurchaseInputFingerprintAccumulator;
import com.loopers.support.preservation.PreservedMySql;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WeeklyPlanGlobalFreezeTest {
    private static final Duration TIMEOUT = Duration.ofMinutes(10);
    private static final String GATE = "week_global_freeze_copy";
    private PreservedMySql database;
    private JdbcTemplate jdbc;
    private WeeklyPlanStore first;
    private WeeklyPlanStore second;

    @BeforeAll
    void open() throws Exception {
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
        first = new WeeklyPlanStore(jdbc, new DataSourceTransactionManager(source));
        second = new WeeklyPlanStore(jdbc, new DataSourceTransactionManager(source));
        first.register("node-a", 2, TIMEOUT);
        second.register("node-b", 2, TIMEOUT);
    }

    @AfterAll
    void close() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("같은 주간에 경쟁하는 두 스케줄러는 복사 도중 커밋한 주문을 제외한 단일 manifest와 경계를 함께 고정한다")
    void snapshotsOneSourceReadWhileAnotherPlannerAndPurchaseCommit() throws Exception {
        var week = LocalDate.of(2027, 1, 4);
        purchase(week, 10, 1);
        purchase(week, 20, 1);
        try (var executor = Executors.newFixedThreadPool(2); var admin = database.connectAsAdministrator();
             var ddl = admin.createStatement()) {
            ddl.execute("""
                CREATE TRIGGER pause_global_copy BEFORE INSERT ON weekly_ranking_snapshot_inputs FOR EACH ROW
                BEGIN
                  IF NEW.order_id=10 THEN
                    IF GET_LOCK('week_global_freeze_copy',20) <> 1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='gate timeout'; END IF;
                    DO RELEASE_LOCK('week_global_freeze_copy');
                  END IF;
                END
                """);
            ddl.execute("SELECT GET_LOCK('" + GATE + "',0)");
            try {
                var planner = executor.submit(() -> first.ensurePlan(week, 1, TIMEOUT, true));
                var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!planner.isDone() && System.nanoTime() < deadline && !copyWaiting(admin)) {
                    Thread.sleep(25);
                }
                assertThat(copyWaiting(admin)).as("the source-copy statement reached its first destination row").isTrue();
                var other = executor.submit(() -> second.ensurePlan(week, 1, TIMEOUT, true));
                var plannerDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!other.isDone() && System.nanoTime() < plannerDeadline && !plannerWaiting(admin)) {
                    Thread.sleep(25);
                }
                assertThat(plannerWaiting(admin)).as("the competing planner waits on this week's pointer").isTrue();
                purchase(week, 11, 10);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_snapshots WHERE period_start=?", Integer.class, week)).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_snapshot_inputs WHERE order_id IN (10,20)", Integer.class)).isZero();
                ddl.execute("SELECT RELEASE_LOCK('" + GATE + "')");
                var id = planner.get(15, TimeUnit.SECONDS).toString();
                assertThat(other.get(15, TimeUnit.SECONDS).toString()).isEqualTo(id);
                assertThat(jdbc.queryForList("SELECT order_id FROM weekly_ranking_snapshot_inputs WHERE snapshot_id=? ORDER BY order_id", Long.class, id))
                    .containsExactly(10L, 20L);
                var expected = new PurchaseInputFingerprintAccumulator(new RankingPeriod(week));
                for (long order : List.of(10L, 20L)) {
                    expected.accept(order, 1, 1, week.atStartOfDay().toInstant(ZoneOffset.UTC));
                }
                var manifest = jdbc.queryForMap("SELECT state,input_count,input_checksum,input_contract_version,frozen_at FROM weekly_ranking_snapshots WHERE snapshot_id=?", id);
                assertThat(manifest).containsEntry("state", "FROZEN").containsEntry("input_count", 2L)
                    .containsEntry("input_checksum", expected.finish().sha256()).containsEntry("input_contract_version", 3);
                assertThat(jdbc.queryForList("SELECT order_max FROM weekly_ranking_order_partitions WHERE snapshot_id=? ORDER BY order_partition_id", Long.class, id))
                    .containsExactly(20L, null);
                assertThat(jdbc.queryForList("SELECT input_count FROM weekly_ranking_order_partitions WHERE snapshot_id=? ORDER BY order_partition_id", Long.class, id))
                    .containsExactly(1L, 1L);
                var left = new PurchaseInputFingerprintAccumulator(new RankingPeriod(week));
                left.accept(10, 1, 1, week.atStartOfDay().toInstant(ZoneOffset.UTC));
                var right = new PurchaseInputFingerprintAccumulator(new RankingPeriod(week));
                right.accept(20, 1, 1, week.atStartOfDay().toInstant(ZoneOffset.UTC));
                assertThat(jdbc.queryForList("SELECT input_checksum FROM weekly_ranking_order_partitions WHERE snapshot_id=? ORDER BY order_partition_id", String.class, id))
                    .containsExactly(left.finish().sha256(), right.finish().sha256());
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_order_partitions WHERE snapshot_id=? AND frozen_at=(SELECT frozen_at FROM weekly_ranking_snapshots WHERE snapshot_id=?)", Integer.class, id, id)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_plans WHERE period_start=?", Integer.class, week)).isOne();
            } finally {
                ddl.execute("SELECT RELEASE_LOCK('" + GATE + "')");
                ddl.execute("DROP TRIGGER pause_global_copy");
            }
        }
    }

    @Test
    @DisplayName("공통 입력 복사 실패는 부모 계획·모든 경계·입력·스케줄 커서를 함께 롤백하며 같은 revision을 다시 요청할 수 있다")
    void rollsBackAndRetriesTheWholePlan() throws Exception {
        var week = LocalDate.of(2027, 2, 1);
        purchase(week, 101, 1);
        try (var admin = database.connectAsAdministrator(); var ddl = admin.createStatement()) {
            ddl.execute("""
                CREATE TRIGGER fail_global_plan BEFORE INSERT ON weekly_ranking_order_partitions FOR EACH ROW
                BEGIN
                  IF NEW.order_partition_id=1 AND EXISTS(SELECT 1 FROM weekly_ranking_snapshot_inputs WHERE snapshot_id=NEW.snapshot_id)
                  THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='failure after copy'; END IF;
                END
                """);
            try {
                assertThatThrownBy(() -> first.ensurePlan(week, 1, TIMEOUT, true)).isInstanceOf(DataAccessException.class);
            } finally {
                ddl.execute("DROP TRIGGER fail_global_plan");
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_snapshots WHERE period_start=?", Integer.class, week)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_plans WHERE period_start=?", Integer.class, week)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_snapshot_inputs WHERE order_id=101", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_schedule_cursor WHERE last_period=?", Integer.class, week)).isZero();
        var id = first.ensurePlan(week, 1, TIMEOUT, true);
        assertThat(jdbc.queryForObject("SELECT input_count FROM weekly_ranking_snapshots WHERE snapshot_id=?", Long.class, id.toString())).isOne();
    }

    @Test
    @DisplayName("구매 없는 주차도 전체와 빈 파티션의 같은 시각·0건 checksum을 계획 생성 시 고정한다")
    void freezesEmptyWeek() {
        var week = LocalDate.of(2027, 3, 1);
        String id = first.ensurePlan(week, 1, TIMEOUT, false).toString();
        String hash = new PurchaseInputFingerprintAccumulator(new RankingPeriod(week)).finish().sha256();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_snapshots WHERE snapshot_id=? AND state='FROZEN' AND input_contract_version=3 AND frozen_at IS NOT NULL AND input_count=0 AND input_checksum=?", Integer.class, id, hash)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM weekly_ranking_order_partitions s JOIN weekly_ranking_snapshots m ON m.snapshot_id=s.snapshot_id
            WHERE s.snapshot_id=? AND s.input_count=0 AND s.input_checksum=? AND s.frozen_at=m.frozen_at
            """, Integer.class, id, hash)).isEqualTo(2);
    }

    @Test
    @DisplayName("미완료 독립 고정 계약은 새 스케줄러가 인수하지 않고 종료된 이후 다음 revision만 공통 고정한다")
    void holdsOlderOpenPlanAndCreatesNextRevisionAfterClosure() {
        var week = LocalDate.of(2026, 1, 5);
        var id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO weekly_ranking_snapshots(snapshot_id,period_start,period_end,revision,state,input_contract_version)
            VALUES(?,?,?,1,'FREEZING',2)
            """, id, week, week.plusWeeks(1));
        jdbc.update("INSERT INTO weekly_ranking_plans(snapshot_id,period_start,revision,order_partition_count) VALUES(?,?,1,1)", id, week);
        jdbc.update("INSERT INTO weekly_ranking_order_partitions(snapshot_id,order_partition_id,preferred_node,local_grid) VALUES(?,0,'node-a',1)", id);
        assertThatThrownBy(() -> first.ensurePlan(week, 1, TIMEOUT, false)).isInstanceOf(IllegalStateException.class);
        assertThat(first.claim("node-a", TIMEOUT).map(claim -> claim.snapshot().toString().equals(id)).orElse(false)).isFalse();
        assertThat(jdbc.queryForObject("SELECT state FROM weekly_ranking_order_partitions WHERE snapshot_id=?", String.class, id)).isEqualTo("READY");
        jdbc.update("UPDATE weekly_ranking_plans SET state='CONFLICT' WHERE snapshot_id=?", id);
        var next = first.ensurePlan(week, 2, TIMEOUT, false);
        assertThat(jdbc.queryForObject("SELECT input_contract_version FROM weekly_ranking_snapshots WHERE snapshot_id=?", Integer.class, next.toString())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT input_contract_version FROM weekly_ranking_snapshots WHERE snapshot_id=?", Integer.class, id)).isEqualTo(2);
    }

    private boolean plannerWaiting(Connection admin) throws Exception {
        try (var probe = admin.createStatement(); var rows = probe.executeQuery("""
            SELECT EXISTS(SELECT 1 FROM performance_schema.data_lock_waits w
                JOIN performance_schema.data_locks dl ON dl.ENGINE=w.ENGINE AND dl.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                WHERE dl.OBJECT_SCHEMA=DATABASE() AND dl.OBJECT_NAME='weekly_ranking_publications')
            """)) {
            rows.next();
            return rows.getBoolean(1);
        }
    }

    private boolean copyWaiting(Connection admin) throws Exception {
        try (var probe = admin.prepareStatement("""
            SELECT COUNT(*) FROM performance_schema.metadata_locks
            WHERE OBJECT_TYPE='USER LEVEL LOCK' AND OBJECT_NAME=? AND LOCK_STATUS='PENDING'
            """)) {
            probe.setString(1, GATE);
            try (var rows = probe.executeQuery()) {
                rows.next();
                return rows.getInt(1) == 1;
            }
        }
    }

    private void purchase(LocalDate week, long order, int products) {
        var at = week.atStartOfDay();
        jdbc.update("""
            INSERT INTO order_confirmation_inbox(event_id,order_id,event_type,schema_version,confirmed_at,normalized_payload,processed_at)
            VALUES(?,?,'OrderConfirmed',1,?,'{}',?)
            """, UUID.randomUUID().toString(), order, at, at);
        for (int product = 1; product <= products; product++) {
            jdbc.update("""
                INSERT INTO product_purchase_activities(inbox_id,order_id,product_id,quantity,confirmed_at)
                SELECT id,order_id,?,1,confirmed_at FROM order_confirmation_inbox WHERE order_id=?
                """, product, order);
        }
    }
}