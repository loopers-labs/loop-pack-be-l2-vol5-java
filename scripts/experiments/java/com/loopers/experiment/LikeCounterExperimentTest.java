package com.loopers.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in JDBC experiment, never loaded by the production application. */
class LikeCounterExperimentTest {
    private static final String IDS = "97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,"
        + "67407,64407,61407,58407,55407,52407,49407,46407,43407,40407";
    private static final long TARGET = 97407;
    private static final String CONTROL = "w4_counter_control";
    private static final String RELATION = "w4_counter_relation";
    private static final String COUNTER = "w4_counter_value";
    private static final int WARMUP_SECONDS = 2;
    private static final int MEASURE_SECONDS = 8;

    private record Mode(String db, boolean counter) {
        String table() { return counter ? RELATION : CONTROL; }
        String sql() {
            return counter
                ? "SELECT product_id,like_count FROM " + COUNTER + " WHERE product_id IN (" + IDS + ")"
                : "SELECT product_id,COUNT(*) FROM " + CONTROL + " WHERE product_id IN (" + IDS + ") GROUP BY product_id";
        }
    }

    @Test
    void verifyCounterAndCompareUnderReadAndMixedLoad() throws Exception {
        Path root = Path.of(System.getProperty("week4.repoRoot"));
        Path output = root.resolve("docs/week4/measurements/100k-counter-experiment.json");
        assertThat(Files.exists(output)).isFalse();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("recorded_at", Instant.now().toString());
        report.put("notes", List.of(
            "JDBC experiment, not HTTP latency. No production policy or implementation change.",
            "Same indexed relationship schema in both modes; candidate maintains separate per-product counter.",
            "Fixed closed-loop workers with persistent connections: read-only 4 readers; mixed 4 readers + 2 writers.",
            "Two rounds per scenario; alternating mode order; 2s warmup + 8s measured per run.",
            "Read latency includes JDBC query and full result consumption; write includes transaction and commit.",
            "Mixed writes alternate register/cancel on one hot product with distinct synthetic users in FK-free copies.",
            "No fixed arrival-rate/SLO claim; closed-loop results can hide queueing under external overload.",
            "Counter backfill performed offline on disposable copies, not an online migration.",
            "Prepared statements created per operation; connection startup and correctness audit excluded from timing."));
        for (String db : List.of("loopers_benchmark_100k", "loopers_benchmark_100k_hotspot")) {
            List<String> created = new ArrayList<>();
            try (Connection c = connect(db)) {
                String before = sourceFingerprint(c);
                assertThat(scalar(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                    + "AND table_name IN ('" + CONTROL + "','" + RELATION + "','" + COUNTER + "')")).isZero();
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("source_before", before);
                data.put("environment", rows(c, "SELECT VERSION(),DATABASE(),@@transaction_isolation,"
                    + "@@innodb_flush_log_at_trx_commit,@@sync_binlog,@@log_bin"));
                try {
                    for (String t : List.of(CONTROL, RELATION)) {
                        exec(c, "CREATE TABLE " + t + " LIKE product_like");
                        created.add(t);
                        exec(c, "INSERT INTO " + t + " SELECT * FROM product_like");
                        exec(c, "ANALYZE TABLE " + t);
                    }
                    exec(c, "CREATE TABLE " + COUNTER + " (product_id BIGINT PRIMARY KEY,"
                        + "like_count BIGINT NOT NULL CHECK (like_count >= 0)) ENGINE=InnoDB");
                    created.add(COUNTER);
                    exec(c, "INSERT INTO " + COUNTER + " SELECT p.id,COUNT(l.id) FROM product p "
                        + "LEFT JOIN " + RELATION + " l ON l.product_id=p.id GROUP BY p.id");
                    exec(c, "ANALYZE TABLE " + COUNTER);
                    Mode candidate = new Mode(db, true);
                    Mode baseline = new Mode(db, false);
                    audit(c);
                    // Normal, duplicate, missing, rollback and races must pass before timing.
                    data.put("correctness", correctness(candidate));
                    audit(c);
                    Map<Long, Long> expected = seedExpected(db.endsWith("hotspot"));
                    assertThat(read(c, baseline)).isEqualTo(expected);
                    assertThat(read(c, candidate)).isEqualTo(expected);
                    data.put("expected_counts", expected);
                    data.put("aggregate_plan", rows(c, "EXPLAIN ANALYZE " + baseline.sql()));
                    data.put("counter_plan", rows(c, "EXPLAIN ANALYZE " + candidate.sql()));
                    List<Map<String, Object>> runs = new ArrayList<>();
                    for (boolean mixed : List.of(false, true)) {
                        for (int round = 0; round < 2; round++) {
                            for (Mode mode : (round == 0 ? List.of(baseline, candidate) : List.of(candidate, baseline))) {
                                Map<String, Object> run = workload(mode, mixed, expected);
                                run.put("round", round + 1);
                                runs.add(run);
                                audit(c);
                                assertThat(read(c, baseline)).isEqualTo(expected);
                                assertThat(read(c, candidate)).isEqualTo(expected);
                                System.out.println(db + " " + run);
                            }
                        }
                    }
                    data.put("runs", runs);
                    data.put("counter_storage", rows(c, "SELECT data_length,index_length FROM information_schema.tables "
                        + "WHERE table_schema=DATABASE() AND table_name='" + COUNTER + "'"));
                } finally {
                    Collections.reverse(created);
                    for (String table : created) {
                        exec(c, "DROP TABLE " + table);
                    }
                }
                assertThat(sourceFingerprint(c)).isEqualTo(before);
                data.put("source_unchanged", true);
                data.put("temporary_tables_removed", true);
                report.put(db, data);
            }
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }

    private static Map<Long, Long> seedExpected(boolean hot) {
        Map<Long, Long> result = new LinkedHashMap<>();
        for (String text : IDS.split(",")) {
            long id = Long.parseLong(text);
            result.put(id, (id % 113 == 0 ? 150 : id % 11) + (hot && id == TARGET ? 50000 : 0));
        }
        return result;
    }

    private static List<String> correctness(Mode mode) throws Exception {
        long user = 800000;
        List<String> passed = new ArrayList<>();
        try (Connection c = connect(mode.db())) {
            assertThat(change(c, mode, user, true, 0)).isEqualTo(1);
            assertThat(change(c, mode, user, true, 0)).isZero();
            audit(c);
            assertThat(change(c, mode, user, false, 0)).isEqualTo(1);
            assertThat(change(c, mode, user, false, 0)).isZero();
            audit(c);
            passed.add("first insert / duplicate insert / first cancel / absent cancel");
            for (int failure : List.of(1, 2)) {
                assertThatThrownBy(() -> change(c, mode, user, true, failure)).isInstanceOf(SQLException.class);
                assertThat(scalar(c, "SELECT COUNT(*) FROM " + RELATION + " WHERE user_id=" + user)).isZero();
                audit(c);
                change(c, mode, user, true, 0);
                assertThatThrownBy(() -> change(c, mode, user, false, failure)).isInstanceOf(SQLException.class);
                assertThat(scalar(c, "SELECT COUNT(*) FROM " + RELATION + " WHERE user_id=" + user)).isEqualTo(1);
                audit(c);
                change(c, mode, user, false, 0);
            }
            passed.add("insert/cancel rollback after relationship and after counter update");
        }
        assertThat(race(mode, true, false).stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        try (Connection c = connect(mode.db())) { audit(c); }
        assertThat(race(mode, false, false).stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        passed.add("8 simultaneous duplicate registrations and cancellations: exactly one change");
        assertThat(race(mode, true, true).stream().mapToInt(Integer::intValue).sum()).isEqualTo(8);
        try (Connection c = connect(mode.db())) { audit(c); }
        assertThat(race(mode, false, true).stream().mapToInt(Integer::intValue).sum()).isEqualTo(8);
        passed.add("8 distinct users on the same product: all increments/decrements preserved");
        // Deterministic uncommitted registration followed by cancel, and vice versa.
        for (boolean register : List.of(true, false)) {
            try (Connection first = connect(mode.db())) {
                if (!register) { change(first, mode, user, true, 0); }
                first.setAutoCommit(false);
                mutate(first, mode, user, register, 0);
                var executor = Executors.newSingleThreadExecutor();
                CountDownLatch started = new CountDownLatch(1);
                try {
                    Future<Integer> pending = executor.submit(() -> {
                        try (Connection second = connect(mode.db())) {
                            started.countDown();
                            return change(second, mode, user, !register, 0);
                        }
                    });
                    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> pending.get(150, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                    first.commit();
                    first.setAutoCommit(true);
                    assertThat(pending.get(10, TimeUnit.SECONDS)).isEqualTo(1);
                    audit(first);
                    assertThat(scalar(first, "SELECT COUNT(*) FROM " + RELATION + " WHERE user_id=" + user))
                        .isEqualTo(register ? 0 : 1);
                    change(first, mode, user, false, 0);
                } finally {
                    if (!first.getAutoCommit()) { first.rollback(); }
                    executor.shutdownNow();
                    assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
                }
            }
        }
        passed.add("uncommitted register/cancel in both orders: follower waits, final state consistent");
        return passed;
    }

    private static List<Integer> race(Mode mode, boolean register, boolean distinct) throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> jobs = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                long user = 800010 + (distinct ? i : 0);
                jobs.add(executor.submit(() -> {
                    try (Connection c = connect(mode.db())) {
                        ready.countDown();
                        if (!go.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("start timeout"); }
                        return change(c, mode, user, register, 0);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> result = new ArrayList<>();
            for (Future<Integer> job : jobs) { result.add(job.get(15, TimeUnit.SECONDS)); }
            return result;
        } finally {
            go.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static int change(Connection c, Mode mode, long user, boolean register, int failure) throws SQLException {
        c.setAutoCommit(false);
        try {
            int changed = mutate(c, mode, user, register, failure);
            c.commit();
            return changed;
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    private static int mutate(Connection c, Mode mode, long user, boolean register, int failure) throws SQLException {
        int changed;
        String sql = register ? "INSERT INTO " + mode.table()
            + " (user_id,product_id,created_at,updated_at) VALUES (?, ?, UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))"
            : "DELETE FROM " + mode.table() + " WHERE user_id=? AND product_id=?";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, user);
            s.setLong(2, TARGET);
            try {
                changed = s.executeUpdate();
            } catch (SQLException e) {
                if (register && e.getErrorCode() == 1062 && e.getMessage().contains("uk_product_like_user_product")) {
                    changed = 0;
                } else { throw e; }
            }
        }
        if (failure == 1) { throw new SQLException("injected after relationship"); }
        if (mode.counter() && changed != 0) {
            try (PreparedStatement s = c.prepareStatement("UPDATE " + COUNTER
                + " SET like_count=like_count+? WHERE product_id=? AND like_count+? >= 0")) {
                int delta = register ? changed : -changed;
                s.setInt(1, delta);
                s.setLong(2, TARGET);
                s.setInt(3, delta);
                if (s.executeUpdate() != 1) { throw new SQLException("missing or inconsistent counter"); }
            }
        }
        if (failure == 2) { throw new SQLException("injected after counter"); }
        return changed;
    }

    private static Map<String, Object> workload(Mode mode, boolean mixed, Map<Long, Long> expected) throws Exception {
        int workers = mixed ? 6 : 4;
        var executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicLong start = new AtomicLong();
        List<Future<Map<String, List<Double>>>> jobs = new ArrayList<>();
        long warm = TimeUnit.SECONDS.toNanos(WARMUP_SECONDS);
        long duration = TimeUnit.SECONDS.toNanos(MEASURE_SECONDS);
        long batchStart = System.nanoTime();
        try {
            for (int i = 0; i < workers; i++) {
                final int worker = i;
                jobs.add(executor.submit((Callable<Map<String, List<Double>>>) () -> {
                    Map<String, List<Double>> values = new LinkedHashMap<>();
                    try (Connection c = connect(mode.db())) {
                        ready.countDown();
                        if (!go.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("load start timeout"); }
                        boolean register = true;
                        try {
                            while (System.nanoTime() < start.get() + warm + duration) {
                                long begin = System.nanoTime();
                                String kind;
                                if (worker < 4) {
                                    kind = "read";
                                    Map<Long, Long> rows = read(c, mode);
                                    if (!mixed) { assertThat(rows).isEqualTo(expected); }
                                    else {
                                        for (long id : expected.keySet()) {
                                            if (id == TARGET) {
                                                assertThat(rows.get(id)).isBetween(expected.get(id), expected.get(id) + 2);
                                            } else { assertThat(rows.get(id)).isEqualTo(expected.get(id)); }
                                        }
                                    }
                                } else {
                                    kind = register ? "register" : "cancel";
                                    assertThat(change(c, mode, 900000L + worker, register, 0)).isEqualTo(1);
                                    register = !register;
                                }
                                double ms = (System.nanoTime() - begin) / 1_000_000.0;
                                if (begin >= start.get() + warm) {
                                    values.computeIfAbsent(kind, key -> new ArrayList<>()).add(ms);
                                }
                            }
                        } finally {
                            if (worker >= 4) { change(c, mode, 900000L + worker, false, 0); }
                        }
                    }
                    return values;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.set(System.nanoTime());
            go.countDown();
            Map<String, List<Double>> all = new LinkedHashMap<>();
            for (var job : jobs) {
                for (var entry : job.get(30, TimeUnit.SECONDS).entrySet()) {
                    all.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).addAll(entry.getValue());
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode", mode.counter() ? "counter" : "aggregate");
            result.put("scenario", mixed ? "mixed" : "read_only");
            result.put("warmup_seconds", WARMUP_SECONDS);
            result.put("measurement_seconds", MEASURE_SECONDS);
            result.put("workers", workers);
            result.put("errors", 0);
            result.put("wall_seconds_including_setup_cleanup", (System.nanoTime() - batchStart) / 1e9);
            for (var entry : all.entrySet()) { result.put(entry.getKey(), stats(entry.getValue())); }
            return result;
        } finally {
            go.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static Map<String, Object> stats(List<Double> values) {
        assertThat(values).hasSizeGreaterThan(30);
        Collections.sort(values);
        return Map.of("requests", values.size(), "median_ms", values.get(values.size() / 2),
            "p95_ms", values.get((int) Math.ceil(values.size() * .95) - 1),
            "p99_ms", values.get((int) Math.ceil(values.size() * .99) - 1),
            "max_ms", values.getLast(), "operations_per_second", values.size() / (double) MEASURE_SECONDS);
    }

    private static Map<Long, Long> read(Connection c, Mode mode) throws SQLException {
        Map<Long, Long> result = new LinkedHashMap<>();
        for (String id : IDS.split(",")) { result.put(Long.parseLong(id), 0L); }
        try (PreparedStatement s = c.prepareStatement(mode.sql()); ResultSet r = s.executeQuery()) {
            while (r.next()) { result.put(r.getLong(1), r.getLong(2)); }
        }
        return result;
    }

    private static void audit(Connection c) throws SQLException {
        assertThat(scalar(c, "SELECT COUNT(*) FROM " + COUNTER)).isEqualTo(100000);
        assertThat(scalar(c, "SELECT COUNT(*) FROM " + COUNTER + " c LEFT JOIN "
            + "(SELECT product_id,COUNT(*) n FROM " + RELATION + " GROUP BY product_id) l "
            + "ON l.product_id=c.product_id WHERE c.like_count <> COALESCE(l.n,0)")).isZero();
        assertThat(scalar(c, "SELECT COUNT(*) FROM " + RELATION + " l LEFT JOIN " + COUNTER
            + " c ON c.product_id=l.product_id WHERE c.product_id IS NULL")).isZero();
    }

    private static Connection connect(String db) throws SQLException {
        if (!List.of("loopers_benchmark_100k", "loopers_benchmark_100k_hotspot").contains(db)) {
            throw new IllegalArgumentException("Unexpected database");
        }
        Connection c = DriverManager.getConnection("jdbc:mysql://localhost:3307/" + db
            + "?connectTimeout=5000&socketTimeout=15000", "application", "application");
        exec(c, "SET SESSION innodb_lock_wait_timeout=5");
        return c;
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute(sql); }
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { r.next(); return r.getLong(1); }
    }

    private static String rows(Connection c, String sql) throws SQLException {
        StringBuilder result = new StringBuilder();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            while (r.next()) {
                for (int col = 1; col <= r.getMetaData().getColumnCount(); col++) {
                    result.append(r.getString(col)).append('\t');
                }
                result.append('\n');
            }
        }
        return result.toString();
    }

    private static String sourceFingerprint(Connection c) throws SQLException {
        return rows(c, "SELECT COUNT(*),SUM(id),SUM(price),SUM(quantity),SUM(deleted_at IS NULL) FROM product")
            + rows(c, "SELECT COUNT(*),SUM(id),SUM(user_id),SUM(product_id) FROM product_like")
            + rows(c, "SELECT table_name,index_name,seq_in_index,column_name,collation FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() ORDER BY table_name,index_name,seq_in_index");
    }
}
