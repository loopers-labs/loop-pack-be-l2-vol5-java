package com.loopers.application.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.loopers.support.preservation.PreservedMySql;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class WeeklyRankingEqualScoreReplayProcessTest {
    private static final LocalDate WEEK = LocalDate.of(2026, 8, 17);
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) private Path logs;

    @Test
    @DisplayName("서로 다른 두 JVM의 부분합 2와 3 중 같은 키의 3을 두 번째 실행에서 실제로 재전달해도 공개 점수는 5다")
    void replaysCommittedScoreInSecondExecutionOfSameOrderPartitionJob() throws Exception {
        try (var database = new PreservedMySql()) {
            RankingProcessLab.prepare(database);
            var lab = new RankingProcessLab(logs, database);
            seedAndObserve(lab.jdbc);
            try (var nodeA = lab.resident("node-a", 1, 10);
                 var nodeB = lab.resident("node-b", 1, 10, "-Dranking.lab.replay-completed-worker=true")) {
                assertThat(nodeA.process().pid()).isNotEqualTo(nodeB.process().pid());
                lab.awaitMembers(2, nodeA, nodeB);
                try (var request = lab.request(WEEK, 1, 1)) {
                    request.awaitSuccess();
                }
                String snapshot = lab.awaitPublication(WEEK, 1);
                var writes = observedWrites(lab.jdbc, snapshot);
                assertThat(writes).extracting(Write::partition, Write::owner, Write::generation, Write::route,
                        Write::product, Write::oldScore, Write::score)
                    .containsExactly(tuple(0, "node-a", 1L, "INSERT", 7L, null, 2L),
                        tuple(1, "node-b", 1L, "INSERT", 7L, null, 3L),
                        tuple(1, "node-b", 2L, "DUPLICATE", 7L, 3L, 3L));
                var firstB = writes.get(1);
                var secondB = writes.get(2);
                assertThat(secondB.execution()).isGreaterThan(firstB.execution());
                assertThat(writes.getFirst().session()).isNotEqualTo(firstB.session());
                assertThat(secondB.session()).isNotBlank().isEqualTo(firstB.session());
                var instances = lab.jdbc.queryForList("""
                    SELECT DISTINCT JOB_INSTANCE_ID FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID IN (?,?)
                    """, Long.class, firstB.execution(), secondB.execution());
                assertThat(instances).hasSize(1);
                var orders = lab.jdbc.queryForList("""
                    SELECT CONCAT_WS(':',p.order_partition_id,p.owner_id,i.order_id)
                    FROM weekly_ranking_order_partitions p JOIN weekly_ranking_snapshot_inputs i ON i.snapshot_id=p.snapshot_id
                      AND (p.order_min IS NULL OR i.order_id>=p.order_min) AND (p.order_max IS NULL OR i.order_id<p.order_max)
                    WHERE p.snapshot_id=? AND p.state='DONE' ORDER BY p.order_partition_id,i.order_id
                    """, String.class, snapshot);
                assertThat(orders).containsExactly("0:node-a:10", "1:node-b:20");
                var executions = lab.jdbc.queryForList("""
                    SELECT CONCAT_WS(':', e.STATUS,manager.STATUS,worker.STATUS,worker.READ_COUNT,worker.WRITE_COUNT)
                    FROM BATCH_JOB_EXECUTION e JOIN BATCH_STEP_EXECUTION manager ON manager.JOB_EXECUTION_ID=e.JOB_EXECUTION_ID
                    JOIN BATCH_STEP_EXECUTION worker ON worker.JOB_EXECUTION_ID=e.JOB_EXECUTION_ID
                    WHERE e.JOB_EXECUTION_ID IN (?,?) AND manager.STEP_NAME='calculateWeeklyRankingOrderPartition'
                      AND worker.STEP_NAME='scoreWeeklyRankingLocalProductPartition:localProductPartition-0'
                    ORDER BY e.JOB_EXECUTION_ID
                    """, String.class, firstB.execution(), secondB.execution());
                assertThat(executions).containsExactly("FAILED:FAILED:COMPLETED:1:1", "COMPLETED:COMPLETED:COMPLETED:1:1");
                assertThat(lab.jdbc.queryForList("""
                    SELECT score FROM weekly_ranking_partial_scores WHERE snapshot_id=? ORDER BY order_partition_id,product_id
                    """, Long.class, snapshot)).containsExactly(2L, 3L);
                assertThat(lab.jdbc.queryForList("""
                    SELECT CONCAT_WS(':',product_id,score) FROM weekly_ranking_score_stage WHERE snapshot_id=?
                    """, String.class, snapshot)).containsExactly("7:5");
                assertThat(lab.jdbc.queryForList("""
                    SELECT CONCAT_WS(':',r.product_id,r.score,r.ranking_position) FROM weekly_ranking_publications p
                    JOIN weekly_rankings r ON r.snapshot_id=p.snapshot_id
                    WHERE p.period_start=? AND p.snapshot_id=? AND p.revision=1
                    """, String.class, WEEK, snapshot)).containsExactly("7:5:1");
                System.out.printf("RANKING_EQUAL_REPLAY pidA=%d pidB=%d snapshot=%s bJobInstance=%d orders=%s writes=%s "
                        + "executions=%s stage=7:5 published=7:5:1%n", nodeA.process().pid(), nodeB.process().pid(), snapshot,
                    instances.getFirst(), orders, writes, executions);
            }
        }
    }

    private static void seedAndObserve(JdbcTemplate jdbc) {
        jdbc.update("""
            INSERT INTO order_confirmation_inbox(id,event_id,order_id,event_type,schema_version,confirmed_at,normalized_payload,processed_at)
            VALUES (1,UUID(),10,'OrderConfirmed',1,'2026-08-18','{}','2026-08-18'),
                   (2,UUID(),20,'OrderConfirmed',1,'2026-08-18','{}','2026-08-18')
            """);
        jdbc.update("""
            INSERT INTO product_purchase_activities(inbox_id,order_id,product_id,quantity,confirmed_at)
            VALUES (1,10,7,2,'2026-08-18'),(2,20,7,3,'2026-08-18')
            """);
        jdbc.execute("""
            CREATE TABLE replay_observed_writes (
                observation_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, snapshot_id CHAR(36) NOT NULL,
                order_partition_id INT NOT NULL, product_id BIGINT NOT NULL, route VARCHAR(16) NOT NULL,
                old_score BIGINT, score BIGINT NOT NULL, owner_id VARCHAR(128) NOT NULL, owner_session VARCHAR(64) NOT NULL,
                generation BIGINT NOT NULL, execution_id BIGINT NOT NULL
            ) ENGINE=InnoDB
            """);
        observe(jdbc, "AFTER INSERT", "INSERT", "NULL");
        observe(jdbc, "BEFORE UPDATE", "DUPLICATE", "OLD.score");
    }

    private static void observe(JdbcTemplate jdbc, String triggerEvent, String route, String oldScore) {
        jdbc.execute("""
            CREATE TRIGGER observe_partial_%s %s ON weekly_ranking_partial_scores FOR EACH ROW
            INSERT INTO replay_observed_writes(snapshot_id,order_partition_id,product_id,route,old_score,score,
                                               owner_id,owner_session,generation,execution_id)
            SELECT NEW.snapshot_id,NEW.order_partition_id,NEW.product_id,'%s',%s,NEW.score,p.owner_id,p.owner_session,
                   p.generation,p.execution_id FROM weekly_ranking_order_partitions p
            WHERE p.snapshot_id=NEW.snapshot_id AND p.order_partition_id=NEW.order_partition_id
            """.formatted(route, triggerEvent, route, oldScore));
    }

    private static List<Write> observedWrites(JdbcTemplate jdbc, String snapshot) {
        return jdbc.query("""
            SELECT order_partition_id,owner_id,owner_session,generation,execution_id,route,product_id,old_score,score
            FROM replay_observed_writes WHERE snapshot_id=? ORDER BY order_partition_id,observation_id
            """, (row, index) -> new Write(row.getInt(1), row.getString(2), row.getString(3), row.getLong(4), row.getLong(5),
                row.getString(6), row.getLong(7), row.getObject(8, Long.class), row.getLong(9)), snapshot);
    }

    private record Write(int partition, String owner, String session, long generation, long execution, String route,
                         long product, Long oldScore, long score) {}
}
