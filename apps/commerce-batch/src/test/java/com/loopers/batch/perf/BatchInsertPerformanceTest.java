package com.loopers.batch.perf;

import com.loopers.batch.domain.RankingSnapshotInput;
import com.querydsl.sql.Configuration;
import com.querydsl.sql.MySQLTemplates;
import com.querydsl.sql.SQLQueryFactory;
import com.querydsl.sql.dml.SQLInsertClause;
import com.querydsl.sql.spring.SpringConnectionProvider;
import com.querydsl.sql.spring.SpringExceptionTranslator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W9 batch insert 실험. 같은 행을 여러 방법으로 ranking_snapshot_input에 넣고, 시간과 MySQL이 받은 INSERT 문장 수를 잰다.
 * <pre>
 * ① jpa       JPA saveAll                          IDENTITY id라 Hibernate가 묶지 못한다 → 행마다 INSERT
 * ② jdbc-off  JDBC batch (JdbcBatchItemWriter), rewrite 끔   묶어서 보내지만 문장은 행마다
 * ③ jdbc-on   JDBC batch (JdbcBatchItemWriter), rewrite 켬   드라이버가 INSERT 한 문장(여러 VALUES)으로 다시 씀
 * ④ qd-off / qd-on   Querydsl-SQL addBatch, rewrite 끔 / 켬   ②·③과 같은 JDBC batch인지 확인
 * ⑤ qd-bulk   Querydsl-SQL batchToBulk, rewrite 끔   드라이버 대신 Querydsl이 여러 VALUES 한 문장을 만든다
 * 원인 확인용 변형:
 * ④' qd-on-quote   ④ rewrite 켬 + MySQLTemplates.builder().quote()   컬럼 이름마다 도는 정규식 검사(requiresQuotes)를 건너뛴다
 * ⑤' qd-bulk-100   ⑤를 100행씩 한 문장으로   값을 꺼내는 LinkedList.get이 O(n²)이면 행당 비용이 1/10로 준다
 * ③' jdbc-on-pause  ③ + chunk 사이 60ms 쉼(시간 밖)   ⑤처럼 MySQL이 문장 사이에 놀면 MySQL 처리 시간이 느는지 본다
 * </pre>
 * 모든 방법이 chunk 1,000행마다 트랜잭션 하나로 쓴다 (점수 계산 step과 같은 단위).
 * 행·엔티티는 chunk마다 만들고(만드는 시간은 재지 않는다), 쓰는 시간만 더한다. 그래서 1,000만 행도 메모리에 한꺼번에 들고 있지 않는다.
 * 방법마다 20,000행으로 먼저 데운 뒤(기록하지 않는다), 회차마다 방법들을 번갈아 잰다 — 오래 도는 동안 PC 상태가 바뀌어도 한 방법만 손해 보지 않게.
 * 시간 나누기: ②~⑤는 드라이버 호출(execute*)과 commit에 쓴 시간을 따로 재고, MySQL이 INSERT·COMMIT을 처리한 시간은 performance_schema에서 읽는다.
 * MySQL은 기본 설정 그대로 띄운다 (innodb_buffer_pool_size 128MB). 표가 이 크기를 넘는 100만 행부터 디스크 읽기가 섞인다 — 결과 파일에 설정을 함께 적는다.
 * 실행 (평소 test에서는 빠진다):
 * <pre>
 * docker run --rm -d --name w9-test-mysql -p 33309:3306 -e MYSQL_ROOT_PASSWORD=scratch -e MYSQL_DATABASE=loopers_test \
 *   -e MYSQL_USER=application -e MYSQL_PASSWORD=application mysql:8.4
 * docker exec w9-test-mysql mysql -uroot -pscratch -e "GRANT SELECT ON performance_schema.* TO 'application'@'%'"
 * DATASOURCE_MYSQLJPA_MAIN_JDBCURL=jdbc:mysql://localhost:33309/loopers_test \
 *   ./gradlew :apps:commerce-batch:perfRun -Pperf.sizes=1000000 -Pperf.reps=3
 * </pre>
 * 고르기: -Pperf.methods=jdbc-on,qd-on · CPU 샘플: -Pperf.jfr=build/perf/insert.jfr
 */
