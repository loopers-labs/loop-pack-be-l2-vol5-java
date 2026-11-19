package com.loopers.application.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.loopers.support.preservation.PreservedMySql;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class WeeklyRankingProcessRecoveryTest {
    private static final LocalDate WEEK = LocalDate.of(2026, 8, 17);
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) private Path logs;

    @Test
    @DisplayName("첫 청크 커밋 뒤 종료된 JVM의 실행을 격리하고 정확한 실행 복구로 남은 상품만 이어 읽는다")
    void recoversOrphanAtLastCommittedPartitionCheckpoint() throws Exception {
        try (var database = new PreservedMySql()) {
            RankingProcessLab.prepare(database);
            var lab = new RankingProcessLab(logs, database);
            seed(lab.jdbc);
            try (var gate = database.connectAsAdministrator(); var original = lab.resident("original", 1, 12)) {
                lab.awaitMembers(1, original);
                namedLock(gate, true);
                try (var request = lab.request(WEEK, 1, 1)) {
                    request.awaitSuccess();
                }
                String snapshot = lab.jdbc.queryForObject("SELECT snapshot_id FROM weekly_ranking_plans WHERE period_start=?",
                    String.class, WEEK);
                await().atMost(Duration.ofSeconds(80)).untilAsserted(() -> assertThat(lab.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM weekly_ranking_partial_scores WHERE snapshot_id=?", Integer.class, snapshot)).isEqualTo(16));
                var claim = lab.jdbc.queryForMap("""
                    SELECT owner_id,generation,execution_id,input_checksum FROM weekly_ranking_order_partitions WHERE snapshot_id=?
                    """, snapshot);
                String owner = (String) claim.get("owner_id");
                long generation = ((Number) claim.get("generation")).longValue();
                long execution = ((Number) claim.get("execution_id")).longValue();
                assertThat(owner).isEqualTo("original");
                assertThat(lab.jdbc.queryForObject("""
                    SELECT READ_COUNT FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID=? AND STEP_NAME='scoreWeeklyRankingLocalProductPartition:localProductPartition-0'
                    """, Long.class, execution)).isEqualTo(16L);
                original.process().destroyForcibly();
                assertThat(original.process().waitFor(15, TimeUnit.SECONDS)).isTrue();
                namedLock(gate, false);
                try (var survivor = lab.resident("survivor", 1, 12)) {
                    await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> assertThat(lab.jdbc.queryForObject(
                        "SELECT state FROM weekly_ranking_order_partitions WHERE snapshot_id=?", String.class, snapshot))
                        .isEqualTo("RECOVERY_REQUIRED"));
                    try (var wrong = lab.recover(snapshot, owner, generation, execution + 1)) {
                        assertThat(wrong.process().waitFor(60, TimeUnit.SECONDS)).isTrue();
                        assertThat(wrong.process().exitValue()).isNotZero();
                    }
                    assertThat(lab.jdbc.queryForObject("SELECT COUNT(*) FROM BATCH_JOB_EXECUTION", Integer.class)).isEqualTo(1);
                    assertThat(lab.jdbc.queryForObject("SELECT snapshot_id FROM weekly_ranking_publications WHERE period_start=?",
                        String.class, WEEK)).isNull();
                    try (var exact = lab.recover(snapshot, owner, generation, execution)) {
                        exact.awaitSuccess();
                    }
                    assertThat(lab.awaitPublication(WEEK, 1)).isEqualTo(snapshot);
                    assertThat(lab.jdbc.queryForMap("""
                        SELECT owner_id,generation,state,input_checksum FROM weekly_ranking_order_partitions WHERE snapshot_id=?
                        """, snapshot)).containsEntry("owner_id", "survivor").containsEntry("generation", generation + 1)
                        .containsEntry("state", "DONE").containsEntry("input_checksum", claim.get("input_checksum"));
                    assertThat(lab.jdbc.queryForList("""
                        SELECT STATUS FROM BATCH_JOB_EXECUTION ORDER BY JOB_EXECUTION_ID
                        """, String.class)).containsExactly("FAILED", "COMPLETED");
                    assertThat(lab.jdbc.queryForObject("SELECT COUNT(DISTINCT JOB_INSTANCE_ID) FROM BATCH_JOB_EXECUTION",
                        Integer.class)).isEqualTo(1);
                    assertThat(lab.jdbc.queryForList("""
                        SELECT READ_COUNT FROM BATCH_STEP_EXECUTION WHERE STEP_NAME='scoreWeeklyRankingLocalProductPartition:localProductPartition-0'
                        ORDER BY JOB_EXECUTION_ID
                        """, Long.class)).containsExactly(16L, 17L);
                    assertThat(lab.jdbc.queryForObject("""
                        SELECT COUNT(*) FROM weekly_rankings WHERE snapshot_id=? AND score=product_id AND ranking_position=34-product_id
                        """, Integer.class, snapshot)).isEqualTo(33);
                }
            }
        }
    }

    private static void seed(JdbcTemplate jdbc) {
        jdbc.update("""
            INSERT INTO order_confirmation_inbox(id,event_id,order_id,event_type,schema_version,confirmed_at,normalized_payload,processed_at)
            VALUES (1,UUID(),1,'OrderConfirmed',1,'2026-08-18','{}','2026-08-18')
            """);
        for (int id = 1; id <= 33; id++) {
            jdbc.update("""
                INSERT INTO product_purchase_activities(inbox_id,order_id,product_id,quantity,confirmed_at) VALUES(1,1,?,?,'2026-08-18')
                """, id, id);
        }
        jdbc.execute("""
            CREATE TRIGGER pause_second_chunk BEFORE INSERT ON weekly_ranking_partial_scores FOR EACH ROW
            BEGIN
              IF NEW.product_id=17 THEN
                IF GET_LOCK('ranking-recovery-chunk',120) <> 1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='chunk gate timeout'; END IF;
                DO RELEASE_LOCK('ranking-recovery-chunk');
              END IF;
            END
            """);
    }

    private static void namedLock(Connection connection, boolean acquire) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(acquire
            ? "SELECT GET_LOCK('ranking-recovery-chunk',0)" : "SELECT RELEASE_LOCK('ranking-recovery-chunk')")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
    }
}
