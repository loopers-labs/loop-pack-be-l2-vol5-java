package com.loopers.application.product;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductListRevisionRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSort;
import com.loopers.infrastructure.product.cache.ProductListCacheConfiguration;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.support.AdminMockMvc;
import com.loopers.testcontainers.RedisTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "commerce.product-list-cache.enabled=true")
@AutoConfigureMockMvc
class ProductListCacheMutationApiTest {
    private static final String NAMESPACE = "commerce:test:mutation-api:" + UUID.randomUUID() + ":products:v1";
    private static final String PAGE = "/api/v1/products?sort=likes_desc&page=0&size=20";

    @Autowired private TestRestTemplate http;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private ProductListRevisionRepository revisions;
    @Autowired private ProductListReader reader;
    @Autowired private FixtureUserInitializer users;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private MeterRegistry metrics;
    @Autowired @Qualifier(ProductListCacheConfiguration.TEMPLATE) private StringRedisTemplate redis;

    private long brandId;
    private long productId;
    private long orderId;

    @DynamicPropertySource
    static void cacheNamespace(DynamicPropertyRegistry registry) {
        // 캐시 설정 바인딩 전에 기존 공유 테스트 Redis fixture의 실제 endpoint를 확정한다.
        new RedisTestContainersConfig();
        for (String name : List.of("datasource.redis.database", "datasource.redis.master.host",
            "datasource.redis.master.port", "datasource.redis.replicas[0].host", "datasource.redis.replicas[0].port")) {
            String value = System.getProperty(name);
            registry.add(name, () -> value);
        }
        registry.add("commerce.product-list-cache.namespace", () -> NAMESPACE);
    }

    @BeforeEach
    void setUp() {
        clearOwnKeys();
        users.initialize();
        Brand brand = brands.save(new Brand("브랜드"));
        brandId = brand.getId();
        productId = products.save(new Product(brand, "상품", 100, 5)).getId();
        products.save(new Product(brand, "품절 상품", 200, 0));
        products.save(new Product(brands.save(new Brand("다른 브랜드")), "다른 상품", 300, 7));
        success(call(HttpMethod.POST, "/api/v1/points/charge", "alice", "{\"amount\":1000}"), 200);
        orderId = success(call(HttpMethod.POST, "/api/v1/orders", "alice", orderBody()), 201).path("orderId").asLong();
    }