@Tag("perf")
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    // test 프로필은 show-sql이 켜져 있다. 켜 두면 ① saveAll만 행마다 SQL을 콘솔에 찍어 그 시간까지 잰다
    "spring.jpa.show-sql=false"
})
class BatchInsertPerformanceTest {

    private static final int CHUNK_SIZE = 1_000;
    private static final int WARMUP_ROWS = 20_000;
    private static final int PROGRESS_FROM_ROWS = 100_000;
    private static final int SECONDS_PER_WEEK = 7 * 24 * 60 * 60;
    private static final String SNAPSHOT_ID = "weekly-20260817-r1";
    private static final LocalDateTime WEEK_START = LocalDateTime.of(2026, 8, 17, 0, 0);
    private static final LocalDateTime FROZEN_AT = LocalDateTime.of(2026, 8, 24, 1, 0);
    private static final String INSERT_SQL = """
        INSERT INTO ranking_snapshot_input (snapshot_id, product_id, order_id, quantity, confirmed_at, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """;

    @Autowired
    private HikariDataSource dataSource;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private final List<HikariDataSource> rewriteDataSources = new ArrayList<>();
    private final JdbcClock clock = new JdbcClock();

    @AfterEach
    void closeDataSources() {
        rewriteDataSources.forEach(HikariDataSource::close);
    }

    @Test
    void 같은_행을_여러_방법으로_넣고_시간과_INSERT_문장_수를_기록한다() throws IOException {
        // given: rewrite는 방법마다 따로 만든 연결 설정으로 정한다. 공용 URL에 이미 붙어 있으면 비교가 섞인다
        assertThat(dataSource.getJdbcUrl()).doesNotContain("rewriteBatchedStatements");
        JdbcTemplate admin = new JdbcTemplate(dataSource);
        assertThat(ServerCounters.read(admin).insertPicos())
            .as("performance_schema를 읽을 수 있어야 한다 (Javadoc의 GRANT)")
            .isNotNegative();
        List<Integer> sizes = Arrays.stream(System.getProperty("perf.sizes", "1000000").split(","))
            .map(String::trim).map(Integer::parseInt).toList();
        int reps = Integer.parseInt(System.getProperty("perf.reps", "3"));
        Map<String, Supplier<InsertMethod>> available = new LinkedHashMap<>();
        available.put("jpa", this::jpaSaveAll);
        available.put("jdbc-off", () -> jdbcBatch(false));
        available.put("jdbc-on", () -> jdbcBatch(true));
        available.put("qd-off", () -> querydslSqlBatch(false, false));
        available.put("qd-on", () -> querydslSqlBatch(true, false));
        available.put("qd-bulk", () -> querydslSqlBulk(CHUNK_SIZE));
        available.put("qd-on-quote", () -> querydslSqlBatch(true, true));
        available.put("qd-bulk-100", () -> querydslSqlBulk(100));
        available.put("jdbc-on-pause", () -> jdbcBatchWithPause(60));
        List<String> keys = Arrays.stream(System.getProperty("perf.methods", String.join(",", available.keySet())).split(","))
            .map(String::trim).toList();
        assertThat(available.keySet()).containsAll(keys);
        List<InsertMethod> methods = keys.stream().map(key -> available.get(key).get()).toList();
        methods.forEach(method -> measure(admin, method, WARMUP_ROWS, 0));

        // when
        List<Measurement> measurements = new ArrayList<>();
        for (int size : sizes) {
            for (int rep = 1; rep <= reps; rep++) {
                for (InsertMethod method : methods) {
                    measurements.add(measure(admin, method, size, rep));
                }
            }
        }

        // then: 모든 실행이 요청한 행을 빠짐없이 넣었다. 시간과 문장 수는 기록으로 남긴다
        assertThat(measurements).allSatisfy(m -> assertThat(m.rowsInTable()).isEqualTo(m.size()));
        Path report = PerfReport.write(admin, dataSource, CHUNK_SIZE, sizes, reps, methods.stream().map(InsertMethod::name).toList(), measurements);
        System.out.println(Files.readString(report));
    }

