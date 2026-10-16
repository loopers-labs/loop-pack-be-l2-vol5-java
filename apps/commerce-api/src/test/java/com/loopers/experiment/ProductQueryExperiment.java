package com.loopers.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.product.ProductListCache;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상품 조회 측정 실험 (4주차). 제품 코드가 아니며 결과는 설계 문서와 기술 글의 근거로 씀.
 *
 * <ul>
 *   <li>측정용 데이터는 seed/week4-product-seed.sql 로 넣음. 규칙과 검증값은 docs/week4/product-query-seed.md</li>
 *   <li>seed 가 어긋나면 측정 전에 실패하도록 건수 · 합계를 먼저 확인함</li>
 *   <li>응답은 seed 규칙으로 따로 계산한 기대 결과와 비교함. 인덱스 · 쿼리를 바꾼 뒤에도 같은 비교로 결과가 바뀌지 않았는지 확인함</li>
 *   <li>실행 계획에는 Hibernate 가 실제로 보낸 SQL 을 그대로 넣음 (SqlCapture)</li>
 *   <li>SQL 출력(show-sql)은 지연 측정에 섞이지 않도록 끔. 측정값은 build/experiment/product-query.md 에 남김</li>
 *   <li>기본 seed 의 장면을 모두 잰 뒤, 상품 수가 많은 브랜드(seed/week4-large-brand-seed.sql)를 더해 그 브랜드의 장면을 잼</li>
 *   <li>목록 첫 페이지는 캐시된다(docs/week4/cache-design.md). 장면마다 hit 와 miss 를 나눠 잰다.
 *       miss 는 요청마다 그 범위의 버전을 올려 만들며, 버전을 올리는 시간은 측정에서 뺀다</li>
 *   <li>이 실험 전용 Redis 컨테이너를 쓰고, 마지막에 일시정지해 Redis 무응답 때 DB 로 우회하는 지연을 잰다</li>
 * </ul>
 * 실행: RUN_EXPERIMENT=true ./gradlew :apps:commerce-api:test --tests '*ProductQueryExperiment'
 */