    @AfterEach
    void tearDown() {
        try {
            clearOwnKeys();
        } finally {
            cleanup.truncateAllTables();
        }
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void everyCommittedHttpMutationRebuildsTheFullCustomerPage(Mutation mutation) {
        if (mutation == Mutation.CANCEL_LIKE) {
            success(call(HttpMethod.POST, likePath(), "alice", null), 200);
        }
        long oldRevision = revisions.current();
        PageResult<ProductInfo> oldPage = warmAndAssertHit();
        String oldPayload = redis.opsForValue().get(key(oldRevision));

        mutate(mutation);

        assertThat(revisions.current()).isEqualTo(oldRevision + 1);
        assertThat(redis.opsForValue().get(key(oldRevision))).isEqualTo(oldPayload);
        PageResult<ProductInfo> actual = currentPage();
        assertThat(actual).isEqualTo(freshPage()).isNotEqualTo(oldPage);
        assertThat(redis.hasKey(key(revisions.current()))).isTrue();
        double hitsBefore = hits();
        assertThat(currentPage()).isEqualTo(actual);
        assertThat(hits()).isEqualTo(hitsBefore + 1);
    }

    @Test
    void successfulNoOpsReuseTheSameStoredKeyAndDoNotExtendItsExpiry() {
        success(call(HttpMethod.POST, likePath(), "alice", null), 200);
        PageResult<ProductInfo> before = warmAndAssertHit();
        long revision = revisions.current();
        String payload = redis.opsForValue().get(key(revision));
        long ttl = redis.getExpire(key(revision), TimeUnit.MILLISECONDS);

        success(call(HttpMethod.PUT, productPath(), "admin", "{\"name\":\" 상품 \",\"price\":100}"), 200);
        success(call(HttpMethod.PUT, productPath() + "/stock", "admin", "{\"stockQuantity\":5}"), 200);
        success(call(HttpMethod.PUT, brandPath(), "admin", "{\"name\":\" 브랜드 \"}"), 200);
        success(call(HttpMethod.POST, likePath(), "alice", null), 200);
        success(call(HttpMethod.DELETE, likePath(), "bob", null), 200);

        assertThat(revisions.current()).isEqualTo(revision);
        double hitsBefore = hits();
        assertThat(currentPage()).isEqualTo(before).isEqualTo(freshPage());
        assertThat(hits()).isEqualTo(hitsBefore + 1);
        assertThat(redis.opsForValue().get(key(revision))).isEqualTo(payload);
        assertThat(redis.getExpire(key(revision), TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(ttl);
        assertThat(redis.keys(NAMESPACE + ":*")).containsExactly(key(revision));
    }

    @Test
    void aLikeOutsideTheCachedPageChangesTheGlobalRankingAndCancellationRestoresIt() {
        Brand brand = brands.findById(brandId).orElseThrow();
        for (int index = 0; index < 22; index++) {
            products.save(new Product(brand, "후속 상품 " + index, 1000, 5));
        }
        PageResult<ProductInfo> before = warmAndAssertHit();
        assertThat(before.totalElements()).isEqualTo(25);
        assertThat(before.items()).extracting(ProductInfo::productId).doesNotContain(productId);

        success(call(HttpMethod.POST, likePath(), "alice", null), 200);
        PageResult<ProductInfo> liked = currentPage();

        assertThat(liked).isEqualTo(freshPage());
        assertThat(liked.items()).hasSize(20);
        assertThat(liked.items().getFirst().productId()).isEqualTo(productId);
        assertThat(liked.items().getFirst().likeCount()).isEqualTo(1);
        assertThat(liked.totalElements()).isEqualTo(25);
        success(call(HttpMethod.DELETE, likePath(), "alice", null), 200);
        assertThat(currentPage()).isEqualTo(before).isEqualTo(freshPage());
    }

    @Test
    void brandDeletionHidesCachedProductsButPreservesHistoricalOrdersAndAccessRules() {
        JsonNode confirmed = success(call(HttpMethod.POST, "/api/v1/orders/" + orderId + "/confirm", "alice", null), 200);
        long pendingOrder = success(call(HttpMethod.POST, "/api/v1/orders", "alice", orderBody()), 201)
            .path("orderId").asLong();
        success(call(HttpMethod.POST, likePath(), "alice", null), 200);
        warmAndAssertHit();
        long oldRevision = revisions.current();

        success(call(HttpMethod.DELETE, brandPath(), "admin", null), 200);

        assertThat(redis.hasKey(key(oldRevision))).isTrue();
        PageResult<ProductInfo> after = currentPage();
        assertThat(after).isEqualTo(freshPage());
        assertThat(after.totalElements()).isEqualTo(1);
        assertThat(after.items()).noneMatch(product -> product.brand().brandId() == brandId);
        assertThat(success(call(HttpMethod.GET, "/api/v1/orders/" + orderId, "alice", null), 200)).isEqualTo(confirmed);
        error(call(HttpMethod.GET, "/api/v1/orders/" + orderId, "bob", null), 404, "ORDER_NOT_FOUND");
        error(call(HttpMethod.POST, "/api/v1/orders", "alice", orderBody()), 404, "PRODUCT_NOT_FOUND");
        error(call(HttpMethod.POST, "/api/v1/orders/" + pendingOrder + "/confirm", "alice", null), 404, "PRODUCT_NOT_FOUND");
        error(call(HttpMethod.POST, likePath(), "bob", null), 404, "PRODUCT_NOT_FOUND");
        long deletedRevision = revisions.current();
        success(call(HttpMethod.DELETE, likePath(), "alice", null), 200);
        success(call(HttpMethod.DELETE, brandPath(), "admin", null), 200);
        assertThat(revisions.current()).isEqualTo(deletedRevision);
        assertThat(currentPage()).isEqualTo(after);
    }

    @Test
    void anEmptyCachedPageStopsBeingEmptyAfterAnHttpProductCreation() {
        success(call(HttpMethod.DELETE, brandPath(), "admin", null), 200);
        PageResult<ProductInfo> remaining = freshPage();
        long otherBrand = remaining.items().getFirst().brand().brandId();
        success(call(HttpMethod.DELETE, "/api-admin/v1/brands/" + otherBrand, "admin", null), 200);
        assertThat(warmAndAssertHit().items()).isEmpty();
        long newBrand = success(call(HttpMethod.POST, "/api-admin/v1/brands", "admin", "{\"name\":\"새 브랜드\"}"), 201)
            .path("brandId").asLong();

        success(call(HttpMethod.POST, "/api-admin/v1/products", "admin",
            "{\"brandId\":" + newBrand + ",\"name\":\"새 상품\",\"price\":100,\"stockQuantity\":2}"), 201);

        PageResult<ProductInfo> page = currentPage();
        assertThat(page).isEqualTo(freshPage());
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
    }

    private void mutate(Mutation mutation) {
        ResponseEntity<JsonNode> response = switch (mutation) {
            case CREATE_PRODUCT -> call(HttpMethod.POST, "/api-admin/v1/products", "admin",
                "{\"brandId\":" + brandId + ",\"name\":\"새 상품\",\"price\":500,\"stockQuantity\":4}");
            case UPDATE_PRODUCT -> call(HttpMethod.PUT, productPath(), "admin", "{\"name\":\"변경 상품\",\"price\":500}");
            case CHANGE_STOCK -> call(HttpMethod.PUT, productPath() + "/stock", "admin", "{\"stockQuantity\":9}");
            case DELETE_PRODUCT -> call(HttpMethod.DELETE, productPath(), "admin", null);
            case RENAME_BRAND -> call(HttpMethod.PUT, brandPath(), "admin", "{\"name\":\"변경 브랜드\"}");
            case DELETE_BRAND -> call(HttpMethod.DELETE, brandPath(), "admin", null);
            case REGISTER_LIKE -> call(HttpMethod.POST, likePath(), "alice", null);
            case CANCEL_LIKE -> call(HttpMethod.DELETE, likePath(), "alice", null);
            case CONFIRM_ORDER -> call(HttpMethod.POST, "/api/v1/orders/" + orderId + "/confirm", "alice", null);
        };
        success(response, mutation == Mutation.CREATE_PRODUCT ? 201 : 200);
    }

    private PageResult<ProductInfo> warmAndAssertHit() {
        PageResult<ProductInfo> first = currentPage();
        assertThat(first).isEqualTo(freshPage());
        assertThat(redis.hasKey(key(revisions.current()))).isTrue();
        double before = hits();
        assertThat(currentPage()).isEqualTo(first);
        assertThat(hits()).isEqualTo(before + 1);
        return first;
    }

    private PageResult<ProductInfo> currentPage() {
        JsonNode data = success(call(HttpMethod.GET, PAGE, null, null), 200);
        return mapper.convertValue(data, new TypeReference<PageResult<ProductInfo>>() { });
    }

    private PageResult<ProductInfo> freshPage() {
        return reader.readList(null, 0, 20, ProductSort.LIKES_DESC);
    }

    private double hits() {
        return metrics.get("commerce.product.list.cache").tags("operation", "lookup", "outcome", "hit").counter().count();
    }

    private JsonNode success(ResponseEntity<JsonNode> response, int status) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        assertThat(response.getBody().has("data")).isTrue();
        return response.getBody().path("data");
    }

    private void error(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("FAIL");
        assertThat(response.getBody().path("meta").path("errorCode").asText()).isEqualTo(code);
        assertThat(response.getBody().has("data")).isFalse();
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, String requester, String body) {
        if (path.startsWith("/api-admin/")) {
            return AdminMockMvc.exchange(mvc, method, path, requester, body);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (requester != null) {
            headers.set("X-USER-ID", requester);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private void clearOwnKeys() {
        Set<String> keys = redis.keys(NAMESPACE + ":*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private String key(long revision) {
        return NAMESPACE + ":brand=all:sort=likes_desc:page=0:size=20:rev=" + revision;
    }

    private String productPath() {
        return "/api-admin/v1/products/" + productId;
    }

    private String brandPath() {
        return "/api-admin/v1/brands/" + brandId;
    }

    private String likePath() {
        return "/api/v1/products/" + productId + "/likes";
    }

    private String orderBody() {
        return "{\"items\":[{\"productId\":" + productId + ",\"quantity\":2}]}";
    }

    enum Mutation {
        CREATE_PRODUCT, UPDATE_PRODUCT, CHANGE_STOCK, DELETE_PRODUCT, RENAME_BRAND,
        DELETE_BRAND, REGISTER_LIKE, CANCEL_LIKE, CONFIRM_ORDER
    }
}
