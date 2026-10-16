package com.loopers.application.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductListCache;
import com.loopers.domain.product.ProductListRevisionRepository;
import com.loopers.domain.product.ProductListSnapshot;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.user.UserRole;
import com.loopers.support.JdbcReadProbe;
import com.loopers.testcontainers.RedisTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "commerce.product-list-cache.enabled=true",
    "commerce.product-list-cache.namespace=commerce:test:cache-api:v1",
    "spring.jpa.show-sql=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductListCacheApiIntegrationTest {
    private static final String PREFIX = "commerce:test:cache-api:v1";
    private static final String REQUEST = "/api/v1/products?sort=likes_desc&page=0&size=20";
    @Autowired private TestRestTemplate http;
    @Autowired private ObjectMapper mapper;
    @Autowired private ProductListReader reader;
    @Autowired private ProductRepository products;
    @Autowired private BrandRepository brands;
    @Autowired private AdminProductService admin;
    @Autowired private ProductQueryService queries;
    @Autowired private ProductListRevisionRepository revisions;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("productListCacheRedisTemplate") private StringRedisTemplate redis;
    @MockitoSpyBean private ProductListCache cache;
    @MockitoSpyBean(name = "mySqlMainDataSource") private HikariDataSource dataSource;
    private final JdbcReadProbe probe = new JdbcReadProbe();
    private long productId;
    private long brandId;
    private boolean terminated = true;

    @DynamicPropertySource
    static void initializeSharedTestRedisBeforeConfigurationBinding(DynamicPropertyRegistry properties) {
        // 기존 테스트 fixture의 생성자보다 캐시 설정 바인딩이 먼저 실행되는 순서 의존을 제거한다.
        new RedisTestContainersConfig();
        for (String name : List.of("datasource.redis.database", "datasource.redis.master.host",
            "datasource.redis.master.port", "datasource.redis.replicas[0].host", "datasource.redis.replicas[0].port")) {
            String value = System.getProperty(name);
            properties.add(name, () -> value);
        }
    }

    @BeforeEach
    void setup() throws SQLException {
        clearOwnedKeys();
        Brand brand = brands.save(new Brand("캐시 브랜드"));
        brandId = brand.getId();
        productId = products.save(new Product(brand, "캐시 상품", 1000, 5)).getId();
        doAnswer(invocation -> probe.wrap((Connection) invocation.callRealMethod())).when(dataSource).getConnection();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(cache).find(anyLong());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(cache).store(any());
    }

    @AfterEach
    void cleanup() {
        assertThat(terminated).as("worker 종료 후 데이터 정리").isTrue();
        clearOwnedKeys();
        cleanup.truncateAllTables();
    }

    @Test
    void databaseMissAndHitReturnExactlyTheSamePublicDtoWithMeasuredSelectCounts() {
        doReturn(false).when(cache).isEnabled();
        Observation database = observe(REQUEST);
        doCallRealMethod().when(cache).isEnabled();
        Observation miss = observe(REQUEST);
        Observation hit = observe(REQUEST);
        assertThat(miss.data()).isEqualTo(database.data());
        assertThat(hit.data()).isEqualTo(database.data());
        assertThat(database.sql()).as("전체 개수·ID/좋아요 순위·선정 상품 상세").hasSize(3);
        assertThat(miss.sql()).as("최초 버전·snapshot 버전·전체 개수·순위·상세").hasSize(5);
        assertThat(database.sql().getLast().sql()).contains("from product ", " in (")
            .doesNotContain("`like`", "group by");
        assertThat(miss.sql().getLast().sql()).contains("from product ", " in (")
            .doesNotContain("`like`", "group by");
        assertThat(hit.sql()).singleElement().satisfies(query ->
            assertThat(query.sql()).contains("product_list_revision").doesNotContain("`like`"));
        JsonNode item = hit.data().path("items").get(0);
        assertThat(item.size()).isEqualTo(6);
        assertThat(item.has("deletedAt")).isFalse();
        assertThat(item.has("liked")).isFalse();
        assertThat(item.path("brand").size()).isEqualTo(2);
        assertThat(redis.getExpire(key(0), TimeUnit.SECONDS)).isBetween(1L, 60L);
    }

    @Test
    void otherSortsFiltersSizesPagesAndDetailDoNotReadRevisionOrRedis() {
        get(REQUEST);
        for (String path : List.of("/api/v1/products", "/api/v1/products?sort=price_asc",
            REQUEST + "&brandId=" + brandId, "/api/v1/products?sort=likes_desc&page=1&size=20",
            "/api/v1/products?sort=likes_desc&size=1", "/api/v1/products/" + productId)) {
            Observation response = observe(path);
            assertThat(response.sql()).isNotEmpty().allSatisfy(query ->
                assertThat(query.sql()).doesNotContain("product_list_revision"));
        }
        assertThat(redis.keys(PREFIX + ":*")).containsExactly(key(0));
    }

    @Test
    void malformedPayloadIsReplacedByTheCorrectDatabaseSnapshot() throws Exception {
        redis.opsForValue().set(key(0), "{\"privateUser\":\"alice\"}", Duration.ofSeconds(60));
        Observation miss = observe(REQUEST);
        assertThat(miss.sql()).hasSize(5);
        assertThat(miss.data()).isEqualTo(json(reader.readList(null, 0, 20, ProductSort.LIKES_DESC)));
        assertThat(observe(REQUEST).sql()).hasSize(1);
    }

    @Test
    void uncommittedOuterTransactionNeverPublishesItsPage() throws Exception {
        var before = reader.readList(null, 0, 20, ProductSort.LIKES_DESC);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("UPDATE product SET price=2000 WHERE id=?", productId);
            revisions.advance();
            assertThat(queries.getList(null, 0, 20, ProductSort.LIKES_DESC).items().getFirst().price()).isEqualTo(2000);
            assertThat(redis.keys(PREFIX + ":*")).isEmpty();
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(revisions.current()).isZero();
        assertThat(get(REQUEST)).isEqualTo(json(before));
    }

    @Test
    @Timeout(30)
    void lateOldFillCannotOverwriteTheNewRevisionAfterDeletion() throws Exception {
        raceWithDeletion(false);
    }

    @Test
    @Timeout(30)
    void hitThatReadRevisionBeforeDeletionMayFinishWithItsOldVersion() throws Exception {
        get(REQUEST);
        raceWithDeletion(true);
    }

    private void raceWithDeletion(boolean hit) throws Exception {
        CountDownLatch observed = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean once = new AtomicBoolean();
        var answer = (org.mockito.stubbing.Answer<Object>) invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (once.compareAndSet(false, true)) {
                if (!hit) {
                    ProductListSnapshot snapshot = invocation.getArgument(0);
                    assertThat(snapshot.revision()).isZero();
                    assertThat(snapshot.page().items()).hasSize(1);
                }
                observed.countDown();
                assertThat(resume.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        };
        if (hit) {
            doAnswer(answer).when(cache).find(anyLong());
        } else {
            doAnswer(answer).when(cache).store(any());
        }
        var executor = Executors.newSingleThreadExecutor();
        terminated = false;
        try {
            var pending = executor.submit(() -> get(REQUEST));
            assertThat(observed.await(10, TimeUnit.SECONDS)).isTrue();
            admin.delete(UserRole.ADMIN, productId);
            assertThat(revisions.current()).isEqualTo(1);
            resume.countDown();
            assertThat(pending.get(10, TimeUnit.SECONDS).path("items")).hasSize(1);
            assertThat(redis.hasKey(key(0))).isTrue();
            JsonNode next = get(REQUEST);
            assertThat(next.path("items")).isEmpty();
            assertThat(next.path("totalElements").asLong()).isZero();
            assertThat(redis.hasKey(key(1))).isTrue();
        } finally {
            resume.countDown();
            executor.shutdownNow();
            terminated = executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(120)
    void recordsDatabaseMissAndHitLatencyOnTheSameTenThousandProductFixture() throws Exception {
        cleanup.truncateAllTables();
        new ResourceDatabasePopulator(new ClassPathResource("fixtures/week4/product-query-baseline.sql"))
            .execute(dataSource);
        jdbc.queryForList("ANALYZE TABLE brand, product, `like`, `user`");
        doReturn(false).when(cache).isEnabled();
        JsonNode expected = get(REQUEST);
        assertThat(expected.path("totalElements").asLong()).isEqualTo(9812);
        Map<String, Object> measurements = new LinkedHashMap<>();
        // 같은 실제 HTTP 경로·fixture·워밍업으로 비용을 비교한다. 지연 수치는 flaky assertion으로 만들지 않는다.
        for (String mode : List.of("database", "miss", "hit")) {
            doReturn(!mode.equals("database")).when(cache).isEnabled();
            for (int i = 0; i < 10; i++) {
                if (mode.equals("miss")) {
                    redis.delete(key(0));
                }
                assertThat(get(REQUEST)).isEqualTo(expected);
            }
            List<Long> nanos = new ArrayList<>();
            int expectedSelects = mode.equals("database") ? 3 : mode.equals("miss") ? 5 : 1;
            for (int i = 0; i < 50; i++) {
                if (mode.equals("miss")) {
                    redis.delete(key(0));
                }
                Observation sample = observe(REQUEST);
                assertThat(sample.data()).isEqualTo(expected);
                assertThat(sample.sql()).hasSize(expectedSelects);
                nanos.add(sample.nanos());
            }
            long[] sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
            measurements.put(mode, Map.of("samples", 50, "selects", expectedSelects,
                "p50Millis", sorted[24] / 1_000_000.0, "p95Millis", sorted[47] / 1_000_000.0,
                "rawNanos", Arrays.stream(sorted).boxed().toList()));
        }
        Path output = Path.of("build/reports/week4/product-list-cache-comparison.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(measurements));
    }

    private Observation observe(String path) {
        probe.start();
        long start = System.nanoTime();
        JsonNode data;
        List<JdbcReadProbe.Query> sql;
        long nanos;
        try {
            data = get(path);
            nanos = System.nanoTime() - start;
        } finally {
            sql = probe.stop();
        }
        return new Observation(data, sql, nanos);
    }

    private JsonNode get(String path) {
        var response = http.getForEntity(path, JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        return response.getBody().path("data");
    }

    private JsonNode json(Object value) throws Exception {
        // HTTP와 같은 JSON 왕복으로 정수 노드 타입을 맞추되 필드·값·순서는 모두 비교한다.
        return mapper.readTree(mapper.writeValueAsString(value));
    }

    private void clearOwnedKeys() {
        var keys = redis.keys(PREFIX + ":*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private String key(long revision) {
        return PREFIX + ":brand=all:sort=likes_desc:page=0:size=20:rev=" + revision;
    }

    private record Observation(JsonNode data, List<JdbcReadProbe.Query> sql, long nanos) {
    }
}
