package com.loopers.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSort;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 옵티마이저 계획 실험 (4주차, 기술 글 근거). 제품 코드가 아니며 결과는 docs/week4/optimizer-plan.md 에 정리한다.
 *
 * <ul>
 *   <li>같은 DB 에서 product 의 (brand_id, deleted_at) 인덱스를 떼었다 붙여, 인덱스 전후의 브랜드 + 좋아요순 계획을 비교한다.
 *       뗀 동안에는 외래 키를 위해 brand_id 단일 인덱스를 둔다 (처음 측정 때의 상태)</li>
 *   <li>브랜드 7(94개) · 101(1.8만 개, 좋아요 고르게) · 102(2,800개, 좋아요 0 · 1개뿐)를 잰다.
 *       102 는 필터와 정렬 키가 연관된 분포로, 좋아요 순서로 훑으면 다른 브랜드 상품 약 2.4만 개를 먼저 지나야 한다</li>
 *   <li>장면마다 실행 계획 · EXPLAIN ANALYZE · 옵티마이저 트레이스 전문(파일) · SQL 만 50회 실행한 지연을 남긴다.
 *       인덱스 후에는 옵티마이저가 고르지 않은 계획도 힌트로 강제해 실제 비용을 비교한다</li>
 *   <li>SQL 은 서비스 코드가 실제로 보내는 문장을 그대로 쓰고(SqlCapture), 목록 캐시를 거치지 않는다.
 *       계획이 달라도 결과 순서가 같은지 매번 확인한다</li>
 * </ul>
 * 실행: RUN_EXPERIMENT=true ./gradlew :apps:commerce-api:test --tests '*OptimizerPlanExperiment'
 */