@SpringBootTest(properties = {
    "spring.jpa.show-sql=false",
    "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.loopers.experiment.SqlCapture"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "RUN_EXPERIMENT", matches = "true")
class ProductQueryExperiment {

    private static final String SEED = "seed/week4-product-seed.sql";
    private static final String LARGE_BRAND_SEED = "seed/week4-large-brand-seed.sql";
    private static final long LARGE_BRAND_ID = 101L;
    private static final int BASE_PRODUCTS = 10_000;
    private static final int ALL_PRODUCTS = 30_000;
    private static final Path REPORT = Path.of("build", "experiment", "product-query.md");

    private static final long MEASURED_BRAND_ID = 7L;
    private static final int PAGE = 1;
    private static final int SIZE = 20;
    private static final int WARMUP_REQUESTS = 50;
    private static final int MEASURED_REQUESTS = 200;
    /** Redis 무응답 장면은 요청마다 대기 한도(100ms)를 기다리므로 표본을 줄인다 */
    private static final int OUTAGE_WARMUP_REQUESTS = 5;
    private static final int OUTAGE_MEASURED_REQUESTS = 50;

    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> REDIS.getMappedPort(6379));
        registry.add("datasource.redis.replicas[0].host", REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> REDIS.getMappedPort(6379));
    }
    private static final List<String> PLAN_COLUMNS = List.of(
        "id", "select_type", "table", "type", "possible_keys", "key", "key_len", "ref", "rows", "filtered", "Extra");

    private static final String LATENCY_HEADER = "| 구분 | 표본 | 평균(ms) | p50(ms) | p95(ms) | p99(ms) | 최대(ms) |\n|---|---|---|---|---|---|---|";

    /** 좋아요 수 행이 없는 상품과, 값이 관계 수와 다른 행의 수 */
    private static final String LIKE_COUNT_MISMATCH = "select count(*) from product p "
        + "left join product_like_count c on c.product_id = p.id "
        + "where c.id is null or c.like_count <> (select count(*) from likes l where l.product_id = p.id)";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductListCache productListCache;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private JdbcTemplate jdbcTemplate;

    /** 지금 DB 에 들어 있는 상품 순번의 끝. 큰 브랜드를 더하면 늘어남 */
    private int seededProducts = BASE_PRODUCTS;

    /** seed 규칙으로 계산한 상품 한 건. DB 를 거치지 않은 기대 결과의 원천 */
    private record SeedProduct(long id, long price, long likeCount, boolean soldOut, long createdOffset) {}

    /** 응답에서 비교하는 값 */
    private record Item(long id, long price, long likeCount, boolean soldOut) {}

    private record Page(List<Item> items, int page, int size, long totalCount, int totalPages) {}

    /** 상세 응답에서 비교하는 값 */
    private record Detail(long id, long price, long likeCount, boolean soldOut, long brandId, String brandName) {}

    @BeforeAll
    void seed() throws SQLException, IOException {
        jdbcTemplate = new JdbcTemplate(dataSource);
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
        runScript(SEED);

        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, String.join("\n",
            "# 상품 목록 조회 측정",
            "",
            "- MySQL " + jdbcTemplate.queryForObject("select version()", String.class) + " (Testcontainers), 테스트 커넥션 풀 10",
            "- 요청: `GET /api/v1/products?[brandId=" + MEASURED_BRAND_ID + "&]sort={정렬}&page=" + PAGE + "&size=" + SIZE + "`",
            "- MockMvc 로 한 스레드에서 차례로 요청. 네트워크 · 톰캣은 포함하지 않음",
            "- 준비 " + WARMUP_REQUESTS + "회 뒤 측정 " + MEASURED_REQUESTS + "회. SQL 출력(show-sql) 끔",
            "- 목록 첫 페이지 캐시: hit 는 같은 요청 반복, miss 는 요청마다 범위 버전을 올림(올리는 시간은 제외). Redis " + REDIS.getDockerImageName() + " 단일 노드",
            "",
            ""
        ));
    }

    @AfterAll
    void cleanUp() {
        resumeRedis();
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private void runScript(String script) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(script));
        }
        jdbcTemplate.queryForList("analyze table users, brand, product, likes, product_like_count");
    }

    @Test
    @Order(1)
    void seedMatchesExpectedValues() {
        assertAll(
            () -> assertThat(count("select count(*) from users")).isEqualTo(1_000),
            () -> assertThat(count("select count(*) from brand where deleted_at is null")).isEqualTo(100),
            () -> assertThat(count("select count(*) from product")).isEqualTo(10_000),
            () -> assertThat(count("select sum(price) from product")).isEqualTo(472_685_000),
            () -> assertThat(count("select count(*) from product where deleted_at is null")).isEqualTo(9_412),
            () -> assertThat(count("select sum(price) from product where deleted_at is null")).isEqualTo(445_004_786),
            () -> assertThat(count("select count(*) from product where brand_id = ?", MEASURED_BRAND_ID)).isEqualTo(100),
            () -> assertThat(count("select sum(price) from product where brand_id = ?", MEASURED_BRAND_ID)).isEqualTo(4_647_200),
            () -> assertThat(count("select count(*) from product where brand_id = ? and deleted_at is null", MEASURED_BRAND_ID))
                .isEqualTo(94),
            () -> assertThat(count("select sum(price) from product where brand_id = ? and deleted_at is null", MEASURED_BRAND_ID))
                .isEqualTo(4_435_168),
            () -> assertThat(count("select count(*) from likes")).isEqualTo(100_163),
            () -> assertThat(count("select count(*) from likes l join product p on p.id = l.product_id where p.deleted_at is null"))
                .isEqualTo(94_276),
            () -> assertThat(count("select count(*) from likes l left join users u on u.id = l.user_id where u.id is null"))
                .isZero(),
            () -> assertThat(count("select count(*) from product_like_count")).isEqualTo(10_000),
            () -> assertThat(count("select sum(like_count) from product_like_count")).isEqualTo(100_163),
            () -> assertThat(count(LIKE_COUNT_MISMATCH)).isZero()
        );
    }

    /** brandId 가 비면 브랜드 필터 없는 전체 목록이다 */
    @ParameterizedTest(name = "브랜드 {0} · {1} 목록")
    @CsvSource({
        "7, latest", "7, price_asc", "7, likes_desc",
        ", latest", ", price_asc", ", likes_desc"
    })
    @Order(2)
    void measure(Long brandId, String sort) throws Exception {
        measureScenario(brandId, sort);
    }

    /** 브랜드 7 의 보통 상품 · 좋아요 0 상품 · 인기 상품(좋아요 250) */
    @ParameterizedTest(name = "상품 {0} 상세")
    @ValueSource(longs = {6, 7, 97})
    @Order(3)
    void measureDetail(long productId) throws Exception {
        SqlCapture.clear();
        Detail actual = requestDetail(productId);
        List<String> statements = SqlCapture.statements();

        int i = (int) productId;
        SeedProduct expected = seedProduct(i);
        assertThat(actual).isEqualTo(new Detail(i, expected.price(), expected.likeCount(), expected.soldOut(),
            brandOf(i), "brand-" + brandOf(i)));

        long[] nanos = measureLatency(() -> requestDetail(productId));

        List<String> lines = new ArrayList<>();
        lines.add("## 상세 · 상품 " + productId + " (좋아요 " + actual.likeCount() + ")");
        lines.add("");
        lines.add("- 요청당 SQL: " + statements.size() + "개");
        lines.add("- 결과: 기대 결과와 일치");
        lines.add("");
        lines.add(LATENCY_HEADER);
        lines.add(latencyRow("상세", nanos));
        lines.add("");
        appendPlans(lines, statements, sql -> new Object[] {productId});
        Files.writeString(REPORT, String.join("\n", lines) + "\n", StandardOpenOption.APPEND);
    }

    private Detail requestDetail(long productId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/products/" + productId))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        return new Detail(data.path("id").asLong(), data.path("price").asLong(), data.path("likeCount").asLong(),
            data.path("soldOut").asBoolean(), data.path("brand").path("id").asLong(), data.path("brand").path("name").asText());
    }

    /** 기본 seed 장면을 모두 잰 뒤 큰 브랜드를 더한다. 첫 실행에서만 넣는다 */
    @ParameterizedTest(name = "큰 브랜드 {0} · {1} 목록")
    @CsvSource({"101, latest", "101, price_asc", "101, likes_desc"})
    @Order(4)
    void measureLargeBrand(Long brandId, String sort) throws Exception {
        if (seededProducts == BASE_PRODUCTS) {
            runScript(LARGE_BRAND_SEED);
            seededProducts = ALL_PRODUCTS;
            assertAll(
                () -> assertThat(count("select count(*) from product where brand_id = ?", LARGE_BRAND_ID)).isEqualTo(20_000),
                () -> assertThat(count("select count(*) from product where brand_id = ? and deleted_at is null", LARGE_BRAND_ID))
                    .isEqualTo(18_824),
                () -> assertThat(count("select sum(price) from product where brand_id = ? and deleted_at is null", LARGE_BRAND_ID))
                    .isEqualTo(958_590_644),
                () -> assertThat(count("select count(*) from likes")).isEqualTo(100_163 + 200_321),
                () -> assertThat(count(LIKE_COUNT_MISMATCH)).isZero()
            );
        }
        measureScenario(brandId, sort);
    }

    private void measureScenario(Long brandId, String sort) throws Exception {
        Page expected = expectedPage(brandId, sort, seededProducts);

        // miss: 이 범위의 버전을 올려 캐시를 버린 뒤 요청한다. 원본이 실행한 SQL 을 모은다
        productListCache.invalidate(brandId);
        SqlCapture.clear();
        Page miss = request(brandId, sort);
        List<String> statements = SqlCapture.statements();

        // hit: 같은 요청을 다시 보낸다. SQL 이 없어야 한다
        SqlCapture.clear();
        Page hit = request(brandId, sort);
        List<String> hitStatements = SqlCapture.statements();

        assertAll(
            () -> assertThat(miss).isEqualTo(expected),
            () -> assertThat(hit).isEqualTo(expected),
            () -> assertThat(hitStatements).isEmpty()
        );

        long[] hitNanos = measureLatency(() -> request(brandId, sort));
        long[] missNanos = measureLatency(() -> productListCache.invalidate(brandId), () -> request(brandId, sort),
            WARMUP_REQUESTS, MEASURED_REQUESTS);

        appendReport(brandId, sort, miss, statements, hitNanos, missNanos);
    }

    /**
     * Redis 무응답 장면: 실험 전용 Redis 를 일시정지한 동안 목록이 DB 로 우회하는 지연 (cache-design.md 4.2).
     * 큰 브랜드를 더한 뒤라 앞선 장면과 데이터가 다르다. 그래서 같은 데이터 · 같은 표본 수로 Redis 정상 때의 miss 를 바로 앞에 재서 비교한다
     */
    @ParameterizedTest(name = "Redis 무응답 · 브랜드 {0} · {1} 목록")
    @CsvSource({"7, price_asc", ", likes_desc"})
    @Order(5)
    void measureRedisOutage(Long brandId, String sort) throws Exception {
        Page expected = expectedPage(brandId, sort, seededProducts);
        long[] missNanos = measureLatency(() -> productListCache.invalidate(brandId), () -> request(brandId, sort),
            OUTAGE_WARMUP_REQUESTS, OUTAGE_MEASURED_REQUESTS);
        pauseRedis();
        try {
            SqlCapture.clear();
            Page bypassed = request(brandId, sort);
            List<String> statements = SqlCapture.statements();
            assertAll(
                () -> assertThat(bypassed).isEqualTo(expected),
                () -> assertThat(statements).hasSize(2)
            );

            long[] nanos = measureLatency(() -> { }, () -> request(brandId, sort), OUTAGE_WARMUP_REQUESTS, OUTAGE_MEASURED_REQUESTS);

            List<String> lines = new ArrayList<>();
            lines.add("## Redis 무응답 · " + (brandId == null ? "전체" : "브랜드 " + brandId) + " · " + sort);
            lines.add("");
            lines.add("- 결과: 기대 결과와 일치. 요청당 SQL " + statements.size() + "개 (원본으로 우회)");
            lines.add("- 상품 " + seededProducts + "개 상태(큰 브랜드 포함). miss(정상)는 같은 상태에서 Redis 정상일 때 잰 값");
            lines.add("");
            lines.add(LATENCY_HEADER);
            lines.add(latencyRow("miss(정상)", missNanos));
            lines.add(latencyRow("우회(무응답)", nanos));
            lines.add("");
            Files.writeString(REPORT, String.join("\n", lines) + "\n", StandardOpenOption.APPEND);
        } finally {
            resumeRedis();
        }
    }

    private static void pauseRedis() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private static void resumeRedis() {
        if (Boolean.TRUE.equals(REDIS.getDockerClient().inspectContainerCmd(REDIS.getContainerId()).exec().getState().getPaused())) {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    @FunctionalInterface
    private interface Request {
        Object send() throws Exception;
    }

    @FunctionalInterface
    private interface Preparation {
        void run() throws Exception;
    }

    /** 준비 요청 뒤 측정 요청의 지연(ns) */
    private static long[] measureLatency(Request request) throws Exception {
        return measureLatency(() -> { }, request, WARMUP_REQUESTS, MEASURED_REQUESTS);
    }

    /** 요청마다 preparation 을 먼저 실행하고, 그 시간은 빼고 요청만 잰다 */
    private static long[] measureLatency(Preparation preparation, Request request, int warmups, int samples) throws Exception {
        for (int i = 0; i < warmups; i++) {
            preparation.run();
            request.send();
        }
        long[] nanos = new long[samples];
        for (int i = 0; i < samples; i++) {
            preparation.run();
            long start = System.nanoTime();
            request.send();
            nanos[i] = System.nanoTime() - start;
        }
        return nanos;
    }

    private Page request(Long brandId, String sort) throws Exception {
        MockHttpServletRequestBuilder builder = get("/api/v1/products")
            .param("sort", sort)
            .param("page", String.valueOf(PAGE))
            .param("size", String.valueOf(SIZE));
        if (brandId != null) {
            builder.param("brandId", String.valueOf(brandId));
        }
        MvcResult result = mvc.perform(builder)
            .andExpect(status().isOk())
            .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        List<Item> items = new ArrayList<>();
        for (JsonNode item : data.path("items")) {
            items.add(new Item(item.path("id").asLong(), item.path("price").asLong(),
                item.path("likeCount").asLong(), item.path("soldOut").asBoolean()));
        }
        return new Page(items, data.path("page").asInt(), data.path("size").asInt(),
            data.path("totalCount").asLong(), data.path("totalPages").asInt());
    }

    // seed 규칙(docs/week4/product-query-seed.md 2절)으로 살아 있는 상품을 만들고 API 계약의 필터 · 정렬로 자른다
    private static Page expectedPage(Long brandId, String sort, int seededProducts) {
        Comparator<SeedProduct> order = switch (sort) {
            case "latest" -> Comparator.comparingLong(SeedProduct::createdOffset).reversed();
            case "price_asc" -> Comparator.comparingLong(SeedProduct::price);
            case "likes_desc" -> Comparator.comparingLong(SeedProduct::likeCount).reversed();
            default -> throw new IllegalArgumentException(sort);
        };
        List<SeedProduct> candidates = IntStream.rangeClosed(1, seededProducts)
            .filter(i -> i % 17 != 0 && (brandId == null || brandOf(i) == brandId))
            .mapToObj(ProductQueryExperiment::seedProduct)
            .sorted(order.thenComparing(Comparator.comparingLong(SeedProduct::id).reversed()))
            .toList();
        List<Item> items = candidates.stream()
            .skip((long) (PAGE - 1) * SIZE)
            .limit(SIZE)
            .map(p -> new Item(p.id(), p.price(), p.likeCount(), p.soldOut()))
            .toList();
        int totalPages = (candidates.size() + SIZE - 1) / SIZE;
        return new Page(items, PAGE, SIZE, candidates.size(), totalPages);
    }

    // 기본 seed 는 (i % 100) + 1, 큰 브랜드 seed 는 모두 101
    private static long brandOf(int i) {
        return i <= BASE_PRODUCTS ? (i % 100) + 1 : LARGE_BRAND_ID;
    }

    private static SeedProduct seedProduct(int i) {
        long likeCount;
        if (i % 97 == 0) {
            likeCount = 250;
        } else if (i % 7 == 0) {
            likeCount = 0;
        } else {
            likeCount = ((i * 31L) % 97) % 17 + 1;
        }
        return new SeedProduct(i, ((i * 37L) % 100_000) + 100, likeCount, i % 13 == 0, (i * 7919L) % 10_000);
    }

    private void appendReport(Long brandId, String sort, Page actual, List<String> statements, long[] hitNanos, long[] missNanos)
        throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("## " + (brandId == null ? "전체" : "브랜드 " + brandId) + " · " + sort);
        lines.add("");
        lines.add("- 요청당 SQL: miss " + statements.size() + "개, hit 0개");
        lines.add("- 결과: miss · hit 모두 기대 결과와 일치. totalCount " + actual.totalCount() + ", totalPages " + actual.totalPages());
        lines.add("- 첫 페이지 id: " + actual.items().stream().map(item -> String.valueOf(item.id())).toList());
        lines.add("");
        lines.add(LATENCY_HEADER);
        lines.add(latencyRow("hit", hitNanos));
        lines.add(latencyRow("miss", missNanos));
        lines.add("");
        appendPlans(lines, statements, sql -> argsOf(sql, brandId));
        Files.writeString(REPORT, String.join("\n", lines) + "\n", StandardOpenOption.APPEND);
    }

    private void appendPlans(List<String> lines, List<String> statements, Function<String, Object[]> argsFor) {
        for (int i = 0; i < statements.size(); i++) {
            String sql = statements.get(i);
            Object[] args = argsFor.apply(sql);
            lines.add("### SQL " + (i + 1) + " (인자 " + Arrays.toString(args) + ")");
            lines.add("");
            lines.add("```sql");
            lines.add(sql);
            lines.add("```");
            lines.add("");
            lines.add(planTable(jdbcTemplate.queryForList("explain " + sql, args)));
            lines.add("");
            lines.add("```");
            jdbcTemplate.queryForList("explain analyze " + sql, args)
                .forEach(row -> lines.add(String.valueOf(row.values().iterator().next())));
            lines.add("```");
            lines.add("");
        }
    }

    // 목록 SQL 은 ([브랜드], offset, limit), 건수 SQL 은 ([브랜드]) 를 받는다. Querydsl 은 첫 페이지에도 offset 을 넘긴다
    private static Object[] argsOf(String sql, Long brandId) {
        List<Object> args = new ArrayList<>();
        if (brandId != null) {
            args.add(brandId);
        }
        if (sql.contains(" limit ")) {
            args.add((long) (PAGE - 1) * SIZE);
            args.add(SIZE);
        }
        long placeholders = sql.chars().filter(c -> c == '?').count();
        if (placeholders != args.size()) {
            throw new IllegalStateException("예상하지 못한 인자 수 " + placeholders + ": " + sql);
        }
        return args.toArray();
    }

    private static String latencyRow(String label, long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        double mean = Arrays.stream(sorted).average().orElse(0);
        return String.format("| %s | %d | %.2f | %.2f | %.2f | %.2f | %.2f |",
            label, sorted.length, mean / 1e6, percentile(sorted, 0.50), percentile(sorted, 0.95),
            percentile(sorted, 0.99), sorted[sorted.length - 1] / 1e6);
    }

    private static double percentile(long[] sorted, double p) {
        int index = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(index, 0)] / 1e6;
    }

    private static String planTable(List<Map<String, Object>> rows) {
        List<String> lines = new ArrayList<>();
        lines.add("| " + String.join(" | ", PLAN_COLUMNS) + " |");
        lines.add("|" + "---|".repeat(PLAN_COLUMNS.size()));
        for (Map<String, Object> row : rows) {
            lines.add("| " + String.join(" | ", PLAN_COLUMNS.stream().map(column -> String.valueOf(row.get(column))).toList()) + " |");
        }
        return String.join("\n", lines);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
