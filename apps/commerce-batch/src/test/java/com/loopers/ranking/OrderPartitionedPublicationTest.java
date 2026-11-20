package com.loopers.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.batch.job.ranking.coordination.WeeklyPlanFinalizer;
import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import com.loopers.domain.ranking.model.vo.RankingPeriod;
import com.loopers.support.preservation.PreservedMySql;
import com.loopers.support.preservation.PreservationContexts;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderPartitionedPublicationTest {
    private PreservedMySql database;
    private ConfigurableApplicationContext context;
    private JdbcTemplate jdbc;

    @BeforeAll
    void start() throws Exception {
        database = new PreservedMySql();
        database.prepareCommerceSchema();
        try (var connection = database.connectAsAdministrator()) {
            for (String file : List.of("001-batch-metadata-mysql.sql","014-weekly-ranking-snapshots.sql",
                "015-weekly-ranking-scores.sql","016-weekly-ranking-publication.sql","017-weekly-ranking-plans.sql",
                "018-weekly-ranking-partial-scores.sql")) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("preservation/" + file));
            }
        }
        context = PreservationContexts.start(RankingJobRestartTest.Configuration.class,
            database.arguments());
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
    }

    @AfterAll
    void stop() {
        if (context != null) {
            context.close();
        }
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("두 주문 파티션이 닫힐 때까지 기다린 후 동시 공개와 재시도를 거쳐도 5점 한 행만 공개한다")
    void concurrentFinalizers() throws Exception {
        String start = "2026-08-17";
        var id = seed(start, 1, List.of(new Purchase(42, 7, 2), new Purchase(42, 8, 1),
            new Purchase(43, 7, 3), new Purchase(44, 9, 5)));
        var originalFreeze = jdbc.queryForObject("SELECT frozen_at FROM weekly_ranking_snapshots WHERE snapshot_id=?",
            LocalDateTime.class, id.toString());
        var service = context.getBean(WeeklyPlanFinalizer.class);
        service.finalizePlan(id);
        assertThat(count(id, "weekly_ranking_score_stage")).isZero();
        jdbc.update("UPDATE weekly_ranking_order_partitions SET state='DONE' WHERE snapshot_id=?", id.toString());
        var go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { go.await(); service.finalizePlan(id); return null; });
            var b = executor.submit(() -> { go.await(); service.finalizePlan(id); return null; });
            go.countDown();
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
        }
        service.finalizePlan(id);
        assertThat(jdbc.queryForObject("SELECT score FROM weekly_rankings WHERE snapshot_id=? AND product_id=7",
            Long.class, id.toString())).isEqualTo(5);
        assertThat(jdbc.queryForList("SELECT CONCAT(product_id,':',score,':',ranking_position) FROM weekly_rankings "
            + "WHERE snapshot_id=? ORDER BY ranking_position", String.class, id.toString()))
            .containsExactly("7:5:1", "9:5:2", "8:1:3");
        assertThat(jdbc.queryForObject("SELECT result_checksum FROM weekly_ranking_completions WHERE snapshot_id=?",
            String.class, id.toString())).isEqualTo("745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2");
        assertThat(count(id, "weekly_ranking_completions")).isOne();
        assertThat(count(id, "weekly_ranking_publications")).isOne();
        assertThat(jdbc.queryForObject("SELECT frozen_at FROM weekly_ranking_snapshots WHERE snapshot_id=?",
            LocalDateTime.class, id.toString())).isEqualTo(originalFreeze);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_order_partitions WHERE snapshot_id=? AND frozen_at=?",
            Long.class, id.toString(), originalFreeze)).isEqualTo(2);
    }

    @Test
    @DisplayName("부분 점수 검산이 다르면 전체 합산을 롤백하고 이미 공개된 결과를 유지한다")
    void rejectsWrongScore() {
        var previous = seed("2026-08-24", 1);
        var service = context.getBean(WeeklyPlanFinalizer.class);
        jdbc.update("UPDATE weekly_ranking_order_partitions SET state='DONE' WHERE snapshot_id=?", previous.toString());
        service.finalizePlan(previous);
        var next = seed("2026-08-24", 2);
        jdbc.update("UPDATE weekly_ranking_order_partitions SET state='DONE' WHERE snapshot_id=?", next.toString());
        jdbc.update("UPDATE weekly_ranking_partial_scores SET score=4 WHERE snapshot_id=? AND order_partition_id=1", next.toString());
        assertThatThrownBy(() -> service.finalizePlan(next)).isInstanceOf(SnapshotIntegrityException.class);
        assertThat(count(next, "weekly_ranking_score_stage")).isZero();
        assertThat(count(previous, "weekly_ranking_publications")).isOne();
    }

    @Test
    @DisplayName("충돌 원장이 남은 revision은 DONE 표식이 있어도 공개할 수 없다")
    void conflictPreventsPublication() {
        var id = seed("2026-08-31", 1);
        jdbc.update("UPDATE weekly_ranking_order_partitions SET state='DONE' WHERE snapshot_id=?", id.toString());
        jdbc.update("INSERT INTO weekly_ranking_score_conflicts VALUES (?,0,7,2,4,'worker',1,UTC_TIMESTAMP(6))", id.toString());
        context.getBean(WeeklyPlanFinalizer.class).finalizePlan(id);
        assertThat(count(id, "weekly_ranking_publications")).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM weekly_ranking_plans WHERE snapshot_id=?", String.class,
            id.toString())).isEqualTo("CONFLICT");
    }

    @Test
    @DisplayName("늦게 끝난 낮은 revision이 더 높은 공개 revision을 바꾸지 않는다")
    void olderRevisionCannotReplacePublished() {
        var old = seed("2026-09-07", 1);
        var newer = seed("2026-09-07", 2);
        jdbc.update("UPDATE weekly_ranking_order_partitions SET state='DONE' WHERE snapshot_id IN (?,?)", old.toString(), newer.toString());
        var service = context.getBean(WeeklyPlanFinalizer.class);
        service.finalizePlan(newer);
        service.finalizePlan(old);
        assertThat(count(newer, "weekly_ranking_publications")).isOne();
        assertThat(count(old, "weekly_ranking_completions")).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM weekly_ranking_plans WHERE snapshot_id=?", String.class,
            old.toString())).isEqualTo("SUPERSEDED");
    }

    @Test
    @DisplayName("주문 파티션별 checksum이 달라지면 같은 수량을 가리키는 부분 점수도 공개할 수 없다")
    void verifiesEachOrderPartitionManifest() {
        var id = seed("2026-09-14", 1);
        jdbc.update("UPDATE weekly_ranking_order_partitions SET state='DONE' WHERE snapshot_id=?", id.toString());
        jdbc.update("UPDATE weekly_ranking_order_partitions SET input_checksum=REPEAT('0',64) WHERE snapshot_id=? AND order_partition_id=1", id.toString());
        assertThatThrownBy(() -> context.getBean(WeeklyPlanFinalizer.class).finalizePlan(id))
            .isInstanceOf(SnapshotIntegrityException.class);
        assertThat(count(id, "weekly_ranking_publications")).isZero();
        assertThat(count(id, "weekly_ranking_score_stage")).isZero();
    }

    @Test
    @DisplayName("입력 0건인 모든 주문 파티션이 닫히면 빈 주도 완성 기록과 포인터를 가진다")
    void publishesEmptyWeek() {
        var id = seed("2026-09-21", 1, List.of());
        jdbc.update("UPDATE weekly_ranking_order_partitions SET state='DONE' WHERE snapshot_id=?", id.toString());
        context.getBean(WeeklyPlanFinalizer.class).finalizePlan(id);
        assertThat(count(id, "weekly_ranking_publications")).isOne();
        assertThat(count(id, "weekly_rankings")).isZero();
        assertThat(jdbc.queryForObject("SELECT product_count FROM weekly_ranking_completions WHERE snapshot_id=?",
            Long.class, id.toString())).isZero();
    }

    @Test
    @DisplayName("상품 전체 합계가 같아도 주문 파티션별 부분합이 바뀌면 공개하지 않는다")
    void rejectsCompensatingErrorsAcrossOrderPartitions() {
        var id = seed("2026-09-28", 1);
        jdbc.update("UPDATE weekly_ranking_order_partitions SET state='DONE' WHERE snapshot_id=?", id.toString());
        // The weekly total is still five. Each individual contribution disagrees with its frozen orders.
        jdbc.update("UPDATE weekly_ranking_partial_scores SET score=IF(order_partition_id=0,1,4) WHERE snapshot_id=?", id.toString());
        assertThatThrownBy(() -> context.getBean(WeeklyPlanFinalizer.class).finalizePlan(id))
            .isInstanceOf(SnapshotIntegrityException.class);
        assertThat(count(id, "weekly_ranking_publications")).isZero();
        assertThat(count(id, "weekly_ranking_score_stage")).isZero();
    }

    @Test
    @DisplayName("주간 checksum 또는 주문 파티션 고정 시각이 계획과 달라지면 manifest를 그대로 두고 공개를 거부한다")
    void rejectsChangedCommonManifest() {
        for (String field : List.of("checksum", "frozenAt")) {
            var id = seed("checksum".equals(field) ? "2026-10-05" : "2026-10-12", 1);
            jdbc.update("UPDATE weekly_ranking_order_partitions SET state='DONE' WHERE snapshot_id=?", id.toString());
            if ("checksum".equals(field)) {
                jdbc.update("UPDATE weekly_ranking_snapshots SET input_checksum=REPEAT('0',64) WHERE snapshot_id=?", id.toString());
            } else {
                jdbc.update("UPDATE weekly_ranking_order_partitions SET frozen_at=TIMESTAMPADD(SECOND,1,frozen_at) "
                    + "WHERE snapshot_id=? AND order_partition_id=1", id.toString());
            }
            var original = jdbc.queryForMap("SELECT frozen_at,input_count,input_checksum FROM weekly_ranking_snapshots WHERE snapshot_id=?",
                id.toString());
            assertThatThrownBy(() -> context.getBean(WeeklyPlanFinalizer.class).finalizePlan(id)).isInstanceOf(SnapshotIntegrityException.class);
            assertThat(jdbc.queryForMap("SELECT frozen_at,input_count,input_checksum FROM weekly_ranking_snapshots WHERE snapshot_id=?",
                id.toString())).isEqualTo(original);
            assertThat(count(id, "weekly_ranking_score_stage")).isZero();
            assertThat(count(id, "weekly_ranking_publications")).isZero();
        }
    }

    private long count(UUID id, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE snapshot_id=?", Long.class, id.toString());
    }

    private record Purchase(int order, int product, int quantity) {}

    private UUID seed(String start, int revision) {
        return seed(start, revision, List.of(new Purchase(42, 7, 2), new Purchase(43, 7, 3)));
    }

    private UUID seed(String start, int revision, List<Purchase> purchases) {
        var id = UUID.randomUUID();
        var period = new RankingPeriod(LocalDate.parse(start));
        var key = id.toString();
        jdbc.update("INSERT INTO weekly_ranking_snapshots(snapshot_id,period_start,period_end,revision,state,input_contract_version)"
            + " VALUES (?,?,?,?,'FREEZING',3)", key, period.start(), period.end(), revision);
        jdbc.update("INSERT INTO weekly_ranking_plans(snapshot_id,period_start,revision,order_partition_count) VALUES (?,?,?,2)",
            key, period.start(), revision);
        jdbc.update("""
            INSERT INTO weekly_ranking_order_partitions(snapshot_id,order_partition_id,preferred_node,local_grid,state,order_min,order_max)
            VALUES (?,0,'node',2,'RUNNING',NULL,43),(?,1,'node',2,'RUNNING',43,NULL)
            """, key, key);
        for (var purchase : purchases) {
            jdbc.update("INSERT INTO weekly_ranking_snapshot_inputs VALUES (?,?,?,?,?)", key, purchase.order(), purchase.product(),
                purchase.quantity(), start + " 12:00:00");
        }
        CommonRankingInputFixture.seal(jdbc, key, period, List.of(43L), 2);
        jdbc.update("""
            INSERT INTO weekly_ranking_partial_scores(snapshot_id,order_partition_id,product_id,score)
            SELECT snapshot_id,IF(order_id<43,0,1),product_id,SUM(quantity) FROM weekly_ranking_snapshot_inputs
            WHERE snapshot_id=? GROUP BY snapshot_id,IF(order_id<43,0,1),product_id
            """, key);
        return id;
    }
}
