package com.loopers.job.ranking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 입력 고정(원본 → snapshot 입력 복사) 방식 비교 실험. 결과는 docs/week9/experiments.md.
 * <ul>
 *   <li>A: REPEATABLE READ TX 안에서 1,000건씩 나눠 읽고 앱에서 묶음 INSERT</li>
 *   <li>B: REPEATABLE READ TX에서 {@code INSERT … SELECT} 한 문장</li>
 *   <li>C: READ COMMITTED TX에서 {@code INSERT … SELECT} 한 문장</li>
 * </ul>
 * 원본 테이블은 commerce-streamer {@code product_purchase_activity}와 같은 열·인덱스로 만든다(confirmed_at 인덱스 없음).
 * 실행: {@code ./gradlew :apps:commerce-batch:experimentTest --tests '*SnapshotFreezeExperimentTest'}
 */
@Tag("experiment")
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class SnapshotFreezeExperimentTest {

    private static final LocalDateTime WEEK_START = LocalDateTime.parse("2026-08-17T00:00:00");
    private static final LocalDateTime WEEK_END = WEEK_START.plusDays(7);
    private static final int WEEK_ORDERS = 50_000;          // 주문마다 상품 2개 → 지난주 100,000행
    private static final int CURRENT_WEEK_ORDERS = 10_000;  // 이번 주 20,000행
    private static final int WEEK_ROWS = WEEK_ORDERS * 2;
    // 실험 1-3: 로컬 부담을 줄이려고 10만 행 안(지난주 8만 + 이번 주 1.6만)으로 둔다.
    private static final int REAL_WEEK_ORDERS = 40_000;
    private static final int REAL_CURRENT_WEEK_ORDERS = 8_000;
    private static final int PAGE_SIZE = 1_000;
    private static final int REPEAT = 3;
    private static final long EXTRA_ORDER_ID = 9_000_000L;  // 실험 중 다른 연결이 넣는 주문
    // 누적 실험: 전체 약 9.2만 행(지난주 1만 + 앞선 8주 각 1만 + 이번 주 2천). 로컬 PC 부담을 줄이려고 10만 행 안으로 둔다.
    private static final int HISTORY_WEEKS = 8;
    private static final int HISTORY_WEEK_ORDERS = 5_000;     // 주문마다 상품 2개 → 주당 1만 행
    private static final int HISTORY_CURRENT_WEEK_ORDERS = 1_000;
    private static final int HISTORY_FIRST_ORDER_ID = 1_000_001;

    private static final String INSERT_SELECT = """
        INSERT INTO exp_snapshot_input (snapshot_id, order_id, product_id, quantity, confirmed_at)
        SELECT ?, order_id, product_id, quantity, confirmed_at
        FROM exp_purchase_activity
        WHERE confirmed_at >= ? AND confirmed_at < ?
        """;

    private static final String PAGE_SELECT = """
        SELECT order_id, product_id, quantity, confirmed_at
        FROM exp_purchase_activity
        WHERE confirmed_at >= ? AND confirmed_at < ?
          AND (order_id > ? OR (order_id = ? AND product_id > ?))
        ORDER BY order_id, product_id
        LIMIT ?
        """;

    private enum Method {
        A_PAGED_REPEATABLE_READ(TransactionDefinition.ISOLATION_REPEATABLE_READ, true),
        B_INSERT_SELECT_REPEATABLE_READ(TransactionDefinition.ISOLATION_REPEATABLE_READ, false),
        C_INSERT_SELECT_READ_COMMITTED(TransactionDefinition.ISOLATION_READ_COMMITTED, false);

        private final int isolation;
        private final boolean paged;

        Method(int isolation, boolean paged) {
            this.isolation = isolation;
            this.paged = paged;
        }
    }

    @Autowired
    @Qualifier("mySqlMainDataSource")
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private int trial;
    private int targetRows;  // 이번 테스트에서 복사 대상(지난주) 행 수

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS exp_purchase_activity");
        jdbc.execute("DROP TABLE IF EXISTS exp_snapshot_input");
        jdbc.execute("""
            CREATE TABLE exp_purchase_activity (
              id BIGINT NOT NULL AUTO_INCREMENT,
              created_at DATETIME(6) NOT NULL,
              updated_at DATETIME(6) NOT NULL,
              deleted_at DATETIME(6),
              order_id BIGINT NOT NULL,
              product_id BIGINT NOT NULL,
              quantity INT NOT NULL,
              confirmed_at DATETIME(6) NOT NULL,
              PRIMARY KEY (id),
              UNIQUE KEY uk_purchase_order_product (order_id, product_id)
            ) ENGINE=InnoDB
            """);
        jdbc.execute("""
            CREATE TABLE exp_snapshot_input (
              snapshot_id VARCHAR(64) NOT NULL,
              order_id BIGINT NOT NULL,
              product_id BIGINT NOT NULL,
              quantity INT NOT NULL,
              confirmed_at DATETIME(6) NOT NULL,
              PRIMARY KEY (snapshot_id, order_id, product_id)
            ) ENGINE=InnoDB
            """);
    }

    /**
     * 실험 1-3: 인위적 지연 없이, 실제 복사 문장이 도는 도중 다른 연결의 INSERT가 얼마나 기다리는가.
     * 원본은 10만 행 안(지난주 8만 + 이번 주 1.6만, 복사 대상 83%)으로 둔다.
     * 같은 데이터에서 confirmed_at 인덱스 없음(A·B·C) → 있음(B·C) 순서로 각각 3번씩 돈다.
     */
    @Test
    void compareInsertWaitDuringRealCopy() throws Exception {
        seed(1, REAL_WEEK_ORDERS, WEEK_START);
        seed(REAL_WEEK_ORDERS + 1, REAL_CURRENT_WEEK_ORDERS, WEEK_END);
        targetRows = REAL_WEEK_ORDERS * 2;
        log("[실제 복사] 원본 %d행 = 지난주 %d행 + 이번 주 %d행", sourceRows(), targetRows, REAL_CURRENT_WEEK_ORDERS * 2);
        log("[기준] 복사 없이 INSERT 한 건: 중앙값 %d ms", baselineInsertMillis());
        long[] offsets = {0, 100, 200, 300, 400, 500, 700};
        analyze();
        logPlan("인덱스 없음");
        for (int round = 1; round <= REPEAT; round++) {
            for (Method method : Method.values()) {
                realCopyTrial(method, "인덱스 없음", offsets, 300);
            }
        }
        jdbc.execute("CREATE INDEX idx_exp_confirmed_at ON exp_purchase_activity (confirmed_at)");
        analyze();
        logPlan("인덱스 있음");
        for (int round = 1; round <= REPEAT; round++) {
            for (Method method : List.of(Method.B_INSERT_SELECT_REPEATABLE_READ, Method.C_INSERT_SELECT_READ_COMMITTED)) {
                realCopyTrial(method, "인덱스 있음(83%)", offsets, 300);
            }
        }
    }

    /**
     * 실험 1-3 누적: 원본 약 9.2만 행에서 복사 대상이 약 10.9%일 때(인덱스 있음, range 스캔) 실제 복사 도중의 INSERT 대기.
     */
    @Test
    void compareInsertWaitDuringRealCopyWithHistory() throws Exception {
        seedHistory();
        jdbc.execute("CREATE INDEX idx_exp_confirmed_at ON exp_purchase_activity (confirmed_at)");
        analyze();
        logPlan("누적, 인덱스 있음");
        log("[기준] 복사 없이 INSERT 한 건: 중앙값 %d ms", baselineInsertMillis());
        long[] offsets = {0, 10, 20, 30, 40, 60, 90};
        for (int round = 1; round <= REPEAT; round++) {
            for (Method method : List.of(Method.B_INSERT_SELECT_REPEATABLE_READ, Method.C_INSERT_SELECT_READ_COMMITTED)) {
                realCopyTrial(method, "누적 + 인덱스", offsets, 30);
            }
        }
    }

    /**
     * 실험 1-4: 주문이 계속 들어오는 동안 복사하면 INSERT 응답 시간 분포가 어떻게 달라지는가.
     * 실제 원본처럼 confirmed_at 인덱스는 두지 않는다. 대상 83%(9.6만 행)와 여러 주가 쌓인 원본(9.2만 행, 대상 10.9%).
     */
    @Test
    void compareContinuousInsertsDuringCopy() throws Exception {
        seed(1, REAL_WEEK_ORDERS, WEEK_START);
        seed(REAL_WEEK_ORDERS + 1, REAL_CURRENT_WEEK_ORDERS, WEEK_END);
        targetRows = REAL_WEEK_ORDERS * 2;
        analyze();
        logPlan("대상 83%, 인덱스 없음");
        for (int round = 1; round <= REPEAT; round++) {
            for (Method method : List.of(Method.B_INSERT_SELECT_REPEATABLE_READ, Method.C_INSERT_SELECT_READ_COMMITTED)) {
                continuousLoadTrial(method, "대상 83%");
            }
        }

        jdbc.execute("TRUNCATE TABLE exp_purchase_activity");
        seedHistory();
        analyze();
        logPlan("누적 10.9%, 인덱스 없음");
        for (int round = 1; round <= REPEAT; round++) {
            for (Method method : List.of(Method.B_INSERT_SELECT_REPEATABLE_READ, Method.C_INSERT_SELECT_READ_COMMITTED)) {
                continuousLoadTrial(method, "누적 10.9%");
            }
        }
    }

    private record InsertSample(boolean late, long startNanos, long tookNanos) {}

    /**
     * 연결 두 개(지난주 늦은 구매 / 이번 주 구매)가 쉬지 않고 한 건씩 INSERT하는 동안 복사한다.
     * 복사 전 300ms, 복사(문장 + commit), 복사 뒤 300ms를 재고, 시작 시각이 복사 구간에 든 INSERT와 그 밖의 INSERT를 나눠 본다.
     */
    private void continuousLoadTrial(Method method, String label) throws Exception {
        String snapshotId = method.name().charAt(0) + "-load-" + (++trial);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong nextOrder = new AtomicLong(EXTRA_ORDER_ID);
        List<InsertSample> samples = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(3);
        JdbcTemplate root = new JdbcTemplate(new SingleConnectionDataSource(jdbcUrl(), "root", "test", true));
        long[] window = new long[2];  // 복사 문장 시작, commit 끝
        try {
            List<Future<?>> loaders = new ArrayList<>();
            for (boolean late : new boolean[] {true, false}) {
                Connection connection = DriverManager.getConnection(jdbcUrl(),
                    System.getProperty("datasource.mysql-jpa.main.username"), System.getProperty("datasource.mysql-jpa.main.password"));
                loaders.add(pool.submit(() -> {
                    try (connection; PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO exp_purchase_activity (created_at, updated_at, order_id, product_id, quantity, confirmed_at)
                        VALUES (NOW(6), NOW(6), ?, 7, 1, ?)
                        """)) {
                        while (running.get()) {
                            insert.setLong(1, nextOrder.getAndIncrement());
                            insert.setObject(2, late ? WEEK_START.plusDays(3) : WEEK_END.plusDays(1));
                            long start = System.nanoTime();
                            insert.executeUpdate();
                            samples.add(new InsertSample(late, start, System.nanoTime() - start));
                        }
                    }
                    return null;
                }));
            }
            Set<String> waits = ConcurrentHashMap.newKeySet();
            int[] waitPolls = new int[2];  // 잠금 대기가 보인 조회 수, 전체 조회 수
            sleep(300);
            Future<?> monitor = pool.submit(() -> {
                while (window[1] == 0) {
                    List<String> found = root.query("""
                        SELECT r.index_name AS ri, r.lock_mode AS rm, b.lock_mode AS bm, b.lock_data AS bd
                        FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.data_locks r ON r.engine_lock_id = w.requesting_engine_lock_id
                        JOIN performance_schema.data_locks b ON b.engine_lock_id = w.blocking_engine_lock_id
                        WHERE r.object_name = 'exp_purchase_activity'
                        """, (rs, n) -> rs.getString("ri") + " " + rs.getString("rm") + " <- " + rs.getString("bm")
                        + ("supremum pseudo-record".equals(rs.getString("bd")) ? " (supremum)" : ""));
                    waits.addAll(found);
                    waitPolls[0] += found.isEmpty() ? 0 : 1;
                    waitPolls[1]++;
                    sleep(10);
                }
                return null;
            });
            long[] statementEnd = new long[1];
            long[] lockWaitsBefore = rowLockWaits(root);
            window[0] = System.nanoTime();
            transaction(method.isolation).executeWithoutResult(status -> {
                copy(method, snapshotId, null);
                statementEnd[0] = System.nanoTime();
            });
            window[1] = System.nanoTime();
            long[] lockWaitsAfter = rowLockWaits(root);
            monitor.get();
            sleep(300);
            running.set(false);
            for (Future<?> loader : loaders) {
                loader.get();
            }

            List<InsertSample> copied = new ArrayList<>(samples);
            // 복사 문장 시작 전에 끝난(= commit된) 늦은 구매 수. 이 행들은 어느 방식이든 snapshot에 들어가야 한다.
            long lateCommittedBefore = copied.stream()
                .filter(sample -> sample.late() && sample.startNanos() + sample.tookNanos() < window[0]).count();
            long lateDuringCopy = copied.stream()
                .filter(sample -> sample.late() && sample.startNanos() + sample.tookNanos() >= window[0]
                    && sample.startNanos() + sample.tookNanos() < statementEnd[0]).count();
            int extra = snapshotRows(snapshotId) - targetRows;
            log("[연속 부하] %s %s #%d: 복사 문장 %d ms, commit까지 %d ms, snapshot 대상 +%d행 = 복사 전 commit된 늦은 구매 %d + %d"
                    + " (복사 문장 도중 끝난 늦은 구매 %d건), 행 잠금 대기 %d회·합계 %d ms, data_lock_waits 조회 %d/%d, 대기 종류 %s",
                method, label, trial, millisBetween(window[0], statementEnd[0]), millisBetween(window[0], window[1]),
                extra, lateCommittedBefore, extra - lateCommittedBefore, lateDuringCopy,
                lockWaitsAfter[0] - lockWaitsBefore[0], lockWaitsAfter[1] - lockWaitsBefore[1],
                waitPolls[0], waitPolls[1], waits.isEmpty() ? "없음" : waits);
            for (boolean late : new boolean[] {true, false}) {
                for (boolean during : new boolean[] {true, false}) {
                    List<Long> took = copied.stream()
                        .filter(sample -> sample.late() == late)
                        .filter(sample -> during == (sample.startNanos() >= window[0] && sample.startNanos() < window[1]))
                        .map(sample -> sample.tookNanos() / 1_000)
                        .sorted()
                        .toList();
                    log("[연속 부하] %s %s #%d: %s INSERT, %s: %s", method, label, trial, late ? "지난주 늦은" : "이번 주",
                        during ? "복사 구간에 시작" : "복사 전후에 시작", distribution(took));
                }
            }
        } finally {
            running.set(false);
            pool.shutdownNow();
            jdbc.update("DELETE FROM exp_purchase_activity WHERE order_id >= ?", EXTRA_ORDER_ID);
        }
    }

    /** 서버 전체의 행 잠금 대기 누적 횟수와 누적 시간(ms). 실험 컨테이너에는 이 실험의 연결만 있다. */
    private static long[] rowLockWaits(JdbcTemplate root) {
        return root.queryForObject("""
            SELECT (SELECT variable_value FROM performance_schema.global_status WHERE variable_name = 'Innodb_row_lock_waits'),
                   (SELECT variable_value FROM performance_schema.global_status WHERE variable_name = 'Innodb_row_lock_time')
            """, (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)});
    }

    /** 마이크로초 표본의 건수·중앙값·p95·최대를 ms로 적는다. */
    private static String distribution(List<Long> micros) {
        if (micros.isEmpty()) {
            return "0건";
        }
        return String.format("%d건, 중앙값 %.1f ms, p95 %.1f ms, 최대 %.1f ms", micros.size(),
            micros.get(micros.size() / 2) / 1000.0, micros.get((int) Math.ceil(micros.size() * 0.95) - 1) / 1000.0,
            micros.get(micros.size() - 1) / 1000.0);
    }

    /**
     * 실험 1-추가: 원본에 지난 여러 주가 쌓인 경우. 기본 준비(지난주 10만·이번 주 2만)에 앞선 8주(주당 10만)를 더한다.
     * ③ C의 복사 문장이 도는 도중 commit된 행이 들어가는지, ① 누적 원본에서 A·C 시간과 confirmed_at 인덱스의 효과를 본다.
     */
    @Test
    void compareWithAccumulatedHistory() throws Exception {
        seedHistory();
        int total = sourceRows();
        log("[누적] 원본 %d행 = 지난주 %d행 + 앞선 %d주 %d행 + 이번 주 %d행 (복사 대상 %.1f%%)", total, targetRows,
            HISTORY_WEEKS, HISTORY_WEEKS * targetRows, HISTORY_CURRENT_WEEK_ORDERS * 2, 100.0 * targetRows / total);

        // ③ C의 한 문장이 원본을 읽는 도중 다른 연결이 지난주 구매를 commit하면 들어가는가 (인덱스 없음)
        for (long delay : new long[] {10, 30, 60}) {
            midStatementCommitTrial(delay);
        }

        // ① 인덱스 없음 / 있음에서 A·C 복사 시간과 실행 계획
        logPlan("누적, confirmed_at 인덱스 없음");
        timeCopies("누적, 인덱스 없음");
        jdbc.execute("CREATE INDEX idx_exp_confirmed_at ON exp_purchase_activity (confirmed_at)");
        logPlan("누적, confirmed_at 인덱스 있음");
        timeCopies("누적, 인덱스 있음");

    }

    /** 대량 INSERT 직후에는 통계가 갱신되지 않아 실행 계획이 실제 행 수와 다르게 정해질 수 있다. 측정 전에 통계를 갱신한다. */
    private void analyze() {
        jdbc.queryForList("ANALYZE TABLE exp_purchase_activity");
    }

    private void seedHistory() {
        seed(1, HISTORY_WEEK_ORDERS, WEEK_START);
        seed(HISTORY_WEEK_ORDERS + 1, HISTORY_CURRENT_WEEK_ORDERS, WEEK_END);
        for (int week = 1; week <= HISTORY_WEEKS; week++) {
            seed(HISTORY_FIRST_ORDER_ID + (week - 1) * HISTORY_WEEK_ORDERS, HISTORY_WEEK_ORDERS, WEEK_START.minusWeeks(week));
        }
        targetRows = HISTORY_WEEK_ORDERS * 2;
    }

    private void timeCopies(String label) {
        for (int round = 1; round <= 2; round++) {
            for (Method method : List.of(Method.A_PAGED_REPEATABLE_READ, Method.C_INSERT_SELECT_READ_COMMITTED)) {
                String snapshotId = method.name().charAt(0) + "-" + label.hashCode() + "-" + round;
                long started = System.nanoTime();
                transaction(method.isolation).executeWithoutResult(status -> copy(method, snapshotId, null));
                long elapsed = millisSince(started);
                assertThat(snapshotRows(snapshotId)).isEqualTo(targetRows);
                log("[누적 시간] %s %s 회차 %d: %d ms", label, method, round, elapsed);
            }
        }
    }

    /** C 복사 TX를 시작하고 delay 뒤 다른 연결이 지난주 늦은 구매를 commit한다. commit이 복사 문장보다 먼저 끝났는지와 포함 여부를 본다. */
    private void midStatementCommitTrial(long delayMillis) throws Exception {
        String snapshotId = "C-mid-" + delayMillis;
        long[] copyWindow = new long[2];
        CompletableFuture<Void> freeze = CompletableFuture.runAsync(() ->
            transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
                copyWindow[0] = System.nanoTime();
                copy(Method.C_INSERT_SELECT_READ_COMMITTED, snapshotId, null);
                copyWindow[1] = System.nanoTime();
            }));
        sleep(delayMillis);
        timedInsert(EXTRA_ORDER_ID, WEEK_START.plusDays(3));  // 자동 commit
        long committedAt = System.nanoTime();
        freeze.get();

        log("[문장 도중 commit] %d ms 뒤 commit: 복사 문장 %d ms 중 %d ms 지점, 복사가 끝나기 전 commit=%s → snapshot %d행 (늦은 구매 포함=%s)",
            delayMillis, (copyWindow[1] - copyWindow[0]) / 1_000_000, (committedAt - copyWindow[0]) / 1_000_000,
            committedAt < copyWindow[1], snapshotRows(snapshotId), snapshotRows(snapshotId) == targetRows + 1);
        jdbc.update("DELETE FROM exp_purchase_activity WHERE order_id >= ?", EXTRA_ORDER_ID);
    }

    @Test
    void compareFreezeMethods() throws Exception {
        seed(1, WEEK_ORDERS, WEEK_START);
        seed(WEEK_ORDERS + 1, CURRENT_WEEK_ORDERS, WEEK_END);
        targetRows = WEEK_ROWS;
        log("MySQL %s, binlog_format=%s, log_bin=%s, 기본 격리 수준=%s",
            jdbc.queryForObject("SELECT VERSION()", String.class),
            jdbc.queryForObject("SELECT @@binlog_format", String.class),
            jdbc.queryForObject("SELECT @@log_bin", String.class),
            jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
        log("원본: 지난주 %d행, 이번 주 %d행", WEEK_ROWS, CURRENT_WEEK_ORDERS * 2);

        // 1. 복사 시간
        for (int round = 1; round <= REPEAT; round++) {
            for (Method method : Method.values()) {
                String snapshotId = method.name().charAt(0) + "-time-" + round;
                long started = System.nanoTime();
                transaction(method.isolation).executeWithoutResult(status -> copy(method, snapshotId, null));
                long elapsed = millisSince(started);
                assertThat(snapshotRows(snapshotId)).isEqualTo(targetRows);
                log("[시간] %s 회차 %d: %d ms", method, round, elapsed);
            }
        }

        // 2. 복사 도중 다른 연결의 INSERT 대기는 compareInsertWaitDuringRealCopy(실험 1-3)에서 본다.

        // 3. 나눠 읽는 도중 commit된 행이 같은 snapshot에 들어가는가
        pagedVisibilityTrial(TransactionDefinition.ISOLATION_REPEATABLE_READ, "REPEATABLE READ");
        pagedVisibilityTrial(TransactionDefinition.ISOLATION_READ_COMMITTED, "READ COMMITTED");

        // 4. 복사 뒤 commit 전에 실패하면 snapshot이 남는가, 그 사이 다른 연결이 넣은 구매는 남는가
        for (Method method : Method.values()) {
            rollbackTrial(method);
        }

        // 5. confirmed_at 인덱스가 있을 때의 실행 계획
        logPlan("confirmed_at 인덱스 없음");
        jdbc.execute("CREATE INDEX idx_exp_confirmed_at ON exp_purchase_activity (confirmed_at)");
        logPlan("confirmed_at 인덱스 있음");
    }

    /** 복사 문장이 원본을 어떻게 읽는지(전체 스캔인지 인덱스 범위인지) 실행 계획을 남긴다. */
    private void logPlan(String indexLabel) {
        jdbc.query("EXPLAIN " + INSERT_SELECT, rs -> {
            if ("exp_purchase_activity".equals(rs.getString("table"))) {
                log("[실행 계획] %s: type=%s possible_keys=%s key=%s rows=%s",
                    indexLabel, rs.getString("type"), rs.getString("possible_keys"),
                    rs.getString("key"), rs.getString("rows"));
            }
        }, "plan", WEEK_START, WEEK_END);
    }

    /**
     * 실제 복사 도중의 INSERT 대기. 복사 TX는 복사 문장이 끝나면 바로 commit한다(인위적 지연 없음).
     * 복사 문장 시작 기준 offsetsMs 시점마다 지난주 늦은 구매 1건·이번 주 구매 1건을 각자 미리 연 연결(풀 밖)로 넣고,
     * 시작 시점·걸린 시간·그때 복사 문장이 돌고 있었는지를 남긴다. 복사가 끝날 때까지 10ms마다 data_lock_waits를 보고,
     * lockSampleAtMs 시점에 원본 잠금 개수를 한 번 센다.
     */
    private void realCopyTrial(Method method, String label, long[] offsetsMs, long lockSampleAtMs) throws Exception {
        String snapshotId = method.name().charAt(0) + "-real-" + (++trial);
        List<Connection> connections = new ArrayList<>();
        for (int i = 0; i < offsetsMs.length * 2; i++) {
            connections.add(DriverManager.getConnection(jdbcUrl(), System.getProperty("datasource.mysql-jpa.main.username"),
                System.getProperty("datasource.mysql-jpa.main.password")));
        }
        // performance_schema 조회용. 실험 계정은 권한이 없어 root로 읽는다. 10ms마다 조회하므로 연결 하나를 계속 쓴다.
        JdbcTemplate root = new JdbcTemplate(new SingleConnectionDataSource(jdbcUrl(), "root", "test", true));
        ExecutorService pool = Executors.newFixedThreadPool(offsetsMs.length * 2 + 2);
        long[] window = new long[3];  // 복사 문장 시작, 복사 문장 끝, commit 끝 (nanoTime)
        CountDownLatch started = new CountDownLatch(1);
        try {
            Future<?> freeze = pool.submit(() -> {
                transaction(method.isolation).executeWithoutResult(status -> {
                    window[0] = System.nanoTime();
                    started.countDown();
                    copy(method, snapshotId, null);
                    window[1] = System.nanoTime();
                });
                window[2] = System.nanoTime();
            });
            started.await();
            long base = window[0];

            Set<String> waits = ConcurrentHashMap.newKeySet();
            String[] lockSample = {"(채취 안 됨)"};
            Future<?> monitor = pool.submit(() -> {
                boolean sampled = false;
                while (!freeze.isDone()) {
                    root.query("""
                        SELECT r.index_name AS ri, r.lock_mode AS rm, b.lock_mode AS bm, b.lock_data AS bd
                        FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.data_locks r ON r.engine_lock_id = w.requesting_engine_lock_id
                        JOIN performance_schema.data_locks b ON b.engine_lock_id = w.blocking_engine_lock_id
                        """, rs -> {
                        waits.add(rs.getString("ri") + " " + rs.getString("rm") + " ← " + rs.getString("bm")
                            + ("supremum pseudo-record".equals(rs.getString("bd")) ? " (supremum)" : ""));
                    });
                    if (!sampled && millisBetween(base, System.nanoTime()) >= lockSampleAtMs) {
                        sampled = true;
                        long at = millisBetween(base, System.nanoTime());
                        // 어느 인덱스에 잠금이 걸렸는지로 실제로 어떤 경로(PRIMARY 전체 / confirmed_at 범위)로 읽는지 본다.
                        List<String> locks = root.query("""
                            SELECT index_name, lock_mode, COUNT(*) AS locks FROM performance_schema.data_locks
                            WHERE object_name = 'exp_purchase_activity' AND lock_type = 'RECORD'
                            GROUP BY index_name, lock_mode ORDER BY index_name, lock_mode
                            """, (rs, n) -> rs.getString("index_name") + " " + rs.getString("lock_mode") + " " + rs.getLong("locks"));
                        lockSample[0] = "시작 뒤 " + at + "ms 원본 RECORD 잠금 " + (locks.isEmpty() ? "없음" : locks)
                            + " (복사 문장 진행 중=" + (window[1] == 0) + ")";
                    }
                    sleep(10);
                }
                return null;
            });

            List<Future<String>> inserts = new ArrayList<>();
            for (int i = 0; i < offsetsMs.length; i++) {
                long offset = offsetsMs[i];
                Connection late = connections.get(i * 2);
                Connection current = connections.get(i * 2 + 1);
                long lateOrder = EXTRA_ORDER_ID + trial * 100L + i * 2L;
                inserts.add(pool.submit(() -> scheduledInsert(late, lateOrder, WEEK_START.plusDays(3), "지난주 늦은", base, offset, window)));
                inserts.add(pool.submit(() -> scheduledInsert(current, lateOrder + 1, WEEK_END.plusDays(1), "이번 주", base, offset, window)));
            }
            freeze.get();
            monitor.get();
            for (Future<String> insert : inserts) {
                log("[실제 복사] %s %s #%d: %s", method, label, trial, insert.get());
            }
            log("[실제 복사] %s %s #%d: 복사 문장 %d ms, commit까지 %d ms, snapshot %d행, 잠금 표본: %s, 관찰된 잠금 대기: %s",
                method, label, trial, millisBetween(window[0], window[1]), millisBetween(window[0], window[2]),
                snapshotRows(snapshotId), lockSample[0], waits.isEmpty() ? "없음" : waits);
        } finally {
            pool.shutdownNow();
            for (Connection connection : connections) {
                connection.close();
            }
            jdbc.update("DELETE FROM exp_purchase_activity WHERE order_id >= ?", EXTRA_ORDER_ID);
        }
    }

    /** 복사 문장 시작(base) 뒤 offset ms에 한 건을 넣고 "시작 시점·걸린 시간·단계"를 돌려준다. */
    private String scheduledInsert(Connection connection, long orderId, LocalDateTime confirmedAt, String kind,
                                   long base, long offsetMs, long[] window) throws Exception {
        long wait = offsetMs - millisBetween(base, System.nanoTime());
        if (wait > 0) {
            Thread.sleep(wait);
        }
        long start = System.nanoTime();
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO exp_purchase_activity (created_at, updated_at, order_id, product_id, quantity, confirmed_at)
            VALUES (NOW(6), NOW(6), ?, 7, 1, ?)
            """)) {
            insert.setLong(1, orderId);
            insert.setObject(2, confirmedAt);
            insert.executeUpdate();
        }
        long end = System.nanoTime();
        String phase = window[1] == 0 || start < window[1] ? "복사 문장 도중 시작"
            : window[2] == 0 || start < window[2] ? "문장 끝·commit 전 시작" : "commit 뒤 시작";
        return String.format("%s INSERT, 목표 %dms → 실제 시작 %dms, 걸린 시간 %d ms, %s",
            kind, offsetMs, millisBetween(base, start), millisBetween(start, end), phase);
    }

    /** 복사 없이 INSERT 한 건에 걸리는 시간(중앙값, 10회). */
    private long baselineInsertMillis() {
        List<Long> took = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            took.add(timedInsert(EXTRA_ORDER_ID + i, WEEK_START.plusDays(3)));
        }
        jdbc.update("DELETE FROM exp_purchase_activity WHERE order_id >= ?", EXTRA_ORDER_ID);
        took.sort(null);
        return took.get(took.size() / 2);
    }

    private static String jdbcUrl() {
        return System.getProperty("datasource.mysql-jpa.main.jdbc-url");
    }

    private static long millisBetween(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000;
    }

    private void rollbackTrial(Method method) throws Exception {
        String snapshotId = method.name().charAt(0) + "-rollback";
        int sourceBefore = sourceRows();
        long[] failedAt = new long[1];
        CompletableFuture<?>[] lateInsert = new CompletableFuture<?>[1];
        assertThatThrownBy(() -> transaction(method.isolation).executeWithoutResult(status -> {
            copy(method, snapshotId, null);
            // 복사 TX가 열린 동안 다른 연결이 지난주 늦은 구매를 넣는다. B에서는 잠금 때문에 rollback 뒤에 들어간다.
            lateInsert[0] = CompletableFuture.supplyAsync(() -> timedInsert(EXTRA_ORDER_ID, WEEK_START.plusDays(3)));
            sleep(200);
            failedAt[0] = System.nanoTime();
            throw new IllegalStateException("commit 전 실패");
        })).isInstanceOf(IllegalStateException.class);
        long rollbackMillis = millisSince(failedAt[0]);
        lateInsert[0].get();

        log("[롤백] %s: commit 전 실패 → rollback %d ms, snapshot %d행, 원본 %d → %d행(다른 연결이 넣은 1행)",
            method, rollbackMillis, snapshotRows(snapshotId), sourceBefore, sourceRows());
        jdbc.update("DELETE FROM exp_purchase_activity WHERE order_id >= ?", EXTRA_ORDER_ID);
    }

    private void pagedVisibilityTrial(int isolation, String label) {
        String snapshotId = "A-visibility-" + label.replace(' ', '_');
        transaction(isolation).executeWithoutResult(status -> copy(Method.A_PAGED_REPEATABLE_READ, snapshotId, () -> {
            // 첫 페이지를 읽은 직후, 다른 연결이 지난주 늦은 구매를 commit한다. order_id가 가장 커서 마지막 페이지에 읽힐 자리다.
            // 같은 스레드에서 넣으면 복사 TX의 연결을 같이 써서 자기 쓰기가 되므로, 다른 스레드에서 넣고 끝날 때까지 기다린다.
            CompletableFuture.runAsync(() -> timedInsert(EXTRA_ORDER_ID, WEEK_START.plusDays(3))).join();
        }));
        log("[가시성] 나눠 읽기 + %s: 첫 페이지 뒤 commit된 늦은 구매 포함 여부 → snapshot %d행 (원래 %d행)",
            label, snapshotRows(snapshotId), targetRows);
        jdbc.update("DELETE FROM exp_purchase_activity WHERE order_id >= ?", EXTRA_ORDER_ID);
    }

    /** 현재 TX 안에서 지난주 원본을 snapshot 입력으로 복사한다. afterFirstPage는 나눠 읽기에서만 쓴다. */
    private void copy(Method method, String snapshotId, Runnable afterFirstPage) {
        if (!method.paged) {
            jdbc.update(INSERT_SELECT, snapshotId, WEEK_START, WEEK_END);
            return;
        }
        long lastOrderId = 0;
        long lastProductId = 0;
        boolean first = true;
        while (true) {
            List<Object[]> page = jdbc.query(PAGE_SELECT,
                (rs, n) -> new Object[] {
                    snapshotId,
                    rs.getLong("order_id"),
                    rs.getLong("product_id"),
                    rs.getInt("quantity"),
                    rs.getObject("confirmed_at", LocalDateTime.class)
                },
                WEEK_START, WEEK_END, lastOrderId, lastOrderId, lastProductId, PAGE_SIZE);
            if (page.isEmpty()) {
                return;
            }
            jdbc.batchUpdate("INSERT INTO exp_snapshot_input VALUES (?, ?, ?, ?, ?)", page);
            Object[] last = page.get(page.size() - 1);
            lastOrderId = (long) last[1];
            lastProductId = (long) last[2];
            if (first && afterFirstPage != null) {
                afterFirstPage.run();
            }
            first = false;
        }
    }

    /** TX 밖의 새 연결로 구매 활동 한 행(상품 7)을 넣고 걸린 시간을 돌려준다. */
    private long timedInsert(long orderId, LocalDateTime confirmedAt) {
        long started = System.nanoTime();
        jdbc.update("""
            INSERT INTO exp_purchase_activity
              (created_at, updated_at, order_id, product_id, quantity, confirmed_at)
            VALUES (NOW(6), NOW(6), ?, 7, 1, ?)
            """, orderId, confirmedAt);
        return millisSince(started);
    }

    private void seed(int firstOrderId, int orders, LocalDateTime periodStart) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < orders; i++) {
            long orderId = firstOrderId + i;
            // 7일에 고르게 퍼뜨린다.
            LocalDateTime confirmedAt = periodStart.plusSeconds((long) i * 7 * 24 * 3600 / orders);
            rows.add(new Object[] {confirmedAt, confirmedAt, orderId, orderId % 1_000 + 1, 1, confirmedAt});
            rows.add(new Object[] {confirmedAt, confirmedAt, orderId, orderId % 1_000 + 1_001, 2, confirmedAt});
            if (rows.size() >= 5_000) {
                insertSeed(rows);
                rows.clear();
            }
        }
        insertSeed(rows);
    }

    private void insertSeed(List<Object[]> rows) {
        jdbc.batchUpdate("""
            INSERT INTO exp_purchase_activity
              (created_at, updated_at, order_id, product_id, quantity, confirmed_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, rows);
    }

    private TransactionTemplate transaction(int isolation) {
        TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        template.setIsolationLevel(isolation);
        return template;
    }

    private int sourceRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM exp_purchase_activity", Integer.class);
    }

    private int snapshotRows(String snapshotId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM exp_snapshot_input WHERE snapshot_id = ?", Integer.class, snapshotId);
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void log(String format, Object... args) {
        System.out.println("[freeze-exp] " + String.format(format, args));
    }
}
