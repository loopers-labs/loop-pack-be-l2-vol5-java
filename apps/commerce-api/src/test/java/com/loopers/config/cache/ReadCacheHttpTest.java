package com.loopers.config.cache;

import com.loopers.like.application.LikeUseCase;
import com.loopers.product.application.ProductUseCase;
import com.loopers.product.domain.ProductSort;
import com.loopers.support.cache.CacheKeys;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.config.redis.RedisConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static com.loopers.support.http.ApiHttp.customer;

@SpringBootTest(properties = "catalog.cache.enabled=true")
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
class ReadCacheHttpTest {
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0")).withExposedPorts(6379);
    static { REDIS.start(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("datasource.redis.master.host", REDIS::getHost);
        r.add("datasource.redis.master.port", REDIS::getFirstMappedPort);
        r.add("datasource.redis.replicas[0].host", REDIS::getHost);
        r.add("datasource.redis.replicas[0].port", REDIS::getFirstMappedPort);
    }
    @Autowired com.loopers.support.cache.CacheVersions versions;
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager tm;
    @MockitoSpyBean com.loopers.support.cache.ResponseCache responseCache;
    @MockitoSpyBean org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoSpyBean com.loopers.support.cache.CachedJson cachedJson;
    @Autowired LikeUseCase likes;
    @Autowired com.loopers.product.interfaces.ProductReadFacade facade;
    @MockitoSpyBean ProductUseCase products;
    @MockitoSpyBean org.springframework.cache.CacheManager cacheManager;
    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis;
    CommerceFixture fixture;
    com.loopers.user.domain.User user;
    com.loopers.product.domain.Product product;
    com.loopers.brand.domain.Brand brand;
    @BeforeEach void setup() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        fixture = new CommerceFixture(em, tm);
        user = fixture.user(); brand = fixture.brand("cache-split"); product = fixture.product(brand, "before", 100L, 1);
        clearInvocations(products);
    }
    @AfterEach void cleanup() { fixture.truncateRemainingTables(); }
    String detail() throws Exception {
        return mvc.perform(get("/api/v1/products/{id}", product.getId()).with(customer(user)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    String key(String bucket, String api, String... namespaces) { return bucket + "::" + versions.key(api, namespaces); }

    /** 준비: A·B 값을 채운다. 실행: B·C를 읽는다. 확인: B 키는 재사용하고 C만 일괄 로드한다. */
    @Test
    @DisplayName("재고와 좋아요 수는 ID 조합이 달라도 상품별 캐시를 재사용한다")
    void reusesIndividualItemsAcrossDifferentBatches() {
        var b = fixture.product(brand, "item-B", 100L, 1);
        var c = fixture.product(brand, "item-C", 100L, 1);
        var ab = java.util.List.of(product.getId(), b.getId());
        var bc = java.util.List.of(b.getId(), c.getId());
        facade.counts(ab); facade.stocks(ab);
        String countB = key(CacheKeys.COUNTS, CacheKeys.item(CacheKeys.COUNTS, b.getId()), CacheKeys.CATALOG_GROUP);
        String stockB = key(CacheKeys.STOCKS, CacheKeys.item(CacheKeys.STOCKS, b.getId()), CacheKeys.CATALOG_GROUP, CacheKeys.stockGroup(b.getId()));
        redis.expire(countB, Duration.ofSeconds(120)); redis.expire(stockB, Duration.ofSeconds(120));
        clearInvocations(products);
        assertThat(facade.counts(bc).data()).extracting(ProductUseCase.LikeCount::productId).containsExactly(b.getId(), c.getId());
        assertThat(facade.stocks(bc).data()).extracting(ProductUseCase.StockStatus::productId).containsExactly(b.getId(), c.getId());
        verify(products, times(1)).findLikeCounts(java.util.List.of(c.getId()));
        verify(products, times(1)).findStockStatuses(java.util.List.of(c.getId()));
        assertThat(redis.getExpire(countB)).isBetween(110L, 120L);
        assertThat(redis.getExpire(stockB)).isBetween(110L, 120L);
    }

    /** 준비: A·B 재고를 캐시한다. 실행: A 재고를 수정한다. 확인: B 키·TTL을 유지하고 A만 재조회한다. */
    @Test
    @DisplayName("A 품절 전환은 A 캐시만 삭제하고 B 재고 캐시를 유지한다")
    void stockInvalidationIsScopedToChangedProduct() throws Exception {
        var b = fixture.product(brand, "unchanged-B", 100L, 1);
        var ids = java.util.List.of(product.getId(), b.getId());
        facade.stocks(ids);
        String stockB = key(CacheKeys.STOCKS, CacheKeys.item(CacheKeys.STOCKS, b.getId()), CacheKeys.CATALOG_GROUP, CacheKeys.stockGroup(b.getId()));
        redis.expire(stockB, Duration.ofSeconds(120));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api-admin/v1/products/" + product.getId() + "/stock")
            .with(com.loopers.support.http.ApiHttp.admin()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
            .contentType("application/json").content("{\"quantity\":0}")).andExpect(status().isOk());
        clearInvocations(products);
        var response = facade.stocks(ids).data();
        assertThat(response).contains(new ProductUseCase.StockStatus(product.getId(), true), new ProductUseCase.StockStatus(b.getId(), false));
        verify(products, times(1)).findStockStatuses(java.util.List.of(product.getId()));
        assertThat(key(CacheKeys.STOCKS, CacheKeys.item(CacheKeys.STOCKS, b.getId()), CacheKeys.CATALOG_GROUP, CacheKeys.stockGroup(b.getId()))).isEqualTo(stockB);
        assertThat(redis.getExpire(stockB)).isBetween(110L, 120L);
    }

    /** 준비: 통합 응답을 캐시한다. 실행: 다시 조회한다. 확인: revision SQL 2회로 버전을 확인하고 원본 조회는 생략한다. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"latest", "price_asc", "likes_desc", "detail", "mine"})
    @DisplayName("통합 상품 hit은 revision SQL 2회와 원본 조회 0회로 같은 응답을 반환한다")
    void combinedHitOnlyQueriesDatabaseVersions(String route) throws Exception {
        likes.register(user.getId(), product.getId());
        String url = switch (route) {
            case "detail" -> "/api/v1/products/" + product.getId();
            case "mine" -> "/api/v1/users/" + user.getId() + "/likes";
            default -> "/api/v1/products?sort=" + route;
        };
        String first = mvc.perform(get(url).with(customer(user))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        clearInvocations(jdbc, products);
        String hit = mvc.perform(get(url).with(customer(user))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(hit).isEqualTo(first);
        org.mockito.Mockito.verify(jdbc, times(2)).query(org.mockito.ArgumentMatchers.contains("read_cache_revision"),
            org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowCallbackHandler.class), org.mockito.ArgumentMatchers.any(Object[].class));
        org.mockito.Mockito.verifyNoInteractions(products);
    }

    /** 준비: 목록과 두 표시 캐시를 채운다. 실행: hit. 확인: 실제 Redis 명령은 GET 1회와 MGET 1회다. */
    @Test
    @DisplayName("통합 목록 hit은 목록 GET 후 좋아요 수와 재고를 MGET 한 번으로 읽는다")
    void readsDisplayCachesInOneRedisCommand() throws Exception {
        String first = mvc.perform(get("/api/v1/products").with(customer(user))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        long gets = redisCommandCalls("get"), multiGets = redisCommandCalls("mget");
        clearInvocations(products);
        String hit = mvc.perform(get("/api/v1/products").with(customer(user))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(hit).isEqualTo(first);
        assertThat(redisCommandCalls("get") - gets).isEqualTo(1);
        assertThat(redisCommandCalls("mget") - multiGets).isEqualTo(1);
        org.mockito.Mockito.verifyNoInteractions(products);
    }

    /** 준비: 통합 응답 캐시를 채운다. 실행: 모든 캐시가 hit인 재조회. 확인: Redis 표시 조회 때 DB 트랜잭션·커넥션 자원이 없다. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"latest", "price_asc", "likes_desc", "detail", "mine"})
    @DisplayName("통합 hit은 DB 트랜잭션 없이 Redis에서 응답을 합친다")
    void hitDoesNotHoldDatabaseTransactionDuringRedisRead(String route) throws Exception {
        likes.register(user.getId(), product.getId());
        String url = switch (route) {
            case "detail" -> "/api/v1/products/" + product.getId();
            case "mine" -> "/api/v1/users/" + user.getId() + "/likes";
            default -> "/api/v1/products?sort=" + route;
        };
        String before = mvc.perform(get(url).with(customer(user))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var observed = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.getResourceMap()).isEmpty();
            observed.incrementAndGet();
            return invocation.callRealMethod();
        }).when(cachedJson).loadMany(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList());
        clearInvocations(products);
        assertThat(mvc.perform(get(url).with(customer(user))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo(before);
        assertThat(observed.get()).isEqualTo(1);
        org.mockito.Mockito.verifyNoInteractions(products);
    }

    /** 준비: 전체 miss. 실행: 목록 조회. 확인: 원본들을 한 트랜잭션에서 읽는다. */
    @Test
    @DisplayName("전체 miss는 트랜잭션에서 원본을 한 번만 조회한다")
    void missLoadsOriginalInsideTransaction() throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return invocation.callRealMethod();
        }).when(cachedJson).loadMany(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList());
        mvc.perform(get("/api/v1/products").with(customer(user))).andExpect(status().isOk());
        verify(products, times(1)).findBasicPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(products, times(1)).findLikeCounts(java.util.List.of(product.getId()));
        verify(products, times(1)).findStockStatuses(java.util.List.of(product.getId()));
    }

    /** 준비: 상품 목록은 hit, 재고만 miss. 실행: 표시 조회 도중 상품 수정 커밋. 확인: 옛 목록을 버리고 새 상품을 함께 반환한다. */
    @Test
    @DisplayName("부분 miss 사이 상품이 변경되면 옛 hit을 버리고 무효화 후 전체 응답을 다시 구성한다")
    void partialMissRechecksInvalidatedBasicProduct() throws Exception {
        mvc.perform(get("/api/v1/products").with(customer(user))).andExpect(status().isOk());
        var ids = java.util.List.of(product.getId());
        redis.delete(key(CacheKeys.STOCKS, CacheKeys.item(CacheKeys.STOCKS, product.getId()), CacheKeys.CATALOG_GROUP, CacheKeys.stockGroup(product.getId())));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            Object values = invocation.callRealMethod();
            if (calls.incrementAndGet() == 1) {
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api-admin/v1/products/" + product.getId())
                    .with(com.loopers.support.http.ApiHttp.admin()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"name\":\"after-probe\",\"price\":200}"))
                    .andExpect(status().isOk());
            }
            return values;
        }).when(cachedJson).loadMany(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList());
        mvc.perform(get("/api/v1/products").with(customer(user))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].name").value("after-probe"))
            .andExpect(jsonPath("$.data.content[0].price").value(200));
        assertThat(calls.get()).isEqualTo(2);
        mvc.perform(get("/api/v1/products").with(customer(user))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].name").value("after-probe"));
    }

    /** 기본 정보 hit 이후 catalog가 바뀌면 서로 다른 버전을 합치지 않고 전체를 다시 읽는다. */
    @Test
    @DisplayName("통합 hit의 두 revision 조회 사이 삭제 commit은 새 스냅샷으로 재조회한다")
    void catalogChangeBetweenRevisionReadsRestartsComposedHit() throws Exception {
        mvc.perform(get("/api/v1/products").with(customer(user))).andExpect(status().isOk());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api-admin/v1/products/" + product.getId())
                    .with(com.loopers.support.http.ApiHttp.admin()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                    .andExpect(status().isOk());
            }
            return invocation.callRealMethod();
        }).when(jdbc).query(org.mockito.ArgumentMatchers.contains("read_cache_revision"),
            org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowCallbackHandler.class), org.mockito.ArgumentMatchers.any(Object[].class));
        mvc.perform(get("/api/v1/products").with(customer(user))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content.length()").value(0))
            .andExpect(jsonPath("$.data.last").value(true));
        assertThat(calls.get()).isEqualTo(3);
    }

    long redisCommandCalls(String command) {
        return redis.execute((org.springframework.data.redis.core.RedisCallback<Long>) connection -> {
            var info = connection.serverCommands().info("commandstats");
            String stat = info.getProperty("cmdstat_" + command, "calls=0,usec=0");
            return Long.parseLong(stat.split(",")[0].substring("calls=".length()));
        });
    }

    /** 준비: 캐시를 채운 뒤 두 표시 키 중 하나만 제거/손상한다. 실행: 목록 조회. 확인: 해당 원본만 읽고 다른 hit의 TTL은 연장하지 않는다. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"like-counts,false", "stocks,false", "like-counts,true", "stocks,true"})
    @DisplayName("MGET의 일부 miss·JSON 손상은 해당 데이터만 원본에서 읽고 나머지 hit을 유지한다")
    void handlesPartialBatchMiss(String bucket, boolean corrupt) throws Exception {
        String url = "/api/v1/products";
        String first = mvc.perform(get(url).with(customer(user))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var ids = java.util.List.of(product.getId());
        String countsKey = key(CacheKeys.COUNTS, CacheKeys.item(CacheKeys.COUNTS, product.getId()), CacheKeys.CATALOG_GROUP);
        String stocksKey = key(CacheKeys.STOCKS, CacheKeys.item(CacheKeys.STOCKS, product.getId()), CacheKeys.CATALOG_GROUP, CacheKeys.stockGroup(product.getId()));
        String missing = bucket.equals(CacheKeys.COUNTS) ? countsKey : stocksKey;
        String kept = bucket.equals(CacheKeys.COUNTS) ? stocksKey : countsKey;
        redis.expire(kept, Duration.ofSeconds(120));
        if (corrupt) redis.opsForValue().set(missing, "{broken");
        else redis.delete(missing);
        clearInvocations(products);
        String response = mvc.perform(get(url).with(customer(user))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).isEqualTo(first);
        verify(products, times(bucket.equals(CacheKeys.COUNTS) ? 1 : 0)).findLikeCounts(ids);
        verify(products, times(bucket.equals(CacheKeys.STOCKS) ? 1 : 0)).findStockStatuses(ids);
        verify(products, times(0)).findBasicPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(redis.getExpire(kept)).isBetween(110L, 120L);
        if (corrupt) {
            assertThat(redis.hasKey(missing)).isFalse();
            mvc.perform(get(url).with(customer(user))).andExpect(status().isOk());
        }
        assertThat(redis.getExpire(missing)).isBetween(269L, 330L);
    }

    /** 20개 목록의 miss는 각 일괄 조회 1회, hit은 원본 호출 0회. 재고 변경은 상품 캐시를 재사용한다. */
    @Test
    @DisplayName("통합 목록은 20개를 일괄 합성하고 재고 변경 후에도 기본 정보 캐시를 재사용한다")
    void combinesTwentyProductsWithoutPerProductReads() throws Exception {
        var ids = new java.util.ArrayList<Long>();
        ids.add(product.getId());
        for (int i = 1; i < 20; i++) ids.add(fixture.product(brand, "batch-" + i, 100L, 1).getId());
        ids.sort(Long::compareTo);
        mvc.perform(get("/api/v1/products").with(customer(user))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content.length()").value(20));
        verify(products, times(1)).findLikeCounts(ids);
        verify(products, times(1)).findStockStatuses(ids);
        verify(products, times(0)).findBasicProduct(org.mockito.ArgumentMatchers.anyLong());
        clearInvocations(products);
        mvc.perform(get("/api/v1/products").with(customer(user))).andExpect(status().isOk());
        verify(products, times(0)).findBasicPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(products, times(0)).findLikeCounts(org.mockito.ArgumentMatchers.anyList());
        verify(products, times(0)).findStockStatuses(org.mockito.ArgumentMatchers.anyList());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api-admin/v1/products/" + product.getId() + "/stock")
            .with(com.loopers.support.http.ApiHttp.admin()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"quantity\":0}")).andExpect(status().isOk());
        String response = mvc.perform(get("/api/v1/products").with(customer(user))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var content = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data").path("content");
        assertThat(java.util.stream.StreamSupport.stream(content.spliterator(), false)
            .filter(item -> item.path("id").asLong() == product.getId()).findFirst().orElseThrow().path("soldOut").asBoolean()).isTrue();
        verify(products, times(0)).findBasicPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(products, times(0)).findLikeCounts(org.mockito.ArgumentMatchers.anyList());
        verify(products, times(1)).findStockStatuses(java.util.List.of(product.getId()));
    }

    /**
     * 준비: 기본 목록을 조회해 캐시를 채운다.
     * 실행: 동일한 기본값을 명시해 재조회하고 사용자 헤더 없이도 요청한다.
     * 확인: 원본 목록 조회는 1회이며, 헤더 없는 요청은 401이고 TTL 범위도 유지된다.
     */
    @Test
    @DisplayName("동일한 목록 조건은 캐시를 공유하지만 사용자 헤더 없는 요청은 401이다")
    void cachesCanonicalListAndStillAuthenticatesHit() throws Exception {
        mvc.perform(get("/api/v1/products").with(customer(user))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/products?sort=latest&page=0&size=20").with(customer(user))).andExpect(status().isOk());
        verify(products, times(1)).findBasicPage(null, null, 0, 20);
        verify(products, times(0)).findBasicPage(null, ProductSort.LATEST, 0, 20);
        mvc.perform(get("/api/v1/products")).andExpect(status().isUnauthorized());
        assertThat(redis.getExpire(key(CacheKeys.PRODUCTS, CacheKeys.products("latest", null, 0, 20), CacheKeys.CATALOG_GROUP))).isBetween(269L, 330L);
    }

    /** 같은 fixture·HTTP 입력에서 캐시 비활성 → miss → hit을 비교한다. 읽기 사이에는 쓰지 않는다. */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}, brand filter={1}")
    @org.junit.jupiter.params.provider.CsvSource({
        "latest,false", "latest,true", "price_asc,false", "price_asc,true", "likes_desc,false", "likes_desc,true"
    })
    @DisplayName("[R-CATALOG-02/04/05] DB-only·miss·hit의 정렬·페이지·응답은 같고 hit은 원본 조회를 생략한다")
    void databaseOnlyMissAndHitReturnSamePages(String sort, boolean filtered) throws Exception {
        var second = fixture.product(brand, "second", 100L, 0);
        var third = fixture.product(brand, "third", 200L, 1);
        var other = fixture.product(fixture.brand("other"), "other-product", 50L, 1);
        fixture.deletedProduct(brand, "deleted", 1L);
        fixture.like(user, second);
        fixture.like(user, third);
        fixture.like(user, other);
        Long brandId = filtered ? brand.getId() : null;
        ProductSort requested = ProductSort.valueOf(sort.toUpperCase(java.util.Locale.ROOT));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var collected = new java.util.ArrayList<Long>();
        for (int page = 0; page < 3; page++) {
            String url = "/api/v1/products?sort=" + sort + "&page=" + page + "&size=2"
                + (filtered ? "&brandId=" + brandId : "");
            String bucket = sort.equals("likes_desc") ? CacheKeys.RANKINGS : CacheKeys.PRODUCTS;
            String cacheKey = key(bucket, CacheKeys.products(sort, brandId, page, 2), CacheKeys.CATALOG_GROUP);
            assertThat(redis.hasKey(cacheKey)).isFalse();
            // Exercise ResponseCache's real disabled branch; only the feature switch is stubbed.
            org.mockito.Mockito.doReturn(false).when(responseCache).isEnabled();
            clearInvocations(products);
            String databaseOnly;
            try {
                databaseOnly = mvc.perform(get(url).with(customer(user))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
                verify(products, times(1)).findBasicPage(brandId, requested, page, 2);
                assertThat(redis.hasKey(cacheKey)).as("DB-only must not populate Redis").isFalse();
            } finally {
                org.mockito.Mockito.doCallRealMethod().when(responseCache).isEnabled();
            }
            clearInvocations(products);
            String miss = mvc.perform(get(url).with(customer(user))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            verify(products, times(1)).findBasicPage(brandId, requested, page, 2);
            assertThat(redis.hasKey(cacheKey)).isTrue();
            assertThat(redis.getExpire(cacheKey)).isBetween(269L, 330L);
            clearInvocations(products);
            String hit = mvc.perform(get(url).with(customer(user))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            verify(products, times(0)).findBasicPage(brandId, requested, page, 2);
            assertThat(mapper.readTree(miss)).isEqualTo(mapper.readTree(databaseOnly));
            assertThat(mapper.readTree(hit)).isEqualTo(mapper.readTree(databaseOnly));
            var data = mapper.readTree(hit).path("data");
            assertThat(data.path("page").asInt()).isEqualTo(page);
            assertThat(data.path("size").asInt()).isEqualTo(2);
            assertThat(data.path("last").asBoolean()).isEqualTo(page >= 1);
            for (var item : data.path("content")) collected.add(item.path("id").asLong());
        }
        var expected = new java.util.ArrayList<>(java.util.List.of(product.getId(), second.getId(), third.getId()));
        if (!filtered) expected.add(other.getId());
        assertThat(collected).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
    }

    /**
     * 준비: 상품 상세 키에 만료 없는 잘못된 JSON을 저장한다.
     * 실행: 상세를 조회하고 한 번 더 조회한다.
     * 확인: 첫 조회는 DB 응답을 반환하고 깨진 키를 지우며, 다음 조회는 정상 JSON을 저장한다.
     */
    @Test
    @DisplayName("깨진 JSON 캐시는 제거하고 원본 조회 뒤 정상 캐시로 다시 채운다")
    void malformedNonExpiringCacheIsRemovedAndRefilled() throws Exception {
        String key = key(CacheKeys.PRODUCTS, CacheKeys.product(product.getId()), CacheKeys.CATALOG_GROUP);
        redis.opsForValue().set(key, "broken JSON");
        assertThat(detail()).contains("before");
        assertThat(redis.hasKey(key)).isFalse();
        detail();
        assertThat(redis.opsForValue().get(key)).contains("before");
    }

    /**
     * 준비: 상세 캐시를 채우고 캐시 get·put이 예외를 던지게 한다.
     * 실행: 상품을 수정한 뒤 조회하고 삭제한 뒤 다시 조회한다.
     * 확인: 수정된 이름과 삭제 후 404를 반환한다.
     */
    @Test
    @DisplayName("Redis 읽기·저장 오류가 나면 현재 DB 결과로 응답한다")
    void redisReadAndWriteFailuresFallBackToCurrentDatabaseResult() throws Exception {
        detail();
        var broken = org.mockito.Mockito.mock(org.springframework.cache.Cache.class);
        org.mockito.Mockito.when(broken.get(org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("Redis down"));
        org.mockito.Mockito.doThrow(new IllegalStateException("Redis down")).when(broken)
            .put(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doReturn(broken).when(cacheManager).getCache(CacheKeys.PRODUCTS);
        products.update(product.getId(), "during outage", 400L, null);
        assertThat(detail()).contains("during outage");
        products.delete(product.getId());
        mvc.perform(get("/api/v1/products/{id}", product.getId()).with(customer(user))).andExpect(status().isNotFound());
    }

    /**
     * 준비: 상세 캐시의 남은 TTL을 120초로 줄인다.
     * 실행: 같은 상세를 다시 조회한다.
     * 확인: 응답이 같고 원본 상세 조회는 0회이며 남은 TTL은 119~120초다.
     */
    @Test
    @DisplayName("상품 상세 cache hit은 DB 원본을 다시 읽거나 TTL을 연장하지 않는다")
    void cacheHitDoesNotRenewTheRemainingTtl() throws Exception {
        String original = detail();
        String key = key(CacheKeys.PRODUCTS, CacheKeys.product(product.getId()), CacheKeys.CATALOG_GROUP);
        redis.expire(key, Duration.ofSeconds(120));
        clearInvocations(products);
        assertThat(detail()).isEqualTo(original);
        verify(products, times(0)).findBasicProduct(product.getId());
        assertThat(redis.getExpire(key)).isBetween(119L, 120L);
    }

    /**
     * 준비: 설정된 모든 캐시 이름을 순회한다.
     * 실행: 각 캐시에 값을 저장한다.
     * 확인: Redis 잔여 TTL이 269~330초다. 초 단위 경과를 1초 허용한다.
     */
    @Test
    @DisplayName("모든 캐시 종류에 5분 ±30초 TTL 정책이 적용된다")
    void everyCacheBucketUsesExpiringJitteredEntries() {
        for (String bucket : CacheKeys.NAMES) {
            cacheManager.getCache(bucket).put("ttl-policy-test", "value");
            assertThat(redis.getExpire(bucket + "::ttl-policy-test")).as(bucket).isBetween(269L, 330L);
        }
    }

    // 캐시 조회 시나리오 12가지. 목록 6가지 + 상세·내 좋아요·좋아요 수·재고와 고객·관리자 브랜드 목록.
    java.util.List<ReadRoute> readRoutes() {
        var routes = new java.util.ArrayList<ReadRoute>();
        for (String sort : java.util.List.of("latest", "price_asc", "likes_desc")) {
            for (Long filter : java.util.Arrays.asList(null, brand.getId())) {
                String url = "/api/v1/products?sort=" + sort + (filter == null ? "" : "&brandId=" + filter);
                routes.add(new ReadRoute(url, "likes_desc".equals(sort) ? CacheKeys.RANKINGS : CacheKeys.PRODUCTS,
                    CacheKeys.products(sort, filter, 0, 20), new String[]{CacheKeys.CATALOG_GROUP}));
            }
        }
        routes.add(new ReadRoute("/api/v1/products/" + product.getId(), CacheKeys.PRODUCTS,
            CacheKeys.product(product.getId()), new String[]{CacheKeys.CATALOG_GROUP}));
        routes.add(new ReadRoute("/api/v1/users/" + user.getId() + "/likes", CacheKeys.MY_LIKES,
            CacheKeys.myLikes(user.getId(), 0, 20), new String[]{CacheKeys.CATALOG_GROUP, CacheKeys.userGroup(user.getId())}));
        routes.add(new ReadRoute("/api-admin/v1/brands", CacheKeys.BRANDS,
            CacheKeys.brands(0, 20), new String[]{CacheKeys.CATALOG_GROUP}));
        routes.add(new ReadRoute("/api/v1/brands", CacheKeys.BRANDS,
            CacheKeys.customerBrands(0, 20), new String[]{CacheKeys.CATALOG_GROUP}));
        for (String bucket : java.util.List.of(CacheKeys.COUNTS, CacheKeys.STOCKS)) {
            routes.add(new ReadRoute("/api/v1/products/" + product.getId(), bucket,
                CacheKeys.item(bucket, product.getId()), bucket.equals(CacheKeys.STOCKS)
                    ? new String[]{CacheKeys.CATALOG_GROUP, CacheKeys.stockGroup(product.getId())} : new String[]{CacheKeys.CATALOG_GROUP}));
        }
        return routes;
    }

    String read(ReadRoute route) throws Exception {
        return mvc.perform(get(route.url()).with(route.url().startsWith("/api-admin")
                ? com.loopers.support.http.ApiHttp.admin() : customer(user)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    record ReadRoute(String url, String bucket, String apiKey, String[] namespaces) { }

    /**
     * 준비: 목록 3종 × 브랜드 필터 유무, 상세, 내 좋아요, 브랜드 목록, 숫자·재고를 조회한다.
     * 실행: 각 키의 TTL을 120초로 줄여 hit을 확인한 뒤 1ms로 줄여 실제 만료시킨다.
     * 확인: hit은 같은 응답과 잔여 TTL을 유지하고, 만료 뒤에는 같은 응답을 새 TTL로 채운다.
     */
    @Test
    @DisplayName("조회 12가지 모두 TTL을 지키고 hit 때 비연장하며 만료 후 재적재한다")
    void allReadVariantsExpireAndHitsNeverExtendTtl() throws Exception {
        for (ReadRoute route : readRoutes()) {
            String original = read(route);
            String cacheKey = key(route.bucket(), route.apiKey(), route.namespaces());
            assertThat(redis.getExpire(cacheKey)).as(route.url() + " initial TTL").isBetween(269L, 330L);
            redis.expire(cacheKey, Duration.ofSeconds(120));
            assertThat(read(route)).as(route.url() + " hit response").isEqualTo(original);
            assertThat(redis.getExpire(cacheKey)).as(route.url() + " hit TTL").isBetween(119L, 120L);
            redis.expire(cacheKey, Duration.ofMillis(1));
            Thread.sleep(10);
            assertThat(redis.hasKey(cacheKey)).as(route.url() + " expired").isFalse();
            assertThat(read(route)).as(route.url() + " refill response").isEqualTo(original);
            assertThat(redis.getExpire(cacheKey)).as(route.url() + " refill TTL").isBetween(269L, 330L);
        }
    }

    @Test
    @DisplayName("좋아요 0개 캐시는 등록 직후 0을 유지하고 만료 후 1을 반환한다")
    void zeroLikeCountRefreshesOnlyAfterExpiry() throws Exception {
        String url = "/api/v1/products/" + product.getId();
        mvc.perform(get(url).with(customer(user))).andExpect(status().isOk()).andExpect(jsonPath("$.data.likeCount").value(0));
        String cacheKey = key(CacheKeys.COUNTS, CacheKeys.item(CacheKeys.COUNTS, product.getId()), CacheKeys.CATALOG_GROUP);
        assertThat(redis.getExpire(cacheKey)).isBetween(269L, 330L);
        likes.register(user.getId(), product.getId());
        mvc.perform(get(url).with(customer(user))).andExpect(status().isOk()).andExpect(jsonPath("$.data.likeCount").value(0));
        redis.expire(cacheKey, Duration.ofMillis(1));
        Thread.sleep(10);
        mvc.perform(get(url).with(customer(user))).andExpect(status().isOk()).andExpect(jsonPath("$.data.likeCount").value(1));
    }

    @Test
    @DisplayName("본인 좋아요 목록이 캐시되어 있어도 다른 사용자의 접근은 404다")
    void ownLikesCacheDoesNotBypassOwnership() throws Exception {
        likes.register(user.getId(), product.getId());
        String url = "/api/v1/users/" + user.getId() + "/likes";
        mvc.perform(get(url).with(customer(user))).andExpect(status().isOk()).andExpect(jsonPath("$.data.content.length()").value(1));
        mvc.perform(get(url).with(customer(fixture.user()))).andExpect(status().isNotFound());
        mvc.perform(get(url).with(customer(user))).andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].id").value(product.getId()));
    }

    @Test
    @DisplayName("관리자 브랜드 목록이 캐시되어 있어도 고객의 접근은 403이다")
    void adminCacheDoesNotBypassRole() throws Exception {
        mvc.perform(get("/api-admin/v1/brands").with(com.loopers.support.http.ApiHttp.admin())).andExpect(status().isOk());
        mvc.perform(get("/api-admin/v1/brands").with(customer(user))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("고객·관리자 브랜드 목록은 캐시 키와 응답을 분리하고 고객 hit에도 사용자 헤더를 요구한다")
    void customerAndAdminBrandKeysAreSeparate() throws Exception {
        mvc.perform(get("/api-admin/v1/brands").with(com.loopers.support.http.ApiHttp.admin())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/brands").with(customer(user)))
            .andExpect(com.loopers.support.http.ApiHttp.success(org.springframework.http.HttpStatus.OK))
            .andExpect(jsonPath("$.data.content[0].deleted").doesNotExist());
        String customerKey = key(CacheKeys.BRANDS, CacheKeys.customerBrands(0, 20), CacheKeys.CATALOG_GROUP);
        String adminKey = key(CacheKeys.BRANDS, CacheKeys.brands(0, 20), CacheKeys.CATALOG_GROUP);
        assertThat(customerKey).isNotEqualTo(adminKey);
        assertThat(redis.hasKey(customerKey)).isTrue();
        assertThat(redis.hasKey(adminKey)).isTrue();
        redis.expire(customerKey, Duration.ofSeconds(120));
        mvc.perform(get("/api/v1/brands?page=0&size=20").with(customer(user))).andExpect(status().isOk());
        assertThat(redis.getExpire(customerKey)).isBetween(119L, 120L);
        mvc.perform(get("/api/v1/brands")).andExpect(status().isUnauthorized());
    }

}