    private Measurement measure(JdbcTemplate admin, InsertMethod method, int size, int rep) {
        admin.execute("TRUNCATE TABLE ranking_snapshot_input");
        ServerCounters before = ServerCounters.read(admin);
        clock.reset();
        long elapsedNanos = 0;
        int printedTenths = 0;
        for (int from = 0; from < size; from += CHUNK_SIZE) {
            List<?> chunk = method.prepare(rows(from, Math.min(CHUNK_SIZE, size - from)));
            long start = System.nanoTime();
            method.writeChunk(chunk);
            elapsedNanos += System.nanoTime() - start;

            int tenths = (int) ((long) (from + chunk.size()) * 10 / size);
            if (size >= PROGRESS_FROM_ROWS && tenths > printedTenths) {
                printedTenths = tenths;
                System.out.printf("[perf] %s · %,d행 · %d회 · %d%% · 쓰는 시간 %,.1f초%n", method.name(), size, rep, tenths * 10, elapsedNanos / 1e9);
            }
        }

        ServerCounters server = ServerCounters.read(admin).minus(before);
        Long rowsInTable = admin.queryForObject("SELECT COUNT(*) FROM ranking_snapshot_input", Long.class);
        double executeSeconds = method.clocked() ? clock.executeNanos / 1e9 : Double.NaN;
        double commitSeconds = method.clocked() ? clock.commitNanos / 1e9 : Double.NaN;
        return new Measurement(method.name(), size, rep, elapsedNanos / 1e9, executeSeconds, commitSeconds, server, rowsInTable == null ? 0 : rowsInTable);
    }

    // ① JPA saveAll. Spring Data가 주는 것과 같은 SimpleJpaRepository를 직접 만든다 (리포지토리 스캔 범위 밖이라서)
    private InsertMethod jpaSaveAll() {
        SimpleJpaRepository<RankingSnapshotInput, Long> repository = new SimpleJpaRepository<>(RankingSnapshotInput.class, entityManager);
        return new InsertMethod() {
            @Override
            public String name() {
                return "① JPA saveAll";
            }

            // 공용 DataSource를 쓰므로 드라이버 호출 시간은 재지 않는다 (MySQL 쪽 시간은 잰다)
            @Override
            public boolean clocked() {
                return false;
            }

            @Override
            public List<?> prepare(List<InputRow> rows) {
                return rows.stream().map(BatchInsertPerformanceTest::entityOf).toList();
            }

            @Override
            @SuppressWarnings("unchecked")
            public void writeChunk(List<?> chunk) {
                transactionTemplate.executeWithoutResult(status -> repository.saveAll((List<RankingSnapshotInput>) chunk));
            }
        };
    }

