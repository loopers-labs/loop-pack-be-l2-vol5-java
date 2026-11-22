package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.ProductScore;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 동일한 랭킹 UPSERT로 Connector/J의 rewrite 여부와 저장 비용을 구분해 확인한다. */
@Tag("experiment")
@SpringBootTest(properties = {
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "spring.batch.job.enabled=false"
})
class ScoreWriterBatchRewriteExperimentTest {
    private static final int CHUNK_SIZE = 100;
    private static final int CHUNKS = 20;

    @Autowired
    @Qualifier("mySqlMainDataSource")
    private HikariDataSource batchDataSource;

    @Test
    void compareWriterWithAndWithoutRewrite() {
        assertThat(batchDataSource.getDataSourceProperties())
            .containsEntry("rewriteBatchedStatements", "true");

        try (var withoutRewrite = pool(false); var withRewrite = pool(true)) {
            var off = new WriterRun(withoutRewrite);
            var on = new WriterRun(withRewrite);
            verifySqlAndRepeatedWrite(off, on);
            measure("off", off, "warmup");
            measure("on", on, "warmup");
            for (int round = 1; round <= 3; round++) {
                String label = "round-" + round;
                if (round % 2 == 0) {
                    measure("on", on, label);
                    measure("off", off, label);
                } else {
                    measure("off", off, label);
                    measure("on", on, label);
                }
            }
        }
    }

