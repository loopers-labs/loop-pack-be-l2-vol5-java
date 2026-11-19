package com.loopers.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.application.ranking.service.WriteWeeklyRankingPartialScoreService;
import com.loopers.domain.ranking.model.SnapshotConflictException;
import com.loopers.domain.ranking.model.vo.WeeklyPurchaseScore;
import com.loopers.infrastructure.persistence.ranking.JdbcWeeklyPartialScoreRepository;
import com.loopers.support.preservation.PreservedMySql;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderPartitionScoreConsistencyTest {
    private PreservedMySql database;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private WriteWeeklyRankingPartialScoreService writer;

    @BeforeAll
    void start() throws Exception {
        database = new PreservedMySql();
        var source = new DriverManagerDataSource(database.jdbcUrl(), "application", "application");
        jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        tx = new TransactionTemplate(manager);
        writer = new WriteWeeklyRankingPartialScoreService(new JdbcWeeklyPartialScoreRepository(source, manager));
        try (var connection = database.connectAsAdministrator()) {
            for (var file : List.of("014-weekly-ranking-snapshots.sql", "015-weekly-ranking-scores.sql",
                "016-weekly-ranking-publication.sql", "017-weekly-ranking-plans.sql", "018-weekly-ranking-partial-scores.sql")) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("preservation/" + file));
            }
        }
    }

    @AfterAll
    void stop() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("서로 다른 주문 파티션이 같은 상품을 동시에 써도 2와 3이 보존되고 재시도·충돌 뒤에도 합계는 5다")
    void concurrentPartialsRetainTheirValue() throws Exception {
        var id = seed(1);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); write(id, 0, 2); return null; });
            var second = executor.submit(() -> { start.await(); write(id, 1, 3); return null; });
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        write(id, 1, 3);
        assertThatThrownBy(() -> write(id, 1, 4)).isInstanceOf(SnapshotConflictException.class);
        assertThat(jdbc.queryForList("SELECT score FROM weekly_ranking_partial_scores WHERE snapshot_id=? ORDER BY order_partition_id",
            Long.class, id.toString())).containsExactly(2L, 3L);
        assertThat(jdbc.queryForObject("SELECT SUM(score) FROM weekly_ranking_partial_scores WHERE snapshot_id=?",
            Long.class, id.toString())).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT CONCAT(stored_score,':',attempted_score) FROM weekly_ranking_score_conflicts "
            + "WHERE snapshot_id=? AND order_partition_id=1 AND product_id=7", String.class, id.toString())).isEqualTo("3:4");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_score_stage", Long.class)).isZero();
    }

    @Test
    @DisplayName("실패한 청크는 전체 부분 점수를 롤백하고 이전 소유자 및 완료된 주문 파티션의 쓰기는 거절한다")
    void failedChunkAndFencedOwner() {
        var id = seed(2);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            writer.write(id, 1, "node-1", 1, List.of(new WeeklyPurchaseScore(id, 7, 3)));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE weekly_ranking_order_partitions SET generation=2 WHERE snapshot_id=? AND order_partition_id=1", id.toString());
        assertThatThrownBy(() -> write(id, 1, 3)).isInstanceOf(SnapshotConflictException.class);
        jdbc.update("UPDATE weekly_ranking_order_partitions SET state='DONE' WHERE snapshot_id=? AND order_partition_id=0", id.toString());
        assertThatThrownBy(() -> write(id, 0, 2)).isInstanceOf(SnapshotConflictException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_partial_scores WHERE snapshot_id=?",
            Long.class, id.toString())).isZero();
    }

    @Test
    @DisplayName("한 주문 파티션의 점수 충돌로 계획이 격리되면 다른 RUNNING 소유자도 새 부분 점수를 쓸 수 없다")
    void rejectsRunningSiblingAfterPlanConflict() {
        var id = seed(3);
        write(id, 1, 3);
        assertThatThrownBy(() -> write(id, 1, 4)).isInstanceOf(SnapshotConflictException.class);
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE weekly_ranking_plans SET state='CONFLICT' WHERE snapshot_id=?", id.toString());
            jdbc.update("UPDATE weekly_ranking_order_partitions SET state='CONFLICT' WHERE snapshot_id=? AND order_partition_id=1", id.toString());
        });
        assertThatThrownBy(() -> write(id, 0, 2)).isInstanceOf(SnapshotConflictException.class);
        assertThat(jdbc.queryForList("SELECT order_partition_id FROM weekly_ranking_partial_scores WHERE snapshot_id=?", Integer.class,
            id.toString())).containsExactly(1);
    }

    @Test
    @DisplayName("같은 주문 파티션의 무관한 상품 잠금과 오래된 RR 읽기 시점에서도 청크의 현재 점수만 확인한다")
    void validatesOnlyOwnedProductKeysAgainstCurrentRows() throws Exception {
        var id = seed(4);
        var key = id.toString();
        var chunk = LongStream.rangeClosed(1, 16).mapToObj(product -> new WeeklyPurchaseScore(id, product, product)).toList();
        tx.executeWithoutResult(status -> writer.write(id, 0, "node-0", 1, chunk.subList(0, 15)));
        jdbc.update("INSERT INTO weekly_ranking_partial_scores(snapshot_id,order_partition_id,product_id,score) VALUES (?,0,17,17)", key);

        var repeatable = new TransactionTemplate(tx.getTransactionManager());
        repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var late = new TransactionTemplate(tx.getTransactionManager());
        late.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try (var peer = database.connect(); var lock = peer.prepareStatement("""
            SELECT score FROM weekly_ranking_partial_scores WHERE snapshot_id=? AND order_partition_id=0 AND product_id=17 FOR UPDATE
            """)) {
            peer.setAutoCommit(false);
            lock.setString(1, key);
            try (var held = lock.executeQuery()) {
                assertThat(held.next()).isTrue();
            }
            repeatable.executeWithoutResult(status -> {
                // A small range optimizer budget reproduces the broad IN scan independently of table statistics.
                jdbc.execute("SET SESSION range_optimizer_max_mem_size=1, innodb_lock_wait_timeout=2");
                assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM weekly_ranking_partial_scores WHERE snapshot_id=? AND order_partition_id=0 AND product_id=16
                    """, Integer.class, key)).isZero();
                late.executeWithoutResult(ignored -> jdbc.update("""
                    INSERT INTO weekly_ranking_partial_scores(snapshot_id,order_partition_id,product_id,score) VALUES (?,0,16,16)
                    """, key));
                writer.write(id, 0, "node-0", 1, chunk);
            });
            peer.rollback();
        }
        assertThat(jdbc.queryForList("SELECT score FROM weekly_ranking_partial_scores WHERE snapshot_id=? ORDER BY product_id",
            Long.class, key)).containsExactlyElementsOf(LongStream.rangeClosed(1, 17).boxed().toList());
    }

    private UUID seed(int revision) {
        var id = UUID.randomUUID();
        var key = id.toString();
        jdbc.update("INSERT INTO weekly_ranking_snapshots(snapshot_id,period_start,period_end,revision,state,input_contract_version) "
            + "VALUES (?,'2026-08-17','2026-08-24',?,'FREEZING',2)", key, revision);
        jdbc.update("INSERT INTO weekly_ranking_plans(snapshot_id,period_start,revision,order_partition_count) "
            + "VALUES (?,'2026-08-17',?,2)", key, revision);
        for (int number = 0; number < 2; number++) {
            jdbc.update("INSERT INTO weekly_ranking_order_partitions(snapshot_id,order_partition_id,preferred_node,local_grid,state,owner_id,generation,"
                + "frozen_at,input_count,input_checksum) VALUES (?,?,?,2,'RUNNING',?,1,UTC_TIMESTAMP(6),1,REPEAT('a',64))",
                key, number, "node-" + number, "node-" + number);
        }
        return id;
    }

    private void write(UUID id, int number, long value) {
        tx.executeWithoutResult(status -> writer.write(id, number, "node-" + number, 1, List.of(new WeeklyPurchaseScore(id, 7, value))));
    }
}
