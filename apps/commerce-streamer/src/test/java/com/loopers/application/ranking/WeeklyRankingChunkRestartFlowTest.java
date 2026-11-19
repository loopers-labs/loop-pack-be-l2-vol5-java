package com.loopers.application.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.support.preservation.PreservedMySql;
import com.loopers.testcontainers.KafkaTestFixture;
import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class WeeklyRankingChunkRestartFlowTest {
    private static final LocalDate WEEK = LocalDate.of(2026, 8, 17);
    private static final String INPUT = "3e90276ec1f9123f308cd18d155e1dde6bf811ef3aa741105ff5c766c357a298";
    private static final String RESULT = "745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2";
    private static final String WORKER = "scoreWeeklyRankingLocalProductPartition:localProductPartition-0";
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) private Path logs;

    @Test
    @DisplayName("스케줄러가 상품 7·8의 첫 청크를 커밋한 뒤 실패하면 같은 JobInstance가 상품 9만 계산해 정상본과 같은 순위를 공개한다")
    void restartsAfterTwoCommittedProducts() throws Exception {
        try (var db = new PreservedMySql(); var kafka = KafkaTestFixture.create();
             var redis = new GenericContainer<>(DockerImageName.parse("redis:7.0")).withExposedPorts(6379)) {
            kafka.start();
            redis.start();
            try (var flow = new WeeklyRankingFlowLab(logs, db, kafka.getBootstrapServers(), redis);
                 var api = flow.startApi("api", 1)) {
                var lab = new RankingProcessLab(logs, db);
                seed(lab.jdbc);
                int port = api.port("CommerceApiApplication");
                try (var node = lab.residentWithChunkSize("fixture", 1, 10, 2, "-Dranking.lab.product-observations=" + logs)) {
                    lab.awaitMembers(1, node);
                    String baseline = requestAndPublish(lab, 1);
                    assertRanking(flow.ranking(port, WEEK), baseline);
                    assertThat(checksums(lab.jdbc, baseline)).containsExactly(INPUT, RESULT);
                    failAndRestart(lab, flow, port, baseline, node.process().pid());
                }
            }
        }
    }

    private void failAndRestart(RankingProcessLab lab, WeeklyRankingFlowLab flow, int port, String baseline, long pid) throws Exception {
        var jdbc = lab.jdbc;
        Files.createFile(logs.resolve("interrupt-on-9"));
        // Hold subsequent claims so the reader checkpoint and public API can be inspected after the failed transaction.
        jdbc.execute("""
            CREATE TRIGGER hold_fixture_retry BEFORE UPDATE ON weekly_ranking_order_partitions FOR EACH ROW
            BEGIN
                IF OLD.generation >= 1 AND NEW.generation > OLD.generation THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Awaiting fixture checkpoint observation';
                END IF;
            END
            """);
        try (var request = lab.request(WEEK, 2, 1)) {
            request.awaitSuccess();
        }
        String id = jdbc.queryForObject("SELECT snapshot_id FROM weekly_ranking_plans WHERE period_start=? AND revision=2", String.class, WEEK);
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> assertThat(attempts(jdbc, id))
            .extracting(Attempt::status).containsExactly("FAILED"));
        Attempt failed = attempts(jdbc, id).getFirst();
        assertThat(Files.readAllLines(logs.resolve("products-" + failed.execution() + ".log"))).containsExactly("7", "8", "before 9");
        // Spring Batch counted the read of product 9 before the processor failed. The saved cursor remains at 2.
        assertThat(failed.read()).isEqualTo(3);
        assertThat(failed.written()).isEqualTo(2);
        String serialized = jdbc.queryForObject("""
            SELECT COALESCE(c.SERIALIZED_CONTEXT, c.SHORT_CONTEXT) FROM BATCH_STEP_EXECUTION_CONTEXT c
            JOIN BATCH_STEP_EXECUTION s USING(STEP_EXECUTION_ID) WHERE s.JOB_EXECUTION_ID=? AND s.STEP_NAME=?
            """, String.class, failed.execution(), WORKER);
        try (var data = new ObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(serialized)))) {
            var checkpoint = (Map<?, ?>) data.readObject();
            assertThat(checkpoint.get("weeklyRankingLocalProductPartitionProducts.read.count")).isEqualTo(2);
        }
        assertThat(jdbc.queryForList("""
            SELECT CONCAT_WS(':',product_id,score) FROM weekly_ranking_partial_scores WHERE snapshot_id=? ORDER BY product_id
            """, String.class, id)).containsExactly("7:5", "8:1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_score_stage WHERE snapshot_id=?", Long.class, id)).isZero();
        var frozen = jdbc.queryForMap("""
            SELECT input_count,input_checksum,DATE_FORMAT(frozen_at,'%Y-%m-%dT%H:%i:%s.%fZ') AS instant
            FROM weekly_ranking_snapshots WHERE snapshot_id=? AND state='FROZEN'
            """, id);
        assertThat(frozen).containsEntry("input_count", 4L).containsEntry("input_checksum", INPUT);
        assertThat(jdbc.queryForObject("SELECT snapshot_id FROM weekly_ranking_publications WHERE period_start=?", String.class, WEEK))
            .isEqualTo(baseline);
        assertRanking(flow.ranking(port, WEEK), baseline);
        jdbc.execute("""
            CREATE TRIGGER block_input_recopy BEFORE INSERT ON weekly_ranking_snapshot_inputs FOR EACH ROW
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='A restart must reuse the frozen input'
            """);
        jdbc.execute("DROP TRIGGER hold_fixture_retry");
        assertThat(lab.awaitPublication(WEEK, 2)).isEqualTo(id);
        List<Attempt> attempts = attempts(jdbc, id);
        assertThat(attempts).hasSize(2).extracting(Attempt::instance).containsOnly(failed.instance());
        Attempt resumed = attempts.getLast();
        assertThat(jdbc.queryForList("""
            SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION_PARAMS WHERE PARAMETER_NAME='snapshotId'
              AND PARAMETER_VALUE=? ORDER BY JOB_EXECUTION_ID
            """, Long.class, id)).containsExactly(failed.execution(), resumed.execution());
        assertThat(resumed.status()).isEqualTo("COMPLETED");
        assertThat(resumed.execution()).isGreaterThan(failed.execution());
        assertThat(resumed.read()).isOne();
        assertThat(resumed.written()).isOne();
        assertThat(Files.readAllLines(logs.resolve("products-" + resumed.execution() + ".log"))).containsExactly("9");
        assertThat(checksums(jdbc, id)).isEqualTo(checksums(jdbc, baseline));
        var published = flow.ranking(port, WEEK);
        assertRanking(published, id);
        assertThat(Instant.parse(published.path("frozenAt").asText())).isEqualTo(Instant.parse((String) frozen.get("instant")));
        System.out.printf("RANKING_FIXTURE pid=%d baseline=%s restarted=%s attempts=%s checkpoint=2 partials=[7:5,8:1] "
            + "resumedProducts=[9] input=%s result=%s api=[7:5:1,9:5:2,8:1:3] frozenAt=%s%n",
            pid, baseline, id, attempts, INPUT, RESULT, published.path("frozenAt").asText());
    }

    private static List<Attempt> attempts(JdbcTemplate jdbc, String id) {
        return jdbc.query("""
            SELECT j.JOB_INSTANCE_ID,j.JOB_EXECUTION_ID,j.STATUS,s.READ_COUNT,s.WRITE_COUNT FROM BATCH_JOB_EXECUTION j
            JOIN BATCH_JOB_EXECUTION_PARAMS p ON p.JOB_EXECUTION_ID=j.JOB_EXECUTION_ID AND p.PARAMETER_NAME='snapshotId'
            JOIN BATCH_STEP_EXECUTION s ON s.JOB_EXECUTION_ID=j.JOB_EXECUTION_ID
            WHERE p.PARAMETER_VALUE=? AND s.STEP_NAME=? ORDER BY j.JOB_EXECUTION_ID
            """, (rs, index) -> new Attempt(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getLong(5)), id, WORKER);
    }

    private static List<String> checksums(JdbcTemplate jdbc, String id) {
        return jdbc.queryForObject("""
            SELECT s.input_checksum,c.result_checksum FROM weekly_ranking_snapshots s
            JOIN weekly_ranking_completions c USING(snapshot_id) WHERE snapshot_id=?
            """, (row, index) -> List.of(row.getString(1), row.getString(2)), id);
    }

    private static void assertRanking(JsonNode result, String snapshot) {
        assertThat(result.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(result.path("snapshotId").asText()).isEqualTo(snapshot);
        assertThat(result.path("items")).extracting(item ->
            List.of(item.path("productId").asInt(), item.path("score").asInt(), item.path("rank").asInt()))
            .containsExactly(List.of(7, 5, 1), List.of(9, 5, 2), List.of(8, 1, 3));
    }

    private static String requestAndPublish(RankingProcessLab lab, int revision) throws Exception {
        try (var request = lab.request(WEEK, revision, 1)) {
            request.awaitSuccess();
        }
        return lab.awaitPublication(WEEK, revision);
    }

    private static void seed(JdbcTemplate jdbc) {
        jdbc.update("""
            INSERT INTO products (id,deleted,stock,brand_id,created_at,updated_at,price,name)
            VALUES (9,0,20,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1000,'Product 9')
            """);
        jdbc.update("""
            INSERT INTO order_confirmation_inbox (id,event_id,order_id,event_type,schema_version,confirmed_at,normalized_payload,processed_at)
            VALUES (1,UUID(),42,'OrderConfirmed',1,'2026-08-17 01:00:00','{}','2026-08-17 01:00:00'),
                   (2,UUID(),43,'OrderConfirmed',1,'2026-08-18 01:00:00','{}','2026-08-18 01:00:00'),
                   (3,UUID(),44,'OrderConfirmed',1,'2026-08-19 01:00:00','{}','2026-08-19 01:00:00')
            """);
        jdbc.update("""
            INSERT INTO product_purchase_activities (inbox_id,order_id,product_id,quantity,confirmed_at)
            VALUES (1,42,7,2,'2026-08-17 01:00:00'),(1,42,8,1,'2026-08-17 01:00:00'),
                   (2,43,7,3,'2026-08-18 01:00:00'),(3,44,9,5,'2026-08-19 01:00:00')
            """);
    }

    private record Attempt(long instance, long execution, String status, long read, long written) {}
}
