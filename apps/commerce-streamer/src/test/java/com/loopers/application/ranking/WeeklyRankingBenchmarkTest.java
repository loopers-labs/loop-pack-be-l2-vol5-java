package com.loopers.application.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.support.preservation.PreservedMySql;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("ranking-benchmark")
class WeeklyRankingBenchmarkTest {
    private static final LocalDate WEEK = LocalDate.of(2026, 8, 17);
    private static final int ORDERS = 2_048;
    private static final String EXPECTED_INPUT = "f00b4434fbe9d2d3fa613bcba1429fbae2ed50d278d87fb7c23942e294e97d7a";
    // Products 1..256 score 8 * (1 + productId % 4); products 257..512 each score 16.
    private static final String EXPECTED_CHECKSUM = "af984639cf2a88196c6b74cd014fa2a2bbf1edbf80b4d2e3b83d2e85dba8fcdf";
    private static final String ATTEMPTS = """
        WITH attempts AS (
            SELECT j.JOB_EXECUTION_ID,j.START_TIME,j.END_TIME,partition_param.PARAMETER_VALUE AS order_partition_id
            FROM BATCH_JOB_EXECUTION_PARAMS id JOIN BATCH_JOB_EXECUTION_PARAMS partition_param ON partition_param.JOB_EXECUTION_ID=id.JOB_EXECUTION_ID
            JOIN BATCH_JOB_EXECUTION j ON j.JOB_EXECUTION_ID=id.JOB_EXECUTION_ID
            WHERE id.PARAMETER_NAME='snapshotId' AND id.PARAMETER_VALUE=? AND partition_param.PARAMETER_NAME='orderPartitionId'
        )
        """;
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) private Path logs;

    @ParameterizedTest(name = "{0}개 인스턴스 × {1}개 로컬 worker")
    @CsvSource({"1,1", "1,4", "2,2"})
    @DisplayName("동일한 4096개 구매 활동에서 세 토폴로지의 공개 지연과 DB·커넥션 비용을 측정한다")
    void comparesWarmTopologies(int nodes, int workers) throws Exception {
        try (var database = new PreservedMySql()) {
            RankingProcessLab.prepare(database);
            var lab = new RankingProcessLab(logs.resolve(nodes + "x" + workers), database);
            seed(lab.jdbc);
            System.out.printf("RANKING_ENV MySQL=%s Java=%s nodes=%d workers=%d%n",
                lab.jdbc.queryForObject("SELECT VERSION()", String.class), Runtime.version(), nodes, workers);
            try (var first = lab.resident("bench-a", workers, 24 / nodes);
                 var second = nodes == 2 ? lab.resident("bench-b", workers, 24 / nodes) : null) {
                lab.awaitMembers(nodes, first, second);
                List<String> active = nodes == 2 ? List.of("bench-a", "bench-b") : List.of("bench-a");
                for (int revision = 1; revision <= 4; revision++) {
                    try (var request = lab.measuredRequest(WEEK, revision, workers)) {
                        lab.awaitMeasurementStart(revision, request);
                        var poolsBefore = poolTotals(lab, active);
                        long rowsBefore = rowsExamined(lab.jdbc);
                        lab.startMeasurement(revision);
                        String snapshot = lab.awaitPublication(WEEK, revision, request);
                        var elapsed = lab.jdbc.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND, ?, UTC_TIMESTAMP(6))",
                            Long.class, lab.requestStarted(revision));
                        var poolsAfter = poolTotals(lab, active);
                        long inspected = rowsExamined(lab.jdbc) - rowsBefore;
                        request.awaitSuccess();
                        var facts = lab.jdbc.queryForMap("""
                            SELECT c.result_checksum, c.product_count, s.input_count, s.input_checksum,
                                (SELECT COUNT(*) FROM weekly_ranking_partial_scores w WHERE w.snapshot_id=c.snapshot_id) AS partial_count
                            FROM weekly_ranking_completions c
                            JOIN weekly_ranking_snapshots s USING(snapshot_id) WHERE c.snapshot_id=?
                            """, snapshot);
                        String digest = (String) facts.get("result_checksum");
                        assertThat(facts).containsEntry("input_count", 2L * ORDERS).containsEntry("product_count", 512L)
                            .containsEntry("input_checksum", EXPECTED_INPUT).containsEntry("partial_count", nodes * 512L);
                        assertThat(digest).isEqualTo(EXPECTED_CHECKSUM);
                        assertThat(lab.jdbc.queryForList("""
                            SELECT owner_id FROM weekly_ranking_order_partitions WHERE snapshot_id=? AND state='DONE'
                            """, String.class, snapshot)).containsExactlyInAnyOrderElementsOf(active);
                        var execution = lab.jdbc.queryForMap(ATTEMPTS + """
                            SELECT SUM(attempt_count) AS job_attempts, MAX(wall_us) AS slowest_order_partition FROM (
                                SELECT order_partition_id,COUNT(*) AS attempt_count,TIMESTAMPDIFF(MICROSECOND,MIN(START_TIME),MAX(END_TIME)) AS wall_us
                                FROM attempts GROUP BY order_partition_id
                            ) by_order_partition
                            """, snapshot);
                        long slowest = ((Number) execution.get("slowest_order_partition")).longValue();
                        long attempts = ((Number) execution.get("job_attempts")).longValue();
                        var steps = lab.jdbc.queryForMap(ATTEMPTS + """
                            SELECT COUNT(DISTINCT a.order_partition_id,e.STEP_NAME) AS completed_local_product_partitions
                            FROM attempts a JOIN BATCH_STEP_EXECUTION e ON e.JOB_EXECUTION_ID=a.JOB_EXECUTION_ID
                            WHERE e.STEP_NAME LIKE 'scoreWeeklyRankingLocalProductPartition:localProductPartition-%' AND e.STATUS='COMPLETED'
                            """, snapshot);
                        int completed = ((Number) steps.get("completed_local_product_partitions")).intValue();
                        assertThat(completed).isEqualTo(nodes * workers);
                        var overlap = lab.jdbc.queryForMap(ATTEMPTS + """
                            , workers AS (
                              SELECT e.START_TIME,e.END_TIME FROM BATCH_STEP_EXECUTION e JOIN attempts a USING(JOB_EXECUTION_ID)
                              WHERE e.STEP_NAME LIKE 'scoreWeeklyRankingLocalProductPartition:localProductPartition-%'
                            ), times AS (SELECT START_TIME AS t,1 AS delta FROM workers UNION ALL SELECT END_TIME,-1 FROM workers)
                            SELECT (SELECT COUNT(*) FROM workers) AS worker_runs, MAX(active) AS peak_workers
                            FROM (SELECT SUM(SUM(delta)) OVER (ORDER BY t) AS active FROM times GROUP BY t) events
                            """, snapshot);
                        int runs = ((Number) overlap.get("worker_runs")).intValue();
                        int peak = ((Number) overlap.get("peak_workers")).intValue();
                        long acquires = poolsAfter[0] - poolsBefore[0];
                        assertThat(slowest).isPositive();
                        assertThat(inspected).isPositive();
                        assertThat(acquires).isPositive();
                        System.out.printf(Locale.ROOT, """
                            RANKING_BENCH nodes=%d workers=%d warmup=%s trial=%d inputRows=%d products=512 inputChecksum=%s checksum=%s totalMs=%.3f slowestOrderPartitionMs=%.3f completedLocalProductPartitions=%d jobAttempts=%d workerRuns=%d peakWorkers=%d sourceStatementRowsExamined=%d poolAcquires=%d meanAcquireMs=%.4f%n""",
                            nodes, workers, revision == 1, revision - 1, ORDERS * 2, EXPECTED_INPUT, digest, elapsed / 1000.0,
                            slowest / 1000.0, completed, attempts, runs, peak, inspected, acquires, (poolsAfter[1] - poolsBefore[1]) / 1_000_000.0 / acquires);
                    }
                }
            }
        }
    }

    private static long[] poolTotals(RankingProcessLab lab, List<String> nodes) throws Exception {
        long[] values = {0, 0};
        for (String node : nodes) {
            var observations = lab.pool(node);
            values[0] += observations[0];
            values[1] += observations[1];
        }
        return values;
    }

    private static long rowsExamined(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
            SELECT COALESCE(SUM(SUM_ROWS_EXAMINED),0) FROM performance_schema.events_statements_summary_by_digest
            WHERE SCHEMA_NAME=DATABASE() AND UPPER(DIGEST_TEXT) LIKE '%PRODUCT_PURCHASE_ACTIVITIES%'
            """, Long.class);
    }

    private static void seed(JdbcTemplate jdbc) {
        var orders = IntStream.rangeClosed(1, ORDERS).boxed().toList();
        jdbc.batchUpdate("""
            INSERT INTO order_confirmation_inbox(id,event_id,order_id,event_type,schema_version,confirmed_at,normalized_payload,processed_at)
            VALUES(?,?,?,'OrderConfirmed',1,'2026-08-18','{}','2026-08-18')
            """, orders, 256, (query, id) -> {
                query.setInt(1, id);
                query.setString(2, new UUID(0, id).toString());
                query.setInt(3, id);
            });
        jdbc.batchUpdate("""
            INSERT INTO product_purchase_activities(inbox_id,order_id,product_id,quantity,confirmed_at)
            VALUES (?,?,?,?,'2026-08-18'),(?,?,?,2,'2026-08-18')
            """, orders, 256, (query, id) -> {
                query.setInt(1, id);
                query.setInt(2, id);
                query.setInt(3, 1 + (id - 1) % 256);
                query.setInt(4, 1 + id % 4);
                query.setInt(5, id);
                query.setInt(6, id);
                query.setInt(7, 257 + (id * 31) % 256);
            });
    }
}