    /**
     * 현재 Writer와 같은 SQL(WeeklyRankingStore.insertScores, 단순 INSERT)로 rewrite 여부에 따른 서버 문장 수와 저장 시간을 비교한다.
     * 결과는 docs/week9/experiments.md 실험 8-2.
     */
    @Test
    void compareCurrentInsertWriterWithAndWithoutRewrite() {
        try (var withoutRewrite = pool(false); var withRewrite = pool(true)) {
            var off = new InsertRun(withoutRewrite);
            var on = new InsertRun(withRewrite);

            JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(batchDataSource.getJdbcUrl(), "root", "test"));
            root.execute("SET GLOBAL log_output = 'TABLE'");
            root.execute("SET GLOBAL general_log = 'ON'");
            try {
                off.write("insert-sql-off", scores(1));
                on.write("insert-sql-on", scores(1));
                assertThatThrownBy(() -> on.transaction().executeWithoutResult(status -> {
                    on.store().insertScores("insert-sql-rollback", scores(1));
                    throw new IllegalStateException("commit 전 실패");
                })).isInstanceOf(IllegalStateException.class);
            } finally {
                root.execute("SET GLOBAL general_log = 'OFF'");
            }
            var individual = writesOnServer(root, "insert-sql-off");
            var rewritten = writesOnServer(root, "insert-sql-on");
            assertThat(individual).hasSize(CHUNK_SIZE);
            assertThat(rewritten).hasSize(1);
            assertThat(rewritten.getFirst()).containsPattern("\\),\\s*\\(");
            assertThat(on.scoresFor("insert-sql-on")).containsExactlyElementsOf(scores(1));
            assertThat(on.scoresFor("insert-sql-rollback")).isEmpty();
            log("[INSERT] off: 100건 한 batch 호출 → 서버 INSERT %d건", individual.size());
            log("[INSERT] on: 100건 한 batch 호출 → 서버 INSERT %d건, rollback 후 %d건",
                rewritten.size(), on.scoresFor("insert-sql-rollback").size());

            measureInsert("off", off, "warmup");
            measureInsert("on", on, "warmup");
            for (int round = 1; round <= 3; round++) {
                String label = "round-" + round;
                if (round % 2 == 0) {
                    measureInsert("on", on, label);
                    measureInsert("off", off, label);
                } else {
                    measureInsert("off", off, label);
                    measureInsert("on", on, label);
                }
            }
        }
    }

    private static void measureInsert(String mode, InsertRun run, String label) {
        String snapshotId = "insert-" + mode + "-" + label;
        List<List<ProductScore>> chunks = LongStream.range(0, CHUNKS)
            .mapToObj(i -> scores(i * CHUNK_SIZE + 1)).toList();
        long started = System.nanoTime();
        for (List<ProductScore> chunk : chunks) {
            run.write(snapshotId, chunk);
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(run.scoresFor(snapshotId)).containsExactlyElementsOf(chunks.stream().flatMap(List::stream).toList());
        log("[INSERT] rewrite=%s %s rows=%d chunks=%d write+commit=%dms", mode, label, CHUNK_SIZE * CHUNKS, CHUNKS, elapsed);
    }

    /** 현재 ScoreStageWriter가 호출하는 저장소 메서드를 그대로 쓴다. */
    private record InsertRun(TransactionTemplate transaction, WeeklyRankingStore store, JdbcTemplate jdbc) {
        InsertRun(HikariDataSource source) {
            this(new TransactionTemplate(new DataSourceTransactionManager(source)), new WeeklyRankingStore(source), new JdbcTemplate(source));
        }

        void write(String snapshotId, List<ProductScore> scores) {
            transaction.executeWithoutResult(status -> store.insertScores(snapshotId, scores));
        }

        List<ProductScore> scoresFor(String snapshotId) {
            return jdbc.query("SELECT product_id, score FROM ranking_score_stage WHERE snapshot_id = ? ORDER BY product_id",
                (rs, n) -> new ProductScore(rs.getLong("product_id"), rs.getLong("score")), snapshotId);
        }
    }

    private void verifySqlAndRepeatedWrite(WriterRun off, WriterRun on) {
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(batchDataSource.getJdbcUrl(), "root", "test"));
        root.execute("SET GLOBAL log_output = 'TABLE'");
        root.execute("SET GLOBAL general_log = 'ON'");
        try {
            off.write("writer-sql-off", scores(1));
            on.write("writer-sql-on", scores(1));
            on.write("writer-sql-on", scores(1));
            assertThatThrownBy(() -> on.transaction().executeWithoutResult(status -> {
                upsertHistoricalScores(on.jdbc(), "writer-sql-rollback", scores(1));
                throw new IllegalStateException("commit 전 실패");
            })).isInstanceOf(IllegalStateException.class).hasMessage("commit 전 실패");
        } finally {
            root.execute("SET GLOBAL general_log = 'OFF'");
        }

        var individual = writesOnServer(root, "writer-sql-off");
        var rewritten = writesOnServer(root, "writer-sql-on");
        assertThat(individual).hasSize(CHUNK_SIZE);
        assertThat(rewritten).hasSize(2);
        assertThat(rewritten.getFirst()).containsPattern("\\),\\s*\\(");
        assertThat(on.scoresFor("writer-sql-on")).containsExactlyElementsOf(scores(1));
        assertThat(off.scoresFor("writer-sql-off")).containsExactlyElementsOf(scores(1));
        assertThat(on.scoresFor("writer-sql-rollback")).isEmpty();

        log("off: 100건 한 batch 호출 → 서버 DML %d건", individual.size());
        log("on: 100건 batch 두 번 호출 → 서버 DML %d건, 저장 %d건, rollback 후 %d건",
            rewritten.size(), on.scoresFor("writer-sql-on").size(), on.scoresFor("writer-sql-rollback").size());
    }

    private static List<String> writesOnServer(JdbcTemplate root, String snapshotId) {
        return root.queryForList("""
            SELECT CONVERT(argument USING utf8mb4) FROM mysql.general_log
            WHERE command_type IN ('Query', 'Execute')
              AND CONVERT(argument USING utf8mb4) LIKE 'INSERT INTO ranking_score_stage%'
              AND CONVERT(argument USING utf8mb4) LIKE ?
            ORDER BY event_time
            """, String.class, "%'" + snapshotId + "'%");
    }

    private static void measure(String mode, WriterRun run, String label) {
        String snapshotId = "writer-" + mode + "-" + label;
        List<List<ProductScore>> chunks = LongStream.range(0, CHUNKS)
            .mapToObj(i -> scores(i * CHUNK_SIZE + 1)).toList();
        long started = System.nanoTime();
        for (List<ProductScore> chunk : chunks) {
            run.write(snapshotId, chunk);
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(run.scoresFor(snapshotId)).containsExactlyElementsOf(chunks.stream().flatMap(List::stream).toList());
        log("rewrite=%s %s rows=%d chunks=%d write+commit=%dms", mode, label, CHUNK_SIZE * CHUNKS, CHUNKS, elapsed);
    }

    private HikariDataSource pool(boolean rewrite) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(batchDataSource.getJdbcUrl());
        config.setUsername(batchDataSource.getUsername());
        config.setPassword(batchDataSource.getPassword());
        config.setMaximumPoolSize(1);
        config.setDataSourceProperties(batchDataSource.getDataSourceProperties());
        config.addDataSourceProperty("rewriteBatchedStatements", Boolean.toString(rewrite));
        return new HikariDataSource(config);
    }

    private static List<ProductScore> scores(long first) {
        return LongStream.range(first, first + CHUNK_SIZE).mapToObj(id -> new ProductScore(id, id % 7 + 1)).toList();
    }

    private static void log(String format, Object... args) {
        System.out.println("[writer-rewrite] " + String.format(format, args));
    }

    /** 기존 실험과 같은 SQL을 사용하는 비교용 Writer. */
    static void upsertHistoricalScores(JdbcTemplate jdbc, String snapshotId, List<ProductScore> scores) {
        jdbc.batchUpdate("""
            INSERT INTO ranking_score_stage (snapshot_id, product_id, score) VALUES (?, ?, ?) AS incoming
            ON DUPLICATE KEY UPDATE score = incoming.score
            """, scores.stream().map(s -> new Object[] {snapshotId, s.productId(), s.score()}).toList());
    }

    private record WriterRun(TransactionTemplate transaction, JdbcTemplate jdbc) {
        WriterRun(HikariDataSource source) {
            this(new TransactionTemplate(new DataSourceTransactionManager(source)), new JdbcTemplate(source));
        }

        void write(String snapshotId, List<ProductScore> scores) {
            transaction.executeWithoutResult(status -> upsertHistoricalScores(jdbc, snapshotId, scores));
        }

        List<ProductScore> scoresFor(String snapshotId) {
            return jdbc.query("SELECT product_id, score FROM ranking_score_stage WHERE snapshot_id = ? ORDER BY product_id",
                (rs, n) -> new ProductScore(rs.getLong("product_id"), rs.getLong("score")), snapshotId);
        }
    }
}
