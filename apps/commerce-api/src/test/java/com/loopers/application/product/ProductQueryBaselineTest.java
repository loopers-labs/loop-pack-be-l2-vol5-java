package com.loopers.application.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.CommerceApiApplication;
import com.loopers.support.JdbcReadProbe;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.autoconfigure.filter.TypeExcludeFilters;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/** 기존 API와 수동 DDL을 사용하는 캐시 없는 W4 전후 비교 측정이다. */
@SpringBootTest(classes = CommerceApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.jpa.show-sql=false",
        "commerce.product-list-cache.enabled=false"})
@TypeExcludeFilters(ProductQueryBaselineTest.ContainerExclusion.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductQueryBaselineTest {
    private static final String SEED = "fixtures/week4/product-query-baseline.sql";
    private static final String REQUEST = "/api/v1/products?brandId=1007&sort=price_asc&page=0&size=20";
    private static final int WARMUPS = 5;
    private static final int SAMPLES = 20;
    private static final int READ_PATH_WARMUPS = 10;
    private static final int READ_PATH_SAMPLES = 50;
    private static final String INDEX_ADDITION = "007-product-brand-price-index.sql";
    private static final boolean COMPARE_INDEX = Boolean.parseBoolean(System.getenv("W4_COMPARE_BRAND_PRICE_INDEX"));
    private static final List<String> SCHEMA = List.of("001-brand.sql", "002-user.sql", "003-product.sql",
        "004-like.sql", "005-order.sql", "006-order-item.sql");

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w4_product_baseline")
        .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci")
        .withStartupTimeout(Duration.ofMinutes(2));
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4"))
        .withExposedPorts(6379)
        .withStartupTimeout(Duration.ofMinutes(2));
    private static boolean schemaApplied;

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ApplicationContext context;
    @MockitoSpyBean(name = "mySqlMainDataSource") private HikariDataSource dataSource;

    private final JdbcReadProbe probe = new JdbcReadProbe();

    @DynamicPropertySource
    static synchronized void ownInfrastructure(DynamicPropertyRegistry properties) {
        // PER_CLASS 컨텍스트 생성이 Testcontainers beforeAll보다 빨라도 스키마를 먼저 준비한다.
        MYSQL.start();
        REDIS.start();
        if (!schemaApplied) {
            ResourceDatabasePopulator schema = new ResourceDatabasePopulator();
            SCHEMA.forEach(file -> schema.addScript(new ClassPathResource("db/schema/" + file)));
            if (!COMPARE_INDEX) {
                schema.addScript(new ClassPathResource("db/schema/" + INDEX_ADDITION));
            }
            schema.execute(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
            schemaApplied = true;
        }
        properties.add("datasource.mysql-jpa.main.jdbc-url", MYSQL::getJdbcUrl);
        properties.add("datasource.mysql-jpa.main.username", MYSQL::getUsername);
        properties.add("datasource.mysql-jpa.main.password", MYSQL::getPassword);
        properties.add("datasource.redis.database", () -> 0);
        properties.add("datasource.redis.master.host", REDIS::getHost);
        properties.add("datasource.redis.master.port", REDIS::getFirstMappedPort);
        properties.add("datasource.redis.replicas[0].host", REDIS::getHost);
        properties.add("datasource.redis.replicas[0].port", REDIS::getFirstMappedPort);
    }

    @BeforeAll
    void seedOwnedDatabase() {
        new ResourceDatabasePopulator(new ClassPathResource(SEED)).execute(dataSource);
        jdbc.setQueryTimeout(30);
        // 빈 테이블 통계로 개선 효과를 과장하지 않도록, 전후 측정 모두 같은 준비를 한다.
        assertThat(jdbc.queryForList("ANALYZE TABLE brand, product, `like`, `user`"))
            .allSatisfy(row -> assertThat(row.get("Msg_text")).isEqualTo("OK"));
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(5000);
        requests.setReadTimeout(15000);
        http.getRestTemplate().setRequestFactory(requests);
    }

    @BeforeEach
    void installReadProbeAfterMockitoReset() throws SQLException {
        doAnswer(invocation -> probe.wrap((Connection) invocation.callRealMethod()))
            .when(dataSource).getConnection();
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void measuresExistingHttpQueryAgainstManualIndexesAndDeterministicTenThousandProducts() throws IOException {
        assertOwnedSchemaAndSeed();
        assertExistingContracts();
        String label = System.getenv().getOrDefault("W4_QUERY_LABEL", "current");
        assertThat(label).matches("[a-z0-9-]+");
        if (COMPARE_INDEX) {
            // 이 테스트가 소유한 빈 DB에만 후보 인덱스를 추가/제거한다. 공유 DB에는 연결하지 않는다.
            for (int round = 1; round <= 2; round++) {
                measure(label + "-existing-r" + round);
                new ResourceDatabasePopulator(new ClassPathResource("db/schema/" + INDEX_ADDITION)).execute(dataSource);
                try {
                    analyzeProducts();
                    measure(label + "-candidate-r" + round);
                } finally {
                    jdbc.execute("DROP INDEX idx_product_brand_price ON product");
                    analyzeProducts();
                }
            }
        } else {
            measure(label);
            compareListAndDetailReadCosts();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"latest", "price_asc"})
    void limitsGlobalLikeAggregationToTheSelectedPage(String sort) {
        for (int size : List.of(1, 20, 100)) {
            for (int page : List.of(0, 1)) {
                JsonNode expected = expectedPage(null, sort, page, size);
                List<JdbcReadProbe.Query> queries = observePage(sort, page, size, expected);
                // 선택한 구현의 비용 경계다. 상품 개수에 비례하는 SELECT나 전체 좋아요 집계를 허용하지 않는다.
                assertThat(queries).as("count + 좋아요 없는 page + 선택 ID의 일괄 집계").hasSize(3);
                assertThat(queries.get(0).sql()).doesNotContain("`like`");
                assertThat(queries.get(1).sql()).doesNotContain("`like`").contains(" limit ");
                JdbcReadProbe.Query aggregate = queries.get(2);
                assertThat(aggregate.sql()).contains("from `like`", " in (", " group by ");
                List<Long> selectedIds = new ArrayList<>();
                expected.path("items").forEach(item -> selectedIds.add(item.path("productId").asLong()));
                assertThat(aggregate.parameters()).extracting(value -> ((Number) value).longValue())
                    .containsExactlyInAnyOrderElementsOf(selectedIds);
                assertThat(selectedIds).hasSize(size).doesNotHaveDuplicates();
                queries.forEach(this::assertBindingsCaptured);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"latest", "price_asc"})
    void skipsLikeAggregationBeyondTheLastGlobalPage(String sort) {
        for (int page : List.of(491, Integer.MAX_VALUE)) {
            JsonNode expected = expectedPage(null, sort, page, 20);
            assertThat(expected.path("items")).isEmpty();
            assertThat(expected.path("totalElements").asInt()).isEqualTo(9812);
            List<JdbcReadProbe.Query> queries = observePage(sort, page, 20, expected);
            assertThat(queries).hasSize(1);
            assertThat(queries.getFirst().sql()).contains("count(").doesNotContain("`like`");
        }
    }

    private List<JdbcReadProbe.Query> observePage(String sort, int page, int size, JsonNode expected) {
        probe.start();
        ResponseEntity<JsonNode> response;
        List<JdbcReadProbe.Query> queries;
        try {
            response = http.getForEntity("/api/v1/products?sort=" + sort + "&page=" + page + "&size=" + size,
                JsonNode.class);
        } finally {
            queries = probe.stop();
        }
        assertResponse(response, expected);
        return queries;
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20, 100})
    void ranksUsingOnlyProductIdsAndCountsThenLoadsOnlyTheSelectedDetails(int size) {
        for (int page : List.of(0, 1, (9812 - 1) / size)) {
            JsonNode expected = expectedPage(null, "likes_desc", page, size);
            List<JdbcReadProbe.Query> queries = observePage("likes_desc", page, size, expected);
            assertThat(queries).as("count + 전체 후보의 좁은 순위 집계 + 선정 ID 상세").hasSize(3);
            assertThat(queries.getFirst().sql()).startsWith("select count(").doesNotContain("`like`");
            JdbcReadProbe.Query ranking = queries.get(1);
            String projection = ranking.sql().substring(0, ranking.sql().indexOf(" from "));
            assertThat(projection).matches("select \\w+\\.id,count\\(\\w+\\.id\\)");
            String group = ranking.sql().split(" group by ")[1].split(" order by ")[0];
            assertThat(group).matches("\\w+\\.id");
            assertThat(ranking.sql()).contains("left join `like`", "deleted_at is null", "order by count(", " limit ");
            JdbcReadProbe.Query details = queries.get(2);
            assertThat(details.sql()).contains(" in (", "deleted_at is null")
                .doesNotContain("`like`", " group by ", " limit ");
            List<Long> selectedIds = new ArrayList<>();
            expected.path("items").forEach(item -> selectedIds.add(item.path("productId").asLong()));
            assertThat(details.parameters()).extracting(value -> ((Number) value).longValue())
                .containsExactlyInAnyOrderElementsOf(selectedIds);
            assertThat(selectedIds).doesNotHaveDuplicates();
            queries.forEach(this::assertBindingsCaptured);
        }
    }

    @Test
    void skipsRankingAndDetailsForOutOfRangeLikesPages() {
        for (int page : List.of(491, Integer.MAX_VALUE)) {
            JsonNode expected = expectedPage(null, "likes_desc", page, 20);
            assertThat(expected.path("items")).isEmpty();
            assertThat(expected.path("totalElements").asInt()).isEqualTo(9812);
            var queries = observePage("likes_desc", page, 20, expected);
            assertThat(queries).hasSize(1);
            assertThat(queries.getFirst().sql()).startsWith("select count(").doesNotContain("`like`");
        }
    }

    private void compareListAndDetailReadCosts() throws IOException {
        List<ReadCase> cases = new ArrayList<>();
        for (String sort : List.of("latest", "price_asc", "likes_desc")) {
            cases.add(new ReadCase("brand-" + sort,
                "/api/v1/products?brandId=1007&sort=" + sort + "&page=0&size=20",
                expectedPage(1007, sort, 0, 20)));
            cases.add(new ReadCase("all-" + sort,
                "/api/v1/products?sort=" + sort + "&page=0&size=20", expectedPage(null, sort, 0, 20)));
        }
        cases.add(new ReadCase("detail-zero-likes", "/api/v1/products/100010", expectedProduct(10)));
        cases.add(new ReadCase("detail-200-likes", "/api/v1/products/100997", expectedProduct(997)));
        assertThat(expectedProduct(10).path("likeCount").asInt()).isZero();
        assertThat(expectedProduct(997).path("likeCount").asInt()).isEqualTo(200);
        for (int warmup = 0; warmup < READ_PATH_WARMUPS; warmup++) {
            for (ReadCase candidate : cases) {
                assertResponse(http.getForEntity(candidate.request(), JsonNode.class), candidate.expected());
            }
        }

        List<Map<String, Object>> rounds = new ArrayList<>();
        Map<String, List<JdbcReadProbe.Query>> representativeQueries = new LinkedHashMap<>();
        for (int round = 1; round <= 2; round++) {
            Map<String, ReadSamples> measurements = new LinkedHashMap<>();
            cases.forEach(candidate -> measurements.put(candidate.label(), new ReadSamples()));
            for (int sample = 0; sample < READ_PATH_SAMPLES; sample++) {
                // 매 회차 시작 후보를 회전하고 2라운드는 순서를 뒤집는다. 동시 부하 실험은 아니다.
                for (int position = 0; position < cases.size(); position++) {
                    int offset = round == 1 ? position : cases.size() - 1 - position;
                    ReadCase candidate = cases.get((sample + offset) % cases.size());
                    ReadSamples samples = measurements.get(candidate.label());
                    probe.start();
                    ResponseEntity<JsonNode> response;
                    List<JdbcReadProbe.Query> queries;
                    long elapsed;
                    try {
                        long started = System.nanoTime();
                        response = http.getForEntity(candidate.request(), JsonNode.class);
                        elapsed = System.nanoTime() - started;
                    } finally {
                        queries = probe.stop();
                    }
                    assertResponse(response, candidate.expected());
                    assertThat(queries).as(candidate.label() + " 실제 SELECT 관찰").isNotEmpty();
                    queries.forEach(this::assertBindingsCaptured);
                    samples.httpNanos.add(elapsed);
                    samples.sqlCounts.add(queries.size());
                    samples.jdbcExecuteNanos.add(queries.stream().mapToLong(JdbcReadProbe.Query::executeNanos).sum());
                    representativeQueries.putIfAbsent(candidate.label(), queries);
                }
            }
            List<Map<String, Object>> results = new ArrayList<>();
            for (ReadCase candidate : cases) {
                ReadSamples samples = measurements.get(candidate.label());
                assertThat(samples.httpNanos).hasSize(READ_PATH_SAMPLES);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("case", candidate.label());
                result.put("request", candidate.request());
                result.put("rawHttpNanos", samples.httpNanos);
                result.put("httpLatencyMillis", latency(samples.httpNanos));
                result.put("rawJdbcExecuteSumNanos", samples.jdbcExecuteNanos);
                result.put("jdbcExecuteSumMillis", latency(samples.jdbcExecuteNanos));
                result.put("sqlCountsPerRequest", samples.sqlCounts);
                results.add(result);
            }
            rounds.add(Map.of("round", round, "results", results));
        }

        // 모든 조건의 HTTP 측정을 마친 뒤 계획을 수집한다. EXPLAIN이 다음 조건을 예열하지 않는다.
        List<Map<String, Object>> plans = new ArrayList<>();
        for (ReadCase candidate : cases) {
            List<Map<String, Object>> queries = new ArrayList<>();
            for (JdbcReadProbe.Query query : representativeQueries.get(candidate.label())) {
                queries.add(Map.of("sql", query.sql(), "parameters", query.parameters(),
                    "explain", jdbc.queryForList("EXPLAIN " + query.sql(), query.parameters().toArray()),
                    "explainAnalyze", jdbc.queryForList("EXPLAIN ANALYZE " + query.sql(), query.parameters().toArray())));
            }
            plans.add(Map.of("case", candidate.label(), "expectedAndVerifiedData", candidate.expected(),
                "queries", queries));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("measuredAt", Instant.now().toString());
        report.put("stage", "Current DB-only list/detail HTTP read cost comparison; no cache implementation");
        report.put("sourceSha256", sha256(Path.of("src/main/java/com/loopers/infrastructure/product/ProductQueryRepositoryImpl.java")));
        report.put("harnessSha256", sha256(Path.of("src/test/java/com/loopers/application/product/ProductQueryBaselineTest.java")));
        report.put("seedSha256", sha256(Path.of("src/test/resources", SEED)));
        report.put("seed", SEED + " (v1); 10000 products, 9812 visible, 46955 likes, 100 brands");
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("mysqlVersion", jdbc.queryForObject("SELECT VERSION()", String.class));
        report.put("sessionIsolation", jdbc.queryForObject("SELECT @@SESSION.transaction_isolation", String.class));
        List<String> scripts = new ArrayList<>(SCHEMA);
        scripts.add(INDEX_ADDITION);
        report.put("schemaScripts", scripts);
        report.put("warmupsPerCase", READ_PATH_WARMUPS);
        report.put("samplesPerCasePerRound", READ_PATH_SAMPLES);
        report.put("preparation", "Owned containers; seed; ANALYZE; contracts; fixed baseline; then 10 warmup cycles for all cases");
        report.put("caseOrder", cases.stream().map(ReadCase::label).toList());
        report.put("schedule", "Each sample cycle visits all 8 cases once; index=(sample+position)%8 in round 1, (sample+7-position)%8 in round 2");
        report.put("rounds", rounds);
        report.put("plansAfterAllTiming", plans);
        report.put("physicalIndexes", jdbc.queryForList("""
            SELECT TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME, COLLATION
            FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
            ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX
            """));
        report.put("limitations", List.of("Local sequential warm requests; no concurrent writers, cold disk, throughput or real traffic distribution",
            "List returns 20 products and page totals; detail returns one product. These are different operations, not equal payload benchmarks",
            "HTTP includes local client, JSON decoding and probe overhead; assertions and EXPLAIN are outside timing",
            "JDBC execute sum excludes subsequent ResultSet consumption and is not complete DB time",
            "Two rounds in one JVM and database; nearest-rank percentiles; no fixed speed assertion",
            "Cache hit ratio, Redis latency, invalidation and consistency protection costs have not been measured"));
        Path destination = Path.of("build", "reports", "week4", "product-read-cost-comparison.json");
        Files.createDirectories(destination.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(destination.toFile(), report);
    }

    private static Map<String, Double> latency(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        return Map.of("min", sorted.getFirst() / 1_000_000.0, "p50", percentile(sorted, 0.50) / 1_000_000.0,
            "p95", percentile(sorted, 0.95) / 1_000_000.0, "max", sorted.getLast() / 1_000_000.0);
    }

    private record ReadCase(String label, String request, JsonNode expected) { }

    private static final class ReadSamples {
        private final List<Long> httpNanos = new ArrayList<>();
        private final List<Integer> sqlCounts = new ArrayList<>();
        private final List<Long> jdbcExecuteNanos = new ArrayList<>();
    }

    private void analyzeProducts() {
        assertThat(jdbc.queryForList("ANALYZE TABLE product"))
            .allSatisfy(row -> assertThat(row.get("Msg_text")).isEqualTo("OK"));
    }

    private void measure(String label) throws IOException {
        JsonNode expected = expectedPage(1007, "price_asc", 0, 20);
        assertThat(expected.path("items").findValuesAsText("productId")).containsExactly(
            "109407", "108407", "107407", "106407", "105407", "103407", "102407", "101407", "100407",
            "109707", "108707", "107707", "106707", "105707", "104707", "103707", "102707", "101707", "100707", "109007");
        assertThat(expected.path("totalElements").asInt()).isEqualTo(99);
        assertThat(expected.path("totalPages").asInt()).isEqualTo(5);
        for (int index = 0; index < WARMUPS; index++) {
            assertResponse(http.getForEntity(REQUEST, JsonNode.class), expected);
        }

        List<Long> elapsedNanos = new ArrayList<>();
        List<Integer> sqlCounts = new ArrayList<>();
        List<JdbcReadProbe.Query> representativeQueries = List.of();
        JsonNode responseBody = null;
        for (int index = 0; index < SAMPLES; index++) {
            probe.start();
            ResponseEntity<JsonNode> response;
            List<JdbcReadProbe.Query> queries;
            long elapsed;
            try {
                long started = System.nanoTime();
                response = http.getForEntity(REQUEST, JsonNode.class);
                elapsed = System.nanoTime() - started;
            } finally {
                queries = probe.stop();
            }
            assertResponse(response, expected);
            assertThat(queries).as("실제 HTTP 요청의 실행 SQL이 관찰되어야 한다").isNotEmpty();
            queries.forEach(this::assertBindingsCaptured);
            elapsedNanos.add(elapsed);
            sqlCounts.add(queries.size());
            if (index == 0) {
                representativeQueries = queries;
                responseBody = response.getBody();
            }
        }

        // EXPLAIN ANALYZE 자체도 SELECT를 실행하므로 HTTP 반복 측정이 끝난 뒤, probe를 끄고 수집한다.
        List<Map<String, Object>> plans = new ArrayList<>();
        for (JdbcReadProbe.Query query : representativeQueries) {
            plans.add(Map.of("sql", query.sql(), "parameters", query.parameters(),
                "jdbcExecuteNanos", query.executeNanos(),
                "explain", jdbc.queryForList("EXPLAIN " + query.sql(), query.parameters().toArray()),
                "explainAnalyze", jdbc.queryForList("EXPLAIN ANALYZE " + query.sql(), query.parameters().toArray())));
        }
        writeReport(label, elapsedNanos, sqlCounts, responseBody, plans);
    }

    private void assertOwnedSchemaAndSeed() {
        assertThat(context.containsBean("mySqlTestContainersConfig")).isFalse();
        assertThat(context.containsBean("redisTestContainersConfig")).isFalse();
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("w4_product_baseline");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Integer.class)).isEqualTo(10000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand", Integer.class)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `like`", Integer.class)).isEqualTo(46955);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE id BETWEEN 10001 AND 10200", Integer.class))
            .isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE deleted_at IS NULL", Integer.class))
            .isEqualTo(9812);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE deleted_at IS NOT NULL", Integer.class))
            .isEqualTo(188);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE stock_quantity = 0", Integer.class))
            .isEqualTo(588);
        assertThat(jdbc.queryForObject("SELECT SUM(price) FROM product", Long.class)).isEqualTo(509500000);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM product p LEFT JOIN `like` l ON l.product_id = p.id WHERE l.id IS NULL
            """, Integer.class)).isEqualTo(999);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM (SELECT product_id FROM `like` GROUP BY product_id HAVING COUNT(*) = 200) popular
            """, Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE id IN (1,2,3) AND point_balance = 0", Integer.class))
            .isEqualTo(3);
        assertIndex("product", "idx_product_brand_deleted", "brand_id", "deleted_at");
        assertIndex("product", "idx_product_latest", "deleted_at", "created_at", "id");
        assertIndex("product", "idx_product_price", "deleted_at", "price", "id");
        if (!COMPARE_INDEX) {
            assertIndex("product", "idx_product_brand_price", "brand_id", "deleted_at", "price", "id");
        }
        assertIndex("like", "idx_like_product", "product_id");
    }

    private void assertIndex(String table, String name, String... columns) {
        assertThat(jdbc.queryForList("""
            SELECT COLUMN_NAME FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ? ORDER BY SEQ_IN_INDEX
            """, String.class, table, name)).containsExactly(columns);
    }

    private void assertExistingContracts() {
        for (String sort : List.of("latest", "price_asc", "likes_desc")) {
            for (int page : List.of(0, 1, 2, 3, 4, 5)) {
                String path = "/api/v1/products?brandId=1007&sort=" + sort + "&page=" + page + "&size=20";
                assertResponse(http.getForEntity(path, JsonNode.class), expectedPage(1007, sort, page, 20));
            }
            assertResponse(http.getForEntity("/api/v1/products?sort=" + sort, JsonNode.class),
                expectedPage(null, sort, 0, 20));
        }
        // 요청당 SQL 수가 페이지 품목 수에 비례해 늘지 않는지 검사한다. 특정 개수나 시간은 강제하지 않는다.
        List<Integer> queryCounts = new ArrayList<>();
        for (int size : List.of(1, 20, 100)) {
            probe.start();
            List<JdbcReadProbe.Query> queries;
            try {
                String path = "/api/v1/products?brandId=1007&sort=price_asc&page=0&size=" + size;
                assertResponse(http.getForEntity(path, JsonNode.class), expectedPage(1007, "price_asc", 0, size));
            } finally {
                queries = probe.stop();
            }
            assertThat(queries).isNotEmpty();
            queryCounts.add(queries.size());
        }
        assertThat(queryCounts).containsOnly(queryCounts.getFirst());
        assertResponse(http.getForEntity("/api/v1/products?brandId=1100&sort=price_asc", JsonNode.class),
            expectedPage(1100, "price_asc", 0, 20));
        for (long hiddenId : List.of(100100L, 100113L)) {
            ResponseEntity<JsonNode> hidden = http.getForEntity("/api/v1/products/" + hiddenId, JsonNode.class);
            assertThat(hidden.getStatusCode().value()).isEqualTo(404);
            assertThat(hidden.getBody().path("meta").path("result").asText()).isEqualTo("FAIL");
            assertThat(hidden.getBody().path("meta").path("errorCode").asText()).isEqualTo("PRODUCT_NOT_FOUND");
            assertThat(hidden.getBody().has("data")).isFalse();
        }
    }

    private void assertResponse(ResponseEntity<JsonNode> response, JsonNode expected) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        assertThat(response.getBody().path("data")).isEqualTo(expected);
    }

    private void assertBindingsCaptured(JdbcReadProbe.Query query) {
        long placeholders = query.sql().chars().filter(character -> character == '?').count();
        assertThat(query.parameters()).hasSize(Math.toIntExact(placeholders));
        assertThat(query.parameters()).doesNotContainNull();
    }

    private JsonNode expectedPage(Integer brandId, String sort, int page, int size) {
        Comparator<Integer> ordering = switch (sort) {
            case "price_asc" -> Comparator.comparingInt(ProductQueryBaselineTest::price);
            case "likes_desc" -> Comparator.comparingInt(ProductQueryBaselineTest::likes).reversed();
            default -> Comparator.<Integer>comparingInt(index -> index % 1000).reversed();
        };
        List<Integer> matching = IntStream.rangeClosed(1, 10000)
            .filter(index -> brandId == null || 1001 + (index - 1) % 100 == brandId)
            .filter(index -> 1001 + (index - 1) % 100 != 1100 && index % 113 != 0)
            .boxed().sorted(ordering.thenComparing(Comparator.reverseOrder())).toList();
        ObjectNode expected = mapper.createObjectNode();
        ArrayNode items = expected.putArray("items");
        matching.stream().skip((long) page * size).limit(size).forEach(index -> items.add(expectedProduct(index)));
        expected.put("page", page);
        expected.put("size", size);
        expected.put("totalElements", matching.size());
        expected.put("totalPages", (matching.size() + size - 1) / size);
        return expected;
    }

    private ObjectNode expectedProduct(int index) {
        ObjectNode item = mapper.createObjectNode();
        item.put("productId", 100000 + index);
        item.put("name", "W4 product %05d".formatted(index));
        item.put("price", price(index));
        item.put("stockQuantity", index % 17 == 0 ? 0 : 1 + index % 50);
        ObjectNode brand = item.putObject("brand");
        int actualBrandId = 1001 + (index - 1) % 100;
        brand.put("brandId", actualBrandId);
        brand.put("name", "W4 brand %03d".formatted(actualBrandId - 1000));
        item.put("likeCount", likes(index));
        return item;
    }

    private static int price(int index) {
        return 1000 + index * 37 % 1000 * 100;
    }

    private static int likes(int index) {
        return index % 997 == 0 ? 200 : ((index - 1) / 100 + index) % 10;
    }

    private void writeReport(String label, List<Long> elapsedNanos, List<Integer> sqlCounts, JsonNode response,
                             List<Map<String, Object>> plans) throws IOException {
        List<Long> sorted = elapsedNanos.stream().sorted().toList();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("measuredAt", Instant.now().toString());
        report.put("stage", label + "; actual DB-only HTTP path, no cache");
        report.put("sourceSha256", sha256(Path.of("src/main/java/com/loopers/infrastructure/product/ProductQueryRepositoryImpl.java")));
        report.put("seedSha256", sha256(Path.of("src/test/resources", SEED)));
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("mysqlVersion", jdbc.queryForObject("SELECT VERSION()", String.class));
        report.put("seed", SEED + " (v1)");
        List<String> appliedScripts = new ArrayList<>(SCHEMA);
        if (!COMPARE_INDEX || label.contains("-candidate-")) {
            appliedScripts.add(INDEX_ADDITION);
        }
        report.put("schemaScripts", appliedScripts);
        report.put("sessionIsolation", jdbc.queryForObject("SELECT @@SESSION.transaction_isolation", String.class));
        report.put("preparation", "Manual DDL, seed v1, ANALYZE TABLE brand/product/like/user, contract checks, warmups");
        report.put("seedStatistics", Map.of("brands", 100, "products", 10000, "likes", 46955,
            "visibleProducts", 9812, "deletedProducts", 188, "zeroLikeProducts", 999,
            "popularProducts", 10, "soldOutProducts", 588, "priceSum", 509500000));
        report.put("request", REQUEST);
        report.put("warmupCount", WARMUPS);
        report.put("sampleCount", SAMPLES);
        report.put("rawHttpNanos", elapsedNanos);
        report.put("httpLatencyMillis", Map.of("min", sorted.getFirst() / 1_000_000.0,
            "p50", percentile(sorted, 0.50) / 1_000_000.0,
            "p95", percentile(sorted, 0.95) / 1_000_000.0, "max", sorted.getLast() / 1_000_000.0));
        report.put("sqlCountsPerRequest", sqlCounts);
        report.put("response", response);
        report.put("plansAfterTiming", plans);
        report.put("physicalIndexes", jdbc.queryForList("""
            SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME, COLLATION, INDEX_TYPE
            FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
            ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX
            """));
        report.put("limitations", List.of("One local process; sequential requests, no concurrent load",
            "Contract checks and five warmups precede timing; this is not a cold-disk measurement",
            "HTTP timing includes local client and JSON decoding; assertions and EXPLAIN are outside timing",
            "JDBC executeNanos excludes subsequent ResultSet consumption; it is not complete query cost",
            "Nearest-rank percentiles from 20 samples; no fixed speedup or latency pass threshold",
            "Dedicated Redis is only application infrastructure here; this does not test cache hit/miss"));
        Path destination = Path.of("build", "reports", "week4", "product-query-" + label + ".json");
        Files.createDirectories(destination.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(destination.toFile(), report);
    }

    private static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static long percentile(List<Long> sorted, double percentile) {
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1));
    }

    static final class ContainerExclusion extends TypeExcludeFilter {
        @Override
        public boolean match(MetadataReader metadataReader, MetadataReaderFactory factory) {
            return metadataReader.getClassMetadata().getClassName().startsWith("com.loopers.testcontainers.");
        }

        @Override
        public boolean equals(Object other) {
            return other != null && other.getClass() == getClass();
        }

        @Override
        public int hashCode() {
            return getClass().hashCode();
        }
    }
}