@SpringBootTest(properties = {
    "spring.jpa.show-sql=false",
    "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.loopers.experiment.SqlCapture"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "RUN_EXPERIMENT", matches = "true")
class OptimizerPlanExperiment {

    private static final List<String> SEEDS = List.of(
        "seed/week4-product-seed.sql", "seed/week4-large-brand-seed.sql", "seed/week4-medium-brand-seed.sql");
    private static final Path REPORT = Path.of("build", "experiment", "optimizer-plan.md");
    private static final Path TRACE_DIR = Path.of("build", "experiment", "optimizer-trace");

    private static final String BRAND_DELETED_INDEX = "idx_product_brand_deleted";
    private static final String BRAND_ONLY_INDEX = "idx_product_brand";
    private static final int SIZE = 20;
    private static final int WARMUP_RUNS = 10;
    private static final int MEASURED_RUNS = 50;
    private static final List<String> PLAN_COLUMNS = List.of(
        "id", "select_type", "table", "type", "possible_keys", "key", "key_len", "ref", "rows", "filtered", "Extra");

    private enum IndexState { BEFORE, AFTER }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private ProductService productService;

    @Autowired
    private ObjectMapper objectMapper;

    /** 서비스 코드가 브랜드 + likes_desc 첫 페이지에 보내는 목록 SQL */
    private String listSql;

    /** 브랜드별로 처음 얻은 결과 순서. 계획이 바뀌어도 같아야 한다 */
    private final Map<Long, List<Long>> firstResults = new HashMap<>();

    @BeforeAll
    void seed() throws Exception {
        databaseCleanUp.truncateAllTables();
        try (Connection connection = dataSource.getConnection()) {
            for (String seed : SEEDS) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(seed));
            }
        }
        analyze();
        assertThat(single("select count(*) from product where brand_id = 102")).isEqualTo("3000");
        assertThat(single("select count(*) from product where brand_id = 102 and deleted_at is null")).isEqualTo("2823");
        assertThat(single("select sum(price) from product where brand_id = 102 and deleted_at is null")).isEqualTo("131837751");
        assertThat(single("select count(*) from product p left join product_like_count c on c.product_id = p.id "
            + "where c.id is null or c.like_count <> (select count(*) from likes l where l.product_id = p.id)")).isEqualTo("0");

        SqlCapture.clear();
        productService.getActiveProductViews(7L, ProductSort.LIKES_DESC, PageRequest.of(0, SIZE));
        listSql = SqlCapture.statements().get(0);

        Files.createDirectories(TRACE_DIR);
        Files.writeString(REPORT, String.join("\n",
            "# 옵티마이저 계획 실험 (브랜드 + likes_desc 첫 페이지)",
            "",
            "- MySQL " + single("select version()") + " (Testcontainers). 상품 33,000개(브랜드 7 · 101 · 102 포함)",
            "- SQL 만 JDBC 로 실행. 준비 " + WARMUP_RUNS + "회 뒤 " + MEASURED_RUNS + "회. 트레이스 전문: build/experiment/optimizer-trace/",
            "",
            "```sql",
            listSql,
            "```",
            "",
            ""
        ));
    }

    @AfterAll
    void cleanUp() throws SQLException {
        switchTo(IndexState.AFTER);
        databaseCleanUp.truncateAllTables();
    }

    @ParameterizedTest(name = "인덱스 전 · 브랜드 {0}")
    @ValueSource(longs = {7, 101, 102})
    @Order(1)
    void beforeIndex(long brandId) throws Exception {
        switchTo(IndexState.BEFORE);
        measure("인덱스 전", brandId, listSql);
    }

    @ParameterizedTest(name = "인덱스 후 · 브랜드 {0}")
    @ValueSource(longs = {7, 101, 102})
    @Order(2)
    void afterIndex(long brandId) throws Exception {
        switchTo(IndexState.AFTER);
        measure("인덱스 후", brandId, listSql);
    }

    /**
     * 인덱스 후 상태에서 두 조인 순서를 각각 힌트로 강제한다. 힌트가 의도한 테이블에서 출발하는지 실행 계획으로 확인한다.
     * 좋아요 순서: 좋아요 수 테이블부터 읽어 좋아요 수 인덱스 순서로 훑다가 20개에서 멈춘다.
     * 브랜드 먼저: 상품을 브랜드 인덱스로 먼저 찾아 좋아요 수와 조인한 뒤 정렬한다
     */
    @ParameterizedTest(name = "인덱스 후 · 브랜드 {0} · {1} 강제")
    @CsvSource({
        "7, 좋아요 순서", "7, 브랜드 먼저",
        "101, 좋아요 순서", "101, 브랜드 먼저",
        "102, 좋아요 순서", "102, 브랜드 먼저"
    })
    @Order(3)
    void forcedPlans(long brandId, String plan) throws Exception {
        switchTo(IndexState.AFTER);
        String joinOrder = switch (plan) {
            case "좋아요 순서" -> "plc1_0, p1_0";
            case "브랜드 먼저" -> "p1_0, plc1_0";
            default -> throw new IllegalArgumentException(plan);
        };
        String sql = listSql.replaceFirst("(?i)^select ", "select /*+ JOIN_ORDER(b1_0, " + joinOrder + ") */ ");
        assertThat(firstJoinedTable(sql, brandId)).isEqualTo(joinOrder.split(", ")[0]);
        measure("인덱스 후 · " + plan + " 강제", brandId, sql);
    }

    /** 실행 계획에서 상수(브랜드) 다음으로 읽는 테이블 */
    private String firstJoinedTable(String sql, long brandId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return rows(connection, "explain " + sql, brandId, 0L, SIZE).get(1).get(2).toString();
        }
    }

    private void measure(String label, long brandId, String sql) throws Exception {
        Object[] args = {brandId, 0L, SIZE};
        List<String> lines = new ArrayList<>();
        lines.add("## " + label + " · 브랜드 " + brandId);
        lines.add("");
        try (Connection connection = dataSource.getConnection()) {
            // 옵티마이저 트레이스: 같은 연결에서 켜고 실행한 뒤 읽는다
            execute(connection, "set optimizer_trace = 'enabled=on', optimizer_trace_max_mem_size = 8388608");
            List<Long> ids = run(connection, sql, args);
            String trace = single(connection, "select trace from information_schema.optimizer_trace");
            execute(connection, "set optimizer_trace = 'enabled=off'");
            Path traceFile = TRACE_DIR.resolve(fileName(label, brandId));
            Files.writeString(traceFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(objectMapper.readTree(trace)));

            firstResults.putIfAbsent(brandId, ids);
            assertThat(ids).isEqualTo(firstResults.get(brandId));

            long[] nanos = new long[MEASURED_RUNS];
            for (int i = 0; i < WARMUP_RUNS; i++) {
                run(connection, sql, args);
            }
            for (int i = 0; i < MEASURED_RUNS; i++) {
                long start = System.nanoTime();
                run(connection, sql, args);
                nanos[i] = System.nanoTime() - start;
            }
            Arrays.sort(nanos);

            lines.add("- 결과: 첫 실행과 같은 순서 " + ids.subList(0, Math.min(5, ids.size())) + " …");
            lines.add(String.format("- SQL 지연: p50 %.2fms, p95 %.2fms (%d회)", nanos[MEASURED_RUNS / 2] / 1e6,
                nanos[(int) Math.ceil(MEASURED_RUNS * 0.95) - 1] / 1e6, MEASURED_RUNS));
            lines.add("- 트레이스: `" + traceFile + "`");
            lines.add("");
            lines.add(planTable(connection, "explain " + sql, args));
            lines.add("");
            lines.add("```");
            lines.addAll(rows(connection, "explain analyze " + sql, args).stream().map(row -> String.valueOf(row.get(0))).toList());
            lines.add("```");
            lines.add("");
            lines.add("트레이스 발췌");
            lines.add("");
            lines.add("```json");
            lines.addAll(traceExcerpt(objectMapper.readTree(trace)));
            lines.add("```");
            lines.add("");
        }
        Files.writeString(REPORT, String.join("\n", lines) + "\n", StandardOpenOption.APPEND);
    }

    /** 행 수 추정 · 비교한 계획 · 정렬을 피하려고 접근 경로를 다시 고려한 단계만 뽑는다 */
    private List<String> traceExcerpt(JsonNode trace) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String key : List.of("rows_estimation", "considered_execution_plans", "reconsidering_access_paths_for_index_ordering")) {
            for (JsonNode node : trace.findValues(key)) {
                lines.add("// " + key);
                lines.add(objectMapper.writeValueAsString(node));
            }
        }
        return lines;
    }

    private void switchTo(IndexState state) throws SQLException {
        boolean hasBrandDeleted = hasIndex(BRAND_DELETED_INDEX);
        boolean hasBrandOnly = hasIndex(BRAND_ONLY_INDEX);
        try (Connection connection = dataSource.getConnection()) {
            if (state == IndexState.BEFORE && hasBrandDeleted) {
                if (!hasBrandOnly) {
                    execute(connection, "create index " + BRAND_ONLY_INDEX + " on product (brand_id)");
                }
                execute(connection, "alter table product drop index " + BRAND_DELETED_INDEX);
            }
            if (state == IndexState.AFTER && !hasBrandDeleted) {
                execute(connection, "create index " + BRAND_DELETED_INDEX + " on product (brand_id, deleted_at)");
                execute(connection, "alter table product drop index " + BRAND_ONLY_INDEX);
            }
        }
        analyze();
    }

    private boolean hasIndex(String name) throws SQLException {
        return !"0".equals(single("select count(*) from information_schema.statistics "
            + "where table_schema = database() and table_name = 'product' and index_name = '" + name + "'"));
    }

    private void analyze() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            rows(connection, "analyze table brand, product, likes, product_like_count");
        }
    }

    private static String fileName(String label, long brandId) {
        return (label + "-brand-" + brandId).replaceAll("[^0-9A-Za-z가-힣-]+", "-") + ".json";
    }

    private static List<Long> run(Connection connection, String sql, Object... args) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement statement = prepare(connection, sql, args); ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                ids.add(resultSet.getLong(1));
            }
        }
        return ids;
    }

    private static List<List<Object>> rows(Connection connection, String sql, Object... args) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        try (PreparedStatement statement = prepare(connection, sql, args); ResultSet resultSet = statement.executeQuery()) {
            int columns = resultSet.getMetaData().getColumnCount();
            while (resultSet.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    row.add(resultSet.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static String planTable(Connection connection, String sql, Object... args) throws SQLException {
        List<String> lines = new ArrayList<>();
        lines.add("| " + String.join(" | ", PLAN_COLUMNS) + " |");
        lines.add("|" + "---|".repeat(PLAN_COLUMNS.size()));
        try (PreparedStatement statement = prepare(connection, sql, args); ResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData meta = resultSet.getMetaData();
            while (resultSet.next()) {
                List<String> cells = new ArrayList<>();
                for (String column : PLAN_COLUMNS) {
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        if (meta.getColumnLabel(i).equalsIgnoreCase(column)) {
                            cells.add(String.valueOf(resultSet.getObject(i)));
                        }
                    }
                }
                lines.add("| " + String.join(" | ", cells) + " |");
            }
        }
        return String.join("\n", lines);
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... args) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
        return statement;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String single(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return single(connection, sql);
        }
    }

    private static String single(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }
}
