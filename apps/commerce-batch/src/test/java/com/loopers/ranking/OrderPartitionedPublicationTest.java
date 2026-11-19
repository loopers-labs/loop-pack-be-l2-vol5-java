package com.loopers.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.batch.job.ranking.coordination.WeeklyPlanFinalizer;
import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import com.loopers.domain.ranking.model.vo.RankingPeriod;
import com.loopers.infrastructure.persistence.ranking.PurchaseInputFingerprintAccumulator;
import com.loopers.support.preservation.PreservedMySql;
import com.loopers.support.preservation.PreservationContexts;
import java.time.Instant;
import java.time.LocalDate;
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
        var id = seed(start, 1);
        var period = new RankingPeriod(LocalDate.parse(start));
        var at = Instant.parse(start + "T12:00:00Z");
        var firstInput = new PurchaseInputFingerprintAccumulator(period);
        firstInput.accept(42, 7, 2, at);
        firstInput.accept(42, 8, 1, at);
        var secondInput = new PurchaseInputFingerprintAccumulator(period);
        secondInput.accept(43, 7, 3, at);
        secondInput.accept(44, 9, 5, at);
        jdbc.update("INSERT INTO weekly_ranking_snapshot_inputs VALUES (?,42,8,1,?),(?,44,9,5,?)",
            id.toString(), start + " 12:00:00", id.toString(), start + " 12:00:00");
        jdbc.update("INSERT INTO weekly_ranking_partial_scores VALUES (?,0,8,1),(?,1,9,5)", id.toString(), id.toString());
        jdbc.update("UPDATE weekly_ranking_order_partitions SET input_count=2,input_checksum=IF(order_partition_id=0,?,?) WHERE snapshot_id=?",
            firstInput.finish().sha256(), secondInput.finish().sha256(), id.toString());
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
        assertThat(jdbc.queryForObject("SELECT frozen_at=(SELECT MAX(frozen_at) FROM weekly_ranking_order_partitions WHERE snapshot_id=?) "
            + "FROM weekly_ranking_snapshots WHERE snapshot_id=?", Boolean.class, id.toString(), id.toString())).isTrue();
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
        var id = seed("2026-09-21", 1);
        var empty = new PurchaseInputFingerprintAccumulator(new RankingPeriod(LocalDate.parse("2026-09-21"))).finish();
        jdbc.update("DELETE FROM weekly_ranking_partial_scores WHERE snapshot_id=?", id.toString());
        jdbc.update("DELETE FROM weekly_ranking_snapshot_inputs WHERE snapshot_id=?", id.toString());
        jdbc.update("UPDATE weekly_ranking_order_partitions SET state='DONE',input_count=0,input_checksum=? WHERE snapshot_id=?",
            empty.sha256(), id.toString());
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

    private long count(UUID id, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE snapshot_id=?", Long.class, id.toString());
    }

    private UUID seed(String start, int revision) {
        var id = UUID.randomUUID();
        var period = new RankingPeriod(LocalDate.parse(start));
        var key = id.toString();
        jdbc.update("INSERT INTO weekly_ranking_snapshots(snapshot_id,period_start,period_end,revision,state,input_contract_version)"
            + " VALUES (?,?,?,?,'FREEZING',2)", key, period.start(), period.end(), revision);
        jdbc.update("INSERT INTO weekly_ranking_plans(snapshot_id,period_start,revision,order_partition_count) VALUES (?,?,?,2)",
            key, period.start(), revision);
        for (int i = 0; i < 2; i++) {
            int quantity = 2 + i;
            int order = 42 + i;
            var fingerprint = new PurchaseInputFingerprintAccumulator(period);
            fingerprint.accept(order, 7, quantity, Instant.parse(start + "T12:00:00Z"));
            jdbc.update("INSERT INTO weekly_ranking_order_partitions(snapshot_id,order_partition_id,preferred_node,local_grid,state,order_min,order_max,"
                + "frozen_at,input_count,input_checksum) VALUES (?,?,'node',2,'RUNNING',?,?,?,1,?)",
                key, i, i == 0 ? null : 43, i == 0 ? 43 : null, start + " 13:00:0" + i, fingerprint.finish().sha256());
            jdbc.update("INSERT INTO weekly_ranking_snapshot_inputs VALUES (?,?,7,?,?)", key, order, quantity, start + " 12:00:00");
            jdbc.update("INSERT INTO weekly_ranking_partial_scores VALUES (?,?,7,?)", key, i, quantity);
        }
        return id;
    }
}
