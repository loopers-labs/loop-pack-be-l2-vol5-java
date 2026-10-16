package com.loopers.application.product;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.like.LikeFacade;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.product.PopularPage;
import com.loopers.infrastructure.product.ProductRepositoryImpl;
import com.loopers.infrastructure.product.RedisPopularPageCache;
import com.loopers.support.error.CoreException;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"commerce.cache.popular.enabled=true", "commerce.cache.popular.prefix=popular-cache-it",
    "commerce.cache.price.enabled=true", "commerce.cache.price.prefix=likes-price-cache-it"})
@AutoConfigureMockMvc
class PopularPageCacheIntegrationTest {
    private static final int REDIS_PORT = freePort();
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0.15"))
        .withExposedPorts(6379)
        .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
            new PortBinding(Ports.Binding.bindPort(REDIS_PORT), new ExposedPort(6379))));
    static { REDIS.start(); }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); }
        catch (IOException exception) { throw new UncheckedIOException(exception); }
    }

    @DynamicPropertySource
    static void redisEndpoint(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> REDIS_PORT);
        registry.add("datasource.redis.replicas[0].host", REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> REDIS_PORT);
    }

    @Autowired private ProductFacade products;
    @Autowired private ProductQueryService query;
    @Autowired private ProductListQueryService lists;
    @Autowired private BrandFacade brands;
    @Autowired private LikeFacade likes;
    @Autowired private UserRegistrationService users;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private TransactionTemplate transaction;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired @Qualifier("productCacheRedisTemplate") private StringRedisTemplate redis;
    @MockitoSpyBean private ProductRepositoryImpl repository;
    @MockitoSpyBean private RedisPopularPageCache cache;

    @AfterEach
    void clean() {
        reset(repository, cache);
        try {
            var keys = redis.keys("likes-*-it:*");
            if (keys != null && !keys.isEmpty()) { redis.delete(keys); }
            var ranking = redis.keys("popular-cache-it:*");
            if (ranking != null && !ranking.isEmpty()) { redis.delete(ranking); }
        } finally { cleanUp.truncateAllTables(); }
    }

    @AfterAll
    static void stopRedis() { REDIS.stop(); }

    @Test
    void sharesOnlyIdsAndKeepsPersonalFlagsSeparateOnHits() {
        long brand = brands.create("brand", null).id();
        long first = product(brand);
        long second = product(brand);
        sameTime(brand);
        long a = users.register().getId();
        long b = users.register().getId();
        likes.register(a, first);
        likes.register(b, second);
        var pageA = lists.getList(a, brand, 0, 20, ProductSort.POPULAR);
        assertThat(pageA.items()).extracting(item -> item.product().id()).containsExactly(second, first);
        assertThat(pageA.items()).extracting(ProductListItemInfo::isLiked).containsExactly(false, true);
        clearInvocations(repository);
        var pageB = lists.getList(b, brand, 0, 20, ProductSort.POPULAR);
        assertThat(pageB.items()).extracting(ProductListItemInfo::isLiked).containsExactly(true, false);
        assertThat(pageB.items()).extracting(item -> item.product().likeCount()).containsExactly(1L, 1L);
        verify(repository, never()).findVisibleIdsByLikesDesc(brand, 0, 20);
        verify(repository).findActiveByIds(List.of(second, first));
        assertThat(redis.opsForValue().get(key(brand)))
            .doesNotContain("isLiked", "userId", "likeCount", "price", "name");
        assertThat(cache.get(brand)).contains(new PopularPage(List.of(second, first)));
        assertThat(redis.getExpire(key(brand))).isBetween(1L, 60L);
    }

    @Test
    void actualAndIdempotentLikesKeepRankingAndDoNotExtendExpiry() throws Exception {
        long brand = brands.create("brand", null).id();
        long first = product(brand);
        long second = product(brand);
        long user = users.register().getId();
        sameTime(brand);
        ranked(brand);
        String before = redis.opsForValue().get(key(brand));
        assertThat(redis.expire(key(brand), Duration.ofSeconds(15))).isTrue();
        clearInvocations(cache);
        mvc.perform(post("/api/v1/products/{id}/likes", first).header("X-USER-ID", user))
            .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/products").header("X-USER-ID", user).param("brandId", String.valueOf(brand))
                .param("sort", "popular"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(second))
            .andExpect(jsonPath("$.data.items[1].id").value(first))
            .andExpect(jsonPath("$.data.items[1].isLiked").value(true))
            .andExpect(jsonPath("$.data.items[1].likeCount").value(1))
            .andExpect(jsonPath("$.data.totalCount").doesNotExist());
        mvc.perform(post("/api/v1/products/{id}/likes", first).header("X-USER-ID", user))
            .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/products/{id}/likes", first).header("X-USER-ID", user))
            .andExpect(status().isNoContent());
        var after = lists.getList(user, brand, 0, 20, ProductSort.POPULAR);
        assertThat(after.items()).extracting(item -> item.product().id()).containsExactly(second, first);
        assertThat(after.items()).extracting(ProductListItemInfo::isLiked).containsExactly(false, false);
        mvc.perform(delete("/api/v1/products/{id}/likes", first).header("X-USER-ID", user))
            .andExpect(status().isNoContent());
        assertThat(redis.opsForValue().get(key(brand))).isEqualTo(before);
        assertThat(redis.getExpire(key(brand), TimeUnit.MILLISECONDS)).isBetween(1L, 15_000L);
        verify(cache, never()).evict(anyLong());
        verify(cache, never()).put(anyLong(), any());
    }

    @Test
    void outsideLikesEnterPopularOnlyAfterExpiryButLikesDescChangesImmediately() throws Exception {
        long brand = brands.create("brand", null).id();
        var ids = new ArrayList<Long>();
        for (int i = 0; i < 21; i++) { ids.add(product(brand)); }
        sameTime(brand);
        long outside = ids.getFirst();
        var before = ranked(brand);
        assertThat(before.items()).extracting(CustomerProductInfo::id).doesNotContain(outside);
        String snapshot = redis.opsForValue().get(key(brand));
        likes.register(users.register().getId(), outside);
        assertThat(redis.opsForValue().get(key(brand))).isEqualTo(snapshot);
        assertThat(ranked(brand)).isEqualTo(before);
        assertThat(query.getList(brand, 0, 20, ProductSort.LIKES_DESC).items().getFirst().id()).isEqualTo(outside);
        assertThat(query.getList(brand, 1, 20, ProductSort.LIKES_DESC).items())
            .extracting(CustomerProductInfo::id).containsExactly(ids.get(1));
        expire(brand);
        var after = ranked(brand);
        assertThat(after.items()).hasSize(20);
        assertThat(after.items().getFirst().id()).isEqualTo(outside);
        assertThat(after.items().getFirst().likeCount()).isEqualTo(1);
    }

    @Test
    void liveNamePriceBrandAndStockChangesReuseRankingAndLikesDoNotEvictPricePage() {
        long brand = brands.create("before", null).id();
        long product = product(brand);
        ranked(brand);
        String ranking = redis.opsForValue().get(key(brand));
        products.update(product, "after", 500);
        products.changeStock(product, 2);
        brands.update(brand, "after brand", "description");
        clearInvocations(repository);
        var current = ranked(brand).items().getFirst();
        assertThat(current.name()).isEqualTo("after");
        assertThat(current.price()).isEqualTo(500);
        assertThat(current.brand().name()).isEqualTo("after brand");
        assertThat(redis.opsForValue().get(key(brand))).isEqualTo(ranking);
        verify(repository, never()).findVisibleIdsByLikesDesc(brand, 0, 20);
        query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        String priceKey = "likes-price-cache-it:brand:" + brand + ":sort:price_asc:page:0:size:20";
        String price = redis.opsForValue().get(priceKey);
        likes.register(users.register().getId(), product);
        assertThat(redis.opsForValue().get(key(brand))).isEqualTo(ranking);
        assertThat(redis.opsForValue().get(priceKey)).isEqualTo(price);
    }

    @Test
    void creationInvalidatesEmptyRankingAndRollbackKeepsThePreviousCache() {
        long brand = brands.create("brand", null).id();
        assertThat(ranked(brand).items()).isEmpty();
        long first = product(brand);
        assertThat(redis.hasKey(key(brand))).isFalse();
        ranked(brand);
        String before = redis.opsForValue().get(key(brand));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            product(brand);
            throw new IllegalStateException("rollback creation");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(redis.opsForValue().get(key(brand))).isEqualTo(before);
        assertThat(ranked(brand).items()).extracting(CustomerProductInfo::id).containsExactly(first);
    }

    @Test
    void likeRollbackBypassesCacheInsideTheWriteAndKeepsCommittedRanking() {
        long brand = brands.create("brand", null).id();
        long first = product(brand);
        long second = product(brand);
        sameTime(brand);
        long user = users.register().getId();
        var before = ranked(brand);
        String cached = redis.opsForValue().get(key(brand));
        clearInvocations(cache);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            likes.register(user, first);
            assertThat(ranked(brand).items()).extracting(CustomerProductInfo::id).containsExactly(first, second);
            throw new IllegalStateException("rollback like");
        })).isInstanceOf(IllegalStateException.class);
        verify(cache, never()).get(anyLong());
        verify(cache, never()).put(anyLong(), any());
        verify(cache, never()).evict(anyLong());
        assertThat(redis.opsForValue().get(key(brand))).isEqualTo(cached);
        assertThat(ranked(brand)).isEqualTo(before);
        assertThat(lists.getList(user, brand, 0, 20, ProductSort.POPULAR).items())
            .extracting(ProductListItemInfo::isLiked).containsExactly(false, false);
    }

    @Test
    void deletedProductsStayHiddenEvenIfOldIdsAreReinserted() {
        long brand = brands.create("brand", null).id();
        long first = product(brand);
        long second = product(brand);
        ranked(brand);
        String old = redis.opsForValue().get(key(brand));
        products.delete(first);
        assertThat(redis.hasKey(key(brand))).isFalse();
        redis.opsForValue().set(key(brand), old, Duration.ofMinutes(5));
        assertThat(ranked(brand).items()).extracting(CustomerProductInfo::id).containsExactly(second);
        assertThat(cache.get(brand)).contains(new PopularPage(List.of(second)));
    }

    @Test
    void deletedBrandCannotUseItsOldCacheAndOtherBrandIdsAreRejected() {
        long brand = brands.create("brand", null).id();
        long expected = product(brand);
        long other = brands.create("other", null).id();
        long forbidden = product(other);
        cache.put(brand, new PopularPage(List.of(forbidden)));
        assertThat(ranked(brand).items()).extracting(CustomerProductInfo::id).containsExactly(expected);
        String old = redis.opsForValue().get(key(brand));
        brands.delete(brand);
        assertThat(redis.hasKey(key(brand))).isFalse();
        redis.opsForValue().set(key(brand), old, Duration.ofMinutes(5));
        assertThatThrownBy(() -> ranked(brand)).isInstanceOf(CoreException.class);
    }

    @Test
    void tiesUseCreationTimeAndIdAndUnsupportedQueriesDoNotUseThisCache() {
        long brand = brands.create("brand", null).id();
        long first = product(brand);
        long second = product(brand);
        long third = product(brand);
        sameTime(brand);
        jdbc.update("update product set created_at='2026-01-01 00:00:00' where id=?", first);
        assertThat(ranked(brand).items()).extracting(CustomerProductInfo::id).containsExactly(third, second, first);
        assertThat(ranked(brand).items()).extracting(CustomerProductInfo::id).containsExactly(third, second, first);
        clearInvocations(cache);
        query.getList(brand, 1, 20, ProductSort.LIKES_DESC);
        query.getList(brand, 0, 2, ProductSort.LIKES_DESC);
        query.getList(null, 0, 20, ProductSort.LIKES_DESC);
        query.getList(brand, 0, 20, ProductSort.LIKES_DESC);
        query.getList(brand, 0, 20, ProductSort.LATEST);
        query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        verify(cache, never()).get(anyLong());
        verify(cache, never()).put(anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"broken JSON", "{\"ids\":[null]}"})
    void invalidCacheValuesFallBack(String value) {
        long brand = brands.create("brand", null).id();
        long product = product(brand);
        redis.opsForValue().set(key(brand), value, Duration.ofMinutes(5));
        assertThat(ranked(brand).items()).extracting(CustomerProductInfo::id).containsExactly(product);
    }

    @Test
    void databaseErrorsOnHitAndMissAreNotTurnedIntoEmptyPages() {
        long brand = brands.create("brand", null).id();
        product(brand);
        ranked(brand);
        doThrow(new DataAccessResourceFailureException("DB failed")).when(repository).findActiveByIds(any());
        assertThatThrownBy(() -> ranked(brand)).isInstanceOf(DataAccessResourceFailureException.class);
        reset(repository);
        redis.delete(key(brand));
        doThrow(new DataAccessResourceFailureException("DB failed")).when(repository).findVisibleIdsByLikesDesc(brand, 0, 20);
        assertThatThrownBy(() -> ranked(brand)).isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void lateFillCanKeepOldOrderButCurrentCountsAndPersonalStateUntilExpiry() throws Exception {
        long brand = brands.create("brand", null).id();
        long first = product(brand);
        long second = product(brand);
        sameTime(brand);
        long user = users.register().getId();
        var read = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var once = new AtomicBoolean();
        doAnswer(invocation -> {
            if (once.compareAndSet(false, true)) {
                read.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("fill timeout"); }
            }
            return invocation.callRealMethod();
        }).when(cache).put(anyLong(), any());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var oldRead = executor.submit(() -> ranked(brand));
            assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
            likes.register(user, first);
            release.countDown();
            assertThat(oldRead.get(10, TimeUnit.SECONDS).items()).extracting(CustomerProductInfo::id)
                .containsExactly(second, first);
            var staleOrder = lists.getList(user, brand, 0, 20, ProductSort.POPULAR);
            assertThat(staleOrder.items()).extracting(item -> item.product().id()).containsExactly(second, first);
            assertThat(staleOrder.items()).extracting(item -> item.product().likeCount()).containsExactly(0L, 1L);
            assertThat(staleOrder.items()).extracting(ProductListItemInfo::isLiked).containsExactly(false, true);
            expire(brand);
            assertThat(ranked(brand).items()).extracting(CustomerProductInfo::id).containsExactly(first, second);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void failedEvictionDoesNotUndoDeletionAndOldIdsRemainHidden() {
        long brand = brands.create("brand", null).id();
        long first = product(brand);
        long second = product(brand);
        ranked(brand);
        clearInvocations(cache);
        doThrow(new IllegalStateException("DEL failed")).when(cache).evict(brand);
        products.delete(first);
        assertThat(redis.hasKey(key(brand))).isTrue();
        assertThat(ranked(brand).items()).extracting(CustomerProductInfo::id).containsExactly(second);
        assertThatThrownBy(() -> products.get(first)).isInstanceOf(CoreException.class);
        verify(cache).evict(brand);
    }

    @Test
    void realRedisOutageFallsBackAndExplicitEvictionRepairsAfterRestart() throws Exception {
        long brand = brands.create("brand", null).id();
        long first = product(brand);
        long second = product(brand);
        sameTime(brand);
        ranked(brand);
        try {
            REDIS.getDockerClient().stopContainerCmd(REDIS.getContainerId()).withTimeout(1).exec();
            assertThat(likes.register(users.register().getId(), first)).isTrue();
            assertThat(ranked(brand).items()).extracting(CustomerProductInfo::id).containsExactly(first, second);
        } finally { REDIS.getDockerClient().startContainerCmd(REDIS.getContainerId()).exec(); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        boolean ready = false;
        while (!ready && System.nanoTime() < deadline) {
            try { redis.hasKey(key(brand)); ready = true; }
            catch (RuntimeException ignored) { Thread.sleep(50); }
        }
        assertThat(ready).isTrue();
        redis.delete(key(brand));
        assertThat(ranked(brand).items()).extracting(CustomerProductInfo::id).containsExactly(first, second);
        assertThat(cache.get(brand)).contains(new PopularPage(List.of(first, second)));
    }

    @Test
    void popularKeepsRankingWhileLikesDescAndPersonalStateReflectChanges() throws Exception {
        long brand = brands.create("brand", null).id();
        long first = product(brand);
        long second = product(brand);
        sameTime(brand);
        long user = users.register().getId();
        mvc.perform(get("/api/v1/products").header("X-USER-ID", user)
                .param("brandId", String.valueOf(brand)).param("sort", "popular"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(second));
        mvc.perform(post("/api/v1/products/{id}/likes", first).header("X-USER-ID", user))
            .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/products").header("X-USER-ID", user)
                .param("brandId", String.valueOf(brand)).param("sort", "popular"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(second))
            .andExpect(jsonPath("$.data.items[1].id").value(first))
            .andExpect(jsonPath("$.data.items[1].likeCount").value(1))
            .andExpect(jsonPath("$.data.items[1].isLiked").value(true))
            .andExpect(jsonPath("$.data.totalCount").doesNotExist());
        mvc.perform(get("/api/v1/products").header("X-USER-ID", user)
                .param("brandId", String.valueOf(brand)).param("sort", "likes_desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(first));
        mvc.perform(delete("/api/v1/products/{id}/likes", first).header("X-USER-ID", user))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/products").header("X-USER-ID", user)
                .param("brandId", String.valueOf(brand)).param("sort", "popular"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[1].likeCount").value(0))
            .andExpect(jsonPath("$.data.items[1].isLiked").value(false));
    }

    @Test
    void popularRejectsUnsupportedFiltersAndPreservesUserErrorsOnHit() throws Exception {
        mvc.perform(get("/api/v1/products").param("sort", "popular"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/products").param("sort", "popular").param("brandId", "7").param("page", "1"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/products").param("sort", "popular").param("brandId", "7").param("size", "10"))
            .andExpect(status().isBadRequest());
        long brand = brands.create("brand", null).id();
        long product = product(brand);
        likes.register(users.register().getId(), product);
        ranked(brand);
        mvc.perform(get("/api/v1/products").param("sort", "popular").param("brandId", String.valueOf(brand)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].isLiked").value(false))
            .andExpect(jsonPath("$.data.items[0].likeCount").value(1));
        mvc.perform(get("/api/v1/products").header("X-USER-ID", Long.MAX_VALUE)
                .param("sort", "popular").param("brandId", String.valueOf(brand)))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/products").header("X-USER-ID", "invalid")
                .param("sort", "popular").param("brandId", String.valueOf(brand)))
            .andExpect(status().isBadRequest());
    }

    private long product(long brand) { return products.create(brand, "product", 1000, 5).id(); }

    private ProductPage<CustomerProductInfo> ranked(long brand) {
        return query.getList(brand, 0, 20, ProductSort.POPULAR);
    }

    private void sameTime(long brand) {
        jdbc.update("update product set created_at='2026-01-02 00:00:00' where brand_id=?", brand);
    }

    private String key(long brand) { return "popular-cache-it:brand:" + brand + ":sort:popular:page:0:size:20"; }

    private void expire(long brand) throws InterruptedException {
        assertThat(redis.expire(key(brand), Duration.ofMillis(100))).isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Boolean.TRUE.equals(redis.hasKey(key(brand))) && System.nanoTime() < deadline) { Thread.sleep(25); }
        assertThat(redis.hasKey(key(brand))).isFalse();
    }
}
