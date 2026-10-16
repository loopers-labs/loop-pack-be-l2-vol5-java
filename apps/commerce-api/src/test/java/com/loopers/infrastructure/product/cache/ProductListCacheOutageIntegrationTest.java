package com.loopers.infrastructure.product.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.CommerceApiApplication;
import com.loopers.application.product.AdminProductService;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.ProductListCache;
import com.loopers.domain.product.ProductListRevisionRepository;
import com.loopers.domain.user.UserRole;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.autoconfigure.filter.TypeExcludeFilters;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CommerceApiApplication.class, properties = {
    "spring.jpa.hibernate.ddl-auto=create", "spring.jpa.show-sql=false",
    "commerce.product-list-cache.enabled=true", "commerce.product-list-cache.ttl=60s"})
@AutoConfigureMockMvc
@TypeExcludeFilters(ProductListCacheOutageIntegrationTest.OwnedInfrastructureOnly.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductListCacheOutageIntegrationTest {
    private static final String REQUEST = "/api/v1/products?sort=likes_desc&page=0&size=20";
    private static final String NAMESPACE = "commerce:outage:" + UUID.randomUUID() + ":products:v1";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w4_cache_outage")
        .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci")
        .withStartupTimeout(Duration.ofMinutes(2));
    @Container
    private static final OwnedRedisContainer REDIS = new OwnedRedisContainer()
        .withCommand("redis-server", "--appendonly", "yes", "--appendfsync", "always")
        .withStartupTimeout(Duration.ofMinutes(2));

    @Autowired private MockMvc http;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BrandRepository brands;
    @Autowired private AdminProductService products;
    @Autowired private ProductListRevisionRepository revisions;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private MeterRegistry metrics;
    @Autowired @Qualifier(ProductListCacheConfiguration.TEMPLATE) private StringRedisTemplate redis;
    @MockitoSpyBean private ProductListCache cache;

    private final Set<String> touchedKeys = new HashSet<>();
    private long updatedId;
    private long deletedId;
    private boolean stopped;

    @DynamicPropertySource
    static synchronized void ownInfrastructure(DynamicPropertyRegistry properties) {
        MYSQL.start();
        REDIS.start();
        properties.add("datasource.mysql-jpa.main.jdbc-url", MYSQL::getJdbcUrl);
        properties.add("datasource.mysql-jpa.main.username", MYSQL::getUsername);
        properties.add("datasource.mysql-jpa.main.password", MYSQL::getPassword);
        properties.add("datasource.redis.database", () -> 0);
        properties.add("datasource.redis.master.host", REDIS::getHost);
        properties.add("datasource.redis.master.port", REDIS::getFirstMappedPort);
        properties.add("datasource.redis.replicas[0].host", REDIS::getHost);
        properties.add("datasource.redis.replicas[0].port", REDIS::getFirstMappedPort);
        properties.add("commerce.product-list-cache.namespace", () -> NAMESPACE);
    }

    @BeforeEach
    void setUp() {
        initializer.initialize();
        long brandId = brands.save(new Brand("장애 테스트 브랜드")).getId();
        updatedId = products.create(UserRole.ADMIN, brandId, "수정할 상품", 1000, 5).productId();
        deletedId = products.create(UserRole.ADMIN, brandId, "삭제할 상품", 2000, 3).productId();
        awaitRedis();
    }

    @AfterEach
    void tearDown() {
        try {
            restartOwnedRedis();
            awaitRedis();
            if (!touchedKeys.isEmpty()) {
                redis.delete(touchedKeys);
            }
        } finally {
            cleanup.truncateAllTables();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void committedAdminChangesAndCustomerFallbackSurviveRedisOutageAndOldKeysAfterRecovery(TestReporter reporter)
        throws Exception {
        long oldRevision = revisions.current();
        JsonNode before = read();
        assertThat(before.path("totalElements").asLong()).isEqualTo(2);
        assertThat(read()).isEqualTo(before);
        String oldBody = redis.opsForValue().get(key(oldRevision));
        assertThat(oldBody).isNotNull();
        int endpoint = REDIS.actualPublishedPort();
        double storesBefore = counter("store", "success") + counter("store", "error");
        double getErrorsBefore = counter("lookup", "error");

        stopOwnedRedis();
        long started = System.nanoTime();
        JsonNode fallback;
        try {
            http.perform(put("/api-admin/v1/products/{id}", updatedId).with(user("admin").roles("ADMIN"))
                    .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"장애 중 수정\",\"price\":3000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.price").value(3000));
            http.perform(delete("/api-admin/v1/products/{id}", deletedId).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.result").value("SUCCESS"));
            assertThat(revisions.current()).isEqualTo(oldRevision + 2);
            assertThat(jdbc.queryForObject("SELECT price FROM product WHERE id = ?", Long.class, updatedId))
                .isEqualTo(3000);
            assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM product WHERE id = ?",
                Boolean.class, deletedId)).isTrue();

            long readStarted = System.nanoTime();
            fallback = read();
            long readMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - readStarted);
            assertThat(readMillis).as("finite outage fallback smoke, not a production latency SLO").isLessThan(5000);
            assertUpdatedPage(fallback);
            assertThat(counter("lookup", "error")).isEqualTo(getErrorsBefore + 1);
            assertThat(counter("store", "success") + counter("store", "error")).isEqualTo(storesBefore);
            recordMeasurement("get", reporter, Map.of("redisEndpoint", REDIS.getHost() + ":" + endpoint,
                "fallbackHttpMillis", readMillis, "adminAndFallbackMillis",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                "smokeThresholdMillis", 5000, "productionSlo", false));
        } finally {
            restartOwnedRedis();
        }
        awaitRedis();
        assertThat(REDIS.actualPublishedPort()).isEqualTo(endpoint);
        assertThat(redis.opsForValue().get(key(oldRevision))).isEqualTo(oldBody);
        assertThat(redis.hasKey(key(revisions.current()))).isFalse();

        double misses = counter("lookup", "miss");
        JsonNode recovered = read();
        assertThat(recovered).isEqualTo(fallback);
        // 최초 조회와 single-flight 리더의 재확인 GET을 각각 관찰한다.
        assertThat(counter("lookup", "miss")).isEqualTo(misses + 2);
        assertThat(redis.hasKey(key(revisions.current()))).isTrue();
        double hits = counter("lookup", "hit");
        assertThat(read()).isEqualTo(recovered);
        assertThat(counter("lookup", "hit")).isEqualTo(hits + 1);
        assertThat(redis.opsForValue().get(key(oldRevision))).isEqualTo(oldBody);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void realRedisSetFailureAfterSuccessfulDatabaseReadPreservesTheSuccessfulCustomerResponse(TestReporter reporter)
        throws Exception {
        long oldRevision = revisions.current();
        read();
        String oldBody = redis.opsForValue().get(key(oldRevision));
        products.update(UserRole.ADMIN, updatedId, "새 가격", 4000);
        long newRevision = revisions.current();
        assertThat(newRevision).isEqualTo(oldRevision + 1);
        double errorsBefore = counter("store", "error");
        double missesBefore = counter("lookup", "miss");
        AtomicBoolean injected = new AtomicBoolean();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (injected.compareAndSet(false, true)) {
                stopOwnedRedis();
            }
            return invocation.callRealMethod();
        }).when(cache).store(any());

        JsonNode result;
        long started = System.nanoTime();
        try {
            result = read();
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(millis).as("includes owned container stop, not a production latency SLO").isLessThan(5000);
            assertThat(injected).isTrue();
            assertThat(counter("lookup", "miss")).isEqualTo(missesBefore + 2);
            assertThat(counter("store", "error")).isEqualTo(errorsBefore + 1);
            assertThat(result.path("totalElements").asLong()).isEqualTo(2);
            assertThat(findItem(result, updatedId).path("price").asLong()).isEqualTo(4000);
            assertThat(jdbc.queryForObject("SELECT price FROM product WHERE id = ?", Long.class, updatedId)).isEqualTo(4000);
            recordMeasurement("set", reporter, Map.of("setFailureHttpMillisIncludingContainerStop", millis,
                "redisEndpoint", REDIS.getHost() + ":" + REDIS.getMappedPort(6379),
                "smokeThresholdMillis", 5000, "productionSlo", false));
        } finally {
            restartOwnedRedis();
        }
        awaitRedis();
        assertThat(redis.opsForValue().get(key(oldRevision))).isEqualTo(oldBody);
        assertThat(redis.hasKey(key(newRevision))).isFalse();
        assertThat(read()).isEqualTo(result);
        double hits = counter("lookup", "hit");
        assertThat(read()).isEqualTo(result);
        assertThat(counter("lookup", "hit")).isEqualTo(hits + 1);
    }

    private JsonNode read() throws Exception {
        touchedKeys.add(key(revisions.current()));
        String body = http.perform(get(REQUEST)).andExpect(status().isOk())
            .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(body).path("data");
    }

    private void assertUpdatedPage(JsonNode page) {
        assertThat(page.path("totalElements").asLong()).isEqualTo(1);
        assertThat(page.path("totalPages").asInt()).isEqualTo(1);
        assertThat(page.path("items")).hasSize(1);
        JsonNode item = page.path("items").get(0);
        assertThat(item.path("productId").asLong()).isEqualTo(updatedId);
        assertThat(item.path("name").asText()).isEqualTo("장애 중 수정");
        assertThat(item.path("price").asLong()).isEqualTo(3000);
    }

    private JsonNode findItem(JsonNode page, long id) {
        for (JsonNode item : page.path("items")) {
            if (item.path("productId").asLong() == id) {
                return item;
            }
        }
        throw new AssertionError("Expected product is missing: " + id);
    }

    private double counter(String operation, String outcome) {
        return metrics.get("commerce.product.list.cache").tags("operation", operation, "outcome", outcome).counter().count();
    }

    private void recordMeasurement(String scenario, TestReporter reporter, Map<String, Object> values) throws IOException {
        values.forEach((name, value) -> reporter.publishEntry(name, String.valueOf(value)));
        Path file = Path.of("build", "reports", "week4", "product-list-cache-outage-" + scenario + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(values));
    }

    private String key(long revision) {
        return NAMESPACE + ":brand=all:sort=likes_desc:page=0:size=20:rev=" + revision;
    }

    private void stopOwnedRedis() {
        REDIS.getDockerClient().stopContainerCmd(REDIS.getContainerId()).withTimeout(1).exec();
        stopped = true;
    }

    private void restartOwnedRedis() {
        if (stopped) {
            REDIS.getDockerClient().startContainerCmd(REDIS.getContainerId()).exec();
            stopped = false;
        }
    }

    private void awaitRedis() {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
            .ignoreExceptionsInstanceOf(org.springframework.dao.DataAccessException.class).untilAsserted(() -> {
                try (var connection = redis.getConnectionFactory().getConnection()) {
                    assertThat(connection.ping()).isEqualTo("PONG");
                }
            });
    }

    static final class OwnedInfrastructureOnly extends TypeExcludeFilter {
        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
            return reader.getClassMetadata().getClassName().startsWith("com.loopers.testcontainers.");
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