    // ②·③ 점수 계산 step의 writer와 같은 JdbcBatchItemWriter
    private InsertMethod jdbcBatch(boolean rewrite) {
        DataSource clockedDataSource = clock.wrap(dataSourceWithRewrite(rewrite));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(clockedDataSource));
        JdbcBatchItemWriter<InputRow> writer = new JdbcBatchItemWriterBuilder<InputRow>()
            .dataSource(clockedDataSource)
            .sql(INSERT_SQL)
            .itemPreparedStatementSetter(BatchInsertPerformanceTest::bind)
            .build();
        return new InsertMethod() {
            @Override
            public String name() {
                return rewrite ? "③ JDBC batch, rewrite 켬" : "② JDBC batch, rewrite 끔";
            }

            @Override
            @SuppressWarnings("unchecked")
            public void writeChunk(List<?> chunk) {
                tx.executeWithoutResult(status -> {
                    try {
                        writer.write(new Chunk<>((List<InputRow>) chunk));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
        };
    }

    // ③' ③과 같고, chunk 사이에 쉰다 (prepare는 시간 밖이라 쉬는 시간은 재지 않는다)
    private InsertMethod jdbcBatchWithPause(int pauseMillis) {
        InsertMethod jdbc = jdbcBatch(true);
        return new InsertMethod() {
            @Override
            public String name() {
                return String.format("③' JDBC batch, rewrite 켬 + chunk 사이 %dms 쉼", pauseMillis);
            }

            @Override
            public List<?> prepare(List<InputRow> rows) {
                try {
                    Thread.sleep(pauseMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return rows;
            }

            @Override
            public void writeChunk(List<?> chunk) {
                jdbc.writeChunk(chunk);
            }
        };
    }

    // ④ Querydsl-SQL addBatch. 행마다 JDBC addBatch를 부르고, 묶을지는 드라이버의 rewrite가 정한다
    private InsertMethod querydslSqlBatch(boolean rewrite, boolean quote) {
        DataSource clockedDataSource = clock.wrap(dataSourceWithRewrite(rewrite));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(clockedDataSource));
        SQLQueryFactory queryFactory = querydsl(clockedDataSource, quote);
        return new InsertMethod() {
            @Override
            public String name() {
                if (quote) {
                    return "④' Querydsl-SQL addBatch + quote(), rewrite 켬";
                }
                return rewrite ? "④ Querydsl-SQL addBatch, rewrite 켬" : "④ Querydsl-SQL addBatch, rewrite 끔";
            }

            @Override
            @SuppressWarnings("unchecked")
            public void writeChunk(List<?> chunk) {
                tx.executeWithoutResult(status -> addRows(queryFactory.insert(SRankingSnapshotInput.INPUT), (List<InputRow>) chunk).execute());
            }
        };
    }

    // ⑤ Querydsl-SQL batchToBulk. Querydsl이 VALUES (…),(…) 한 문장을 만들어 executeUpdate 한 번으로 보낸다 — rewrite가 필요 없다
    private InsertMethod querydslSqlBulk(int rowsPerStatement) {
        DataSource clockedDataSource = clock.wrap(dataSourceWithRewrite(false));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(clockedDataSource));
        SQLQueryFactory queryFactory = querydsl(clockedDataSource, false);
        return new InsertMethod() {
            @Override
            public String name() {
                return rowsPerStatement == CHUNK_SIZE
                    ? "⑤ Querydsl-SQL batchToBulk, rewrite 끔"
                    : String.format("⑤' Querydsl-SQL batchToBulk %,d행씩, rewrite 끔", rowsPerStatement);
            }

            @Override
            @SuppressWarnings("unchecked")
            public void writeChunk(List<?> chunk) {
                List<InputRow> rows = (List<InputRow>) chunk;
                tx.executeWithoutResult(status -> {
                    for (int from = 0; from < rows.size(); from += rowsPerStatement) {
                        SQLInsertClause insert = queryFactory.insert(SRankingSnapshotInput.INPUT);
                        insert.setBatchToBulk(true);
                        addRows(insert, rows.subList(from, Math.min(from + rowsPerStatement, rows.size()))).execute();
                    }
                });
            }
        };
    }

    // SpringConnectionProvider로 Spring 트랜잭션의 연결을 쓴다 — 없으면 chunk 실패 때 rollback되지 않는다
    // quote()면 이름을 늘 따옴표로 감싸, 이름마다 정규식으로 따옴표가 필요한지 검사하는 일(SQLTemplates.requiresQuotes)을 건너뛴다
    private static SQLQueryFactory querydsl(DataSource dataSource, boolean quote) {
        MySQLTemplates.Builder templates = MySQLTemplates.builder();
        if (quote) {
            templates.quote();
        }
        Configuration configuration = new Configuration(templates.build());
        configuration.setExceptionTranslator(new SpringExceptionTranslator());
        return new SQLQueryFactory(configuration, new SpringConnectionProvider(dataSource));
    }

    private static SQLInsertClause addRows(SQLInsertClause insert, List<InputRow> rows) {
        SRankingSnapshotInput input = SRankingSnapshotInput.INPUT;
        for (InputRow row : rows) {
            insert.columns(input.snapshotId, input.productId, input.orderId, input.quantity, input.confirmedAt, input.createdAt, input.updatedAt)
                .values(row.snapshotId(), row.productId(), row.orderId(), row.quantity(), row.confirmedAt(), row.createdAt(), row.createdAt())
                .addBatch();
        }
        return insert;
    }

    private HikariDataSource dataSourceWithRewrite(boolean rewrite) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dataSource.getJdbcUrl());
        config.setUsername(dataSource.getUsername());
        config.setPassword(dataSource.getPassword());
        config.setPoolName("perf-rewrite-" + rewrite + "-" + rewriteDataSources.size());
        config.setMaximumPoolSize(2);
        config.addDataSourceProperty("rewriteBatchedStatements", String.valueOf(rewrite));
        HikariDataSource created = new HikariDataSource(config);
        rewriteDataSources.add(created);
        return created;
    }

    // i번째 행. 상품 1,000개에 고르게 나뉘고, 주문 시각은 한 주 안에 머문다
    private static List<InputRow> rows(int from, int count) {
        return IntStream.range(from, from + count)
            .mapToObj(i -> new InputRow(SNAPSHOT_ID, i % 1_000 + 1, i + 1L, i % 5 + 1, WEEK_START.plusSeconds(i % SECONDS_PER_WEEK), FROZEN_AT))
            .toList();
    }

    // 엔티티는 테이블 정의용이라 값을 넣는 생성자가 없다. 실험에서만 리플렉션으로 채운다 (시간 밖)
    private static RankingSnapshotInput entityOf(InputRow row) {
        RankingSnapshotInput entity = BeanUtils.instantiateClass(RankingSnapshotInput.class);
        ReflectionTestUtils.setField(entity, "snapshotId", row.snapshotId());
        ReflectionTestUtils.setField(entity, "productId", row.productId());
        ReflectionTestUtils.setField(entity, "orderId", row.orderId());
        ReflectionTestUtils.setField(entity, "quantity", row.quantity());
        ReflectionTestUtils.setField(entity, "confirmedAt", ZonedDateTime.of(row.confirmedAt(), ZoneOffset.UTC));
        ReflectionTestUtils.setField(entity, "createdAt", ZonedDateTime.of(row.createdAt(), ZoneOffset.UTC));
        ReflectionTestUtils.setField(entity, "updatedAt", ZonedDateTime.of(row.createdAt(), ZoneOffset.UTC));
        return entity;
    }

    private static void bind(InputRow row, PreparedStatement ps) throws SQLException {
        ps.setString(1, row.snapshotId());
        ps.setLong(2, row.productId());
        ps.setLong(3, row.orderId());
        ps.setInt(4, row.quantity());
        ps.setObject(5, row.confirmedAt());
        ps.setObject(6, row.createdAt());
        ps.setObject(7, row.createdAt());
    }

    private interface InsertMethod {

        String name();

        /**
         * 드라이버 호출·commit 시간을 JdbcClock으로 재는가
         */
        default boolean clocked() {
            return true;
        }

        /**
         * 시간 밖에서 chunk마다 — 행을 이 방법이 쓰는 모양(엔티티 등)으로 바꾼다
         */
        default List<?> prepare(List<InputRow> rows) {
            return rows;
        }

        /**
         * 시간 안에서 chunk마다 — 트랜잭션 하나로 쓴다
         */
        void writeChunk(List<?> chunk);
    }

    /**
     * 우리가 만든 DataSource를 감싸, 드라이버 호출(PreparedStatement.execute*) 안과 Connection.commit 안에 머문 시간을 더한다.
     * execute 안 = 드라이버가 SQL을 만들고 보내고 MySQL 응답을 기다린 시간. 그 밖의 시간은 값 넣기와 라이브러리(Spring·Querydsl)가 쓴 시간이다.
     */
    private static final class JdbcClock {

        private long executeNanos;
        private long commitNanos;

        void reset() {
            executeNanos = 0;
            commitNanos = 0;
        }

        DataSource wrap(DataSource target) {
            return proxy(DataSource.class, (proxy, method, args) -> {
                Object result = call(target, method, args);
                return method.getName().equals("getConnection") ? connection((Connection) result) : result;
            });
        }

        private Connection connection(Connection target) {
            return proxy(Connection.class, (proxy, method, args) -> {
                if (method.getName().equals("commit")) {
                    long start = System.nanoTime();
                    try {
                        return call(target, method, args);
                    } finally {
                        commitNanos += System.nanoTime() - start;
                    }
                }
                Object result = call(target, method, args);
                return method.getName().equals("prepareStatement") ? statement((PreparedStatement) result) : result;
            });
        }

        private PreparedStatement statement(PreparedStatement target) {
            return proxy(PreparedStatement.class, (proxy, method, args) -> {
                if (!method.getName().startsWith("execute")) {
                    return call(target, method, args);
                }
                long start = System.nanoTime();
                try {
                    return call(target, method, args);
                } finally {
                    executeNanos += System.nanoTime() - start;
                }
            });
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
            return (T) Proxy.newProxyInstance(JdbcClock.class.getClassLoader(), new Class<?>[]{type}, handler);
        }

        private static Object call(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    /**
     * MySQL 쪽 누적값. 측정 전후 차이로 한 측정분을 얻는다.
     * insertPicos·commitPicos = MySQL이 INSERT·COMMIT 문장을 받아 끝낼 때까지 쓴 시간 합 (performance_schema, 피코초).
     */
    record ServerCounters(long inserts, long bytesReceived, long insertPicos, long commitPicos) {

        static ServerCounters read(JdbcTemplate admin) {
            return new ServerCounters(
                status(admin, "Com_insert"),
                status(admin, "Bytes_received"),
                statementPicos(admin, "statement/sql/insert"),
                statementPicos(admin, "statement/sql/commit")
            );
        }

        ServerCounters minus(ServerCounters other) {
            return new ServerCounters(inserts - other.inserts, bytesReceived - other.bytesReceived,
                insertPicos - other.insertPicos, commitPicos - other.commitPicos);
        }

        private static long status(JdbcTemplate admin, String name) {
            Long value = admin.queryForObject("SHOW GLOBAL STATUS LIKE '" + name + "'", (rs, rowNum) -> rs.getLong("Value"));
            return value == null ? 0 : value;
        }

        private static long statementPicos(JdbcTemplate admin, String eventName) {
            Long value = admin.queryForObject(
                "SELECT SUM_TIMER_WAIT FROM performance_schema.events_statements_summary_global_by_event_name WHERE EVENT_NAME = ?",
                Long.class, eventName);
            return value == null ? 0 : value;
        }
    }

    record InputRow(String snapshotId, long productId, long orderId, int quantity, LocalDateTime confirmedAt, LocalDateTime createdAt) {
    }

    record Measurement(String method, int size, int rep, double seconds, double executeSeconds, double commitSeconds,
                       ServerCounters server, long rowsInTable) {

        double outsideDriverSeconds() {
            return seconds - executeSeconds - commitSeconds;
        }

        double serverInsertSeconds() {
            return server.insertPicos() / 1e12;
        }

        double serverCommitSeconds() {
            return server.commitPicos() / 1e12;
        }
    }

    /**
     * 결과 파일. 환경(버전·CPU·MySQL 설정) → 평균 시간 → 초당 넣은 행 → INSERT 문장 수 → 시간 나누기 → 회차별 원본.
     */
    private static final class PerfReport {

        private PerfReport() {
        }

        static Path write(JdbcTemplate admin, HikariDataSource dataSource, int chunkSize, List<Integer> sizes, int reps,
                          List<String> methods, List<Measurement> measurements) throws IOException {
            String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            StringBuilder out = new StringBuilder();
            out.append("# batch insert 측정 ").append(now).append("\n\n");
            out.append("| 항목 | 값 |\n|---|---|\n");
            out.append("| MySQL | ").append(admin.queryForObject("SELECT VERSION()", String.class)).append(" |\n");
            out.append("| MySQL 설정 | ").append(admin.queryForObject("""
                SELECT CONCAT('innodb_buffer_pool_size ', @@innodb_buffer_pool_size DIV 1048576, 'MB · log_bin ', IF(@@log_bin, 'ON', 'OFF'),
                              ' · sync_binlog ', @@sync_binlog, ' · innodb_flush_log_at_trx_commit ', @@innodb_flush_log_at_trx_commit)
                """, String.class)).append(" |\n");
            out.append("| Connector/J | ").append(driverVersion(dataSource)).append(" |\n");
            out.append("| JDK | ").append(Runtime.version()).append(" |\n");
            out.append("| OS · CPU | ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.arch"))
                .append(" · ").append(Runtime.getRuntime().availableProcessors()).append(" cores |\n");
            out.append("| chunk (트랜잭션 하나에 쓰는 행) | ").append(chunkSize).append(" |\n");
            out.append("| 반복 | 크기마다 ").append(reps).append("회 평균. 방법마다 ").append(String.format("%,d", WARMUP_ROWS))
                .append("행으로 먼저 데우고, 회차마다 방법들을 번갈아 잼 |\n\n");

            out.append("## 평균 시간 (초)\n\n");
            appendTable(out, sizes, methods, (method, size) -> String.format("%,.2f", average(measurements, method, size, Measurement::seconds)));
            out.append("\n## 초당 넣은 행\n\n");
            appendTable(out, sizes, methods, (method, size) -> String.format("%,.0f", size / average(measurements, method, size, Measurement::seconds)));
            out.append("\n## MySQL이 받은 INSERT 문장 수 (Com_insert, 마지막 회)\n\n");
            appendTable(out, sizes, methods, (method, size) -> String.format("%,d", last(measurements, method, size).server().inserts()));

            for (int size : sizes) {
                out.append(String.format("%n## 시간 나누기 · %,d행 (초, 평균)%n%n", size));
                out.append("""
                    전체 = 드라이버 밖(값 넣기·Spring·Querydsl·Hibernate) + execute 안(드라이버가 SQL을 만들고 보내고 기다림) + commit 안.
                    MySQL 칸은 MySQL이 문장을 받아 끝낼 때까지 쓴 시간이다. execute 안 − MySQL INSERT = 드라이버가 SQL 만들기 + 왕복.

                    | 방법 | 전체 | 드라이버 밖 | execute 안 | commit 안 | MySQL INSERT | MySQL COMMIT | MySQL이 받은 MB |
                    |---|---:|---:|---:|---:|---:|---:|---:|
                    """);
                for (String method : methods) {
                    out.append(String.format("| %s | %s | %s | %s | %s | %s | %s | %,.1f |%n", method,
                        seconds(average(measurements, method, size, Measurement::seconds)),
                        seconds(average(measurements, method, size, Measurement::outsideDriverSeconds)),
                        seconds(average(measurements, method, size, Measurement::executeSeconds)),
                        seconds(average(measurements, method, size, Measurement::commitSeconds)),
                        seconds(average(measurements, method, size, Measurement::serverInsertSeconds)),
                        seconds(average(measurements, method, size, Measurement::serverCommitSeconds)),
                        average(measurements, method, size, m -> m.server().bytesReceived()) / 1_048_576));
                }
            }

            out.append("""

                ## 회차별 원본

                | 방법 | 행 | 회차 | 초 | execute 안 | commit 안 | MySQL INSERT | MySQL COMMIT | INSERT 문장 | 받은 바이트 | 넣은 행 |
                |---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
                """);
            for (Measurement m : measurements) {
                out.append(String.format("| %s | %,d | %d | %,.2f | %s | %s | %,.2f | %,.2f | %,d | %,d | %,d |%n", m.method(), m.size(), m.rep(), m.seconds(),
                    seconds(m.executeSeconds()), seconds(m.commitSeconds()), m.serverInsertSeconds(), m.serverCommitSeconds(),
                    m.server().inserts(), m.server().bytesReceived(), m.rowsInTable()));
            }

            Path dir = Path.of(System.getProperty("perf.out", "build/perf"));
            Files.createDirectories(dir);
            Path file = dir.resolve("batch-insert-" + now + ".md");
            Files.writeString(file, out.toString());
            return file;
        }

        private static void appendTable(StringBuilder out, List<Integer> sizes, List<String> methods, Cell cell) {
            out.append("| 방법 |");
            sizes.forEach(size -> out.append(String.format(" %,d행 |", size)));
            out.append("\n|---|").append("---:|".repeat(sizes.size())).append('\n');
            for (String method : methods) {
                out.append("| ").append(method).append(" |");
                sizes.forEach(size -> out.append(' ').append(cell.value(method, size)).append(" |"));
                out.append('\n');
            }
        }

        // ① JPA처럼 재지 않은 칸은 NaN이라 "—"로 적는다
        private static String seconds(double value) {
            return Double.isNaN(value) ? "—" : String.format("%,.2f", value);
        }

        private static double average(List<Measurement> measurements, String method, int size, ToDoubleFunction<Measurement> value) {
            return measurements.stream().filter(m -> m.method().equals(method) && m.size() == size)
                .mapToDouble(value).average().orElse(Double.NaN);
        }

        private static Measurement last(List<Measurement> measurements, String method, int size) {
            return measurements.stream().filter(m -> m.method().equals(method) && m.size() == size)
                .reduce((first, second) -> second).orElseThrow();
        }

        private static String driverVersion(HikariDataSource dataSource) {
            try (Connection connection = dataSource.getConnection()) {
                return connection.getMetaData().getDriverVersion();
            } catch (SQLException e) {
                return "알 수 없음";
            }
        }

        @FunctionalInterface
        private interface Cell {
            String value(String method, int size);
        }
    }
}
