package com.loopers.application.product;

import com.loopers.application.brand.BrandFacade;
import com.loopers.infrastructure.product.ProductRepositoryImpl;
import com.loopers.infrastructure.product.RedisPricePageCache;
import com.loopers.application.like.LikeFacade;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderFacade.OrderLine;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.product.PricePage;
import com.loopers.domain.product.ProductService;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.net.ServerSocket;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {"commerce.cache.price.enabled=true", "commerce.cache.price.prefix=price-cache-it"})
@AutoConfigureMockMvc
class PricePageCacheIntegrationTest {
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
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private LikeFacade likes;
    @Autowired private OrderFacade orders;
    @Autowired private UserRegistrationService users;
    @Autowired private ProductService productService;
    @Autowired private TransactionTemplate transaction;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired @Qualifier("productCacheRedisTemplate") private StringRedisTemplate redis;
    @MockitoSpyBean private ProductRepositoryImpl repository;
    @MockitoSpyBean private RedisPricePageCache cache;

    @AfterEach
    void clean() {
        reset(repository, cache);
        try {
            var keys = redis.keys("price-cache-it:*");
            if (keys != null && !keys.isEmpty()) { redis.delete(keys); }
        } finally { cleanUp.truncateAllTables(); }
    }

    @Test
    void successfulPriceChangeDeletesCachedPageAfterCommit() {
        long brand = brands.create("brand", null).id();
        long product = products.create(brand, "first", 1000, 5).id();
        query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        String oldKey = redis.keys("price-cache-it:*").iterator().next();
        products.update(product, "updated", 500);
        assertThat(redis.hasKey(oldKey)).isFalse();
    }

    @Test
    void sharedPageKeepsPersonalLikesSeparateAndReflectsCommittedChanges() throws Exception {
        long brand = brands.create("personal brand", null).id();
        long first = products.create(brand, "first", 1000, 5).id();
        long second = products.create(brand, "second", 2000, 5).id();
        long a = users.register().getId();
        long b = users.register().getId();
        likes.register(a, first);
        likes.register(b, second);
        assertThat(lists.getList(a, brand, 0, 20, ProductSort.PRICE_ASC).items())
            .extracting(ProductListItemInfo::isLiked).containsExactly(true, false);
        assertThat(lists.getList(b, brand, 0, 20, ProductSort.PRICE_ASC).items())
            .extracting(ProductListItemInfo::isLiked).containsExactly(false, true);
        String shared = redis.opsForValue().get(key(brand));
        assertThat(shared).doesNotContain("isLiked", "userId");
        clearInvocations(repository, cache);
        mvc.perform(post("/api/v1/products/{id}/likes", first).header("X-USER-ID", a))
            .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/products/{id}/likes", first).header("X-USER-ID", a))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/products").header("X-USER-ID", a).param("brandId", String.valueOf(brand))
                .param("sort", "price_asc"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].isLiked").value(false))
            .andExpect(jsonPath("$.data.items[0].likeCount").value(0));
        mvc.perform(post("/api/v1/products/{id}/likes", first).header("X-USER-ID", a))
            .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/products").header("X-USER-ID", a).param("brandId", String.valueOf(brand))
                .param("sort", "price_asc"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].isLiked").value(true))
            .andExpect(jsonPath("$.data.items[0].likeCount").value(1));
        assertThat(lists.getList(b, brand, 0, 20, ProductSort.PRICE_ASC).items())
            .extracting(ProductListItemInfo::isLiked).containsExactly(false, true);
        assertThat(redis.opsForValue().get(key(brand))).isEqualTo(shared);
        verify(repository, times(0)).findActiveByPriceAsc(brand, 0, 20);
        verify(cache, times(0)).evict(any(), anyInt(), anyInt());
        verify(cache, times(0)).put(any(), anyInt(), anyInt(), any());
    }

    @Test
    void rolledBackLikeChangeKeepsPersonalStateAndSharedCache() {
        long brand = brands.create("personal rollback", null).id();
        long product = products.create(brand, "first", 1000, 5).id();
        long user = users.register().getId();
        likes.register(user, product);
        var before = lists.getList(user, brand, 0, 20, ProductSort.PRICE_ASC);
        String shared = redis.opsForValue().get(key(brand));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            likes.cancel(user, product);
            assertThat(lists.getList(user, brand, 0, 20, ProductSort.PRICE_ASC).items().getFirst().isLiked()).isFalse();
            throw new IllegalStateException("rollback personal change");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(lists.getList(user, brand, 0, 20, ProductSort.PRICE_ASC)).isEqualTo(before);
        assertThat(before.items().getFirst().isLiked()).isTrue();
        assertThat(redis.opsForValue().get(key(brand))).isEqualTo(shared);
    }

    @Test
    void repeatedPricePageUsesCacheAndPriceCommitChangesOrder() throws Exception {
        long brand = brands.create("cache brand", null).id();
        long first = products.create(brand, "first", 1000, 5).id();
        long second = products.create(brand, "second", 2000, 5).id();
        clearInvocations(repository);
        var miss = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        var hit = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        assertThat(hit).isEqualTo(miss);
        verify(repository, times(1)).findActiveByPriceAsc(anyLong(), anyInt(), anyInt());
        mvc.perform(put("/api-admin/v1/products/{id}", second).header("X-USER-ROLE", "ADMIN")
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"second updated\",\"price\":500}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/products").param("brandId", String.valueOf(brand))
            .param("page", "0").param("size", "20").param("sort", "price_asc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(second))
            .andExpect(jsonPath("$.data.items[0].price").value(500))
            .andExpect(jsonPath("$.data.totalCount").doesNotExist());
        var changed = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        assertThat(changed.items()).extracting(CustomerProductInfo::id).containsExactly(second, first);
        assertThat(changed.items().getFirst().price()).isEqualTo(500);
        assertThat(changed.items().getFirst().name()).isEqualTo("second updated");
    }

    @Test
    void dbOnlyMissAndHitAgreeAndTtlIsApplied() {
        long brand = brands.create("brand", null).id();
        products.create(brand, "first", 1000, 5);
        var dbOnly = new PricePageReader(productService, cache, false);
        var readOnly = new TransactionTemplate(transaction.getTransactionManager());
        readOnly.setReadOnly(true);
        PricePage expected = readOnly.execute(status -> dbOnly.get(brand, 0, 20));
        clearInvocations(repository);
        var miss = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        var hit = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        assertThat(hit).isEqualTo(miss);
        assertThat(hit.page()).isZero();
        assertThat(hit.size()).isEqualTo(20);
        assertThat(hit.items()).extracting(CustomerProductInfo::id)
            .containsExactlyElementsOf(expected.items().stream().map(PricePage.Item::id).toList());
        verify(repository, times(1)).findActiveByPriceAsc(brand, 0, 20);
        String key = redis.keys("price-cache-it:*").iterator().next();
        assertThat(redis.getExpire(key)).isBetween(1L, 300L);
        assertThat(redis.opsForValue().get(key)).doesNotContain("totalCount", "likeCount", "quantity", "brand");
    }

    @Test
    void likesStockAndBrandLabelsStayLiveWithoutRefillingPricePage() {
        long brand = brands.create("brand", null).id();
        long product = products.create(brand, "first", 1000, 5).id();
        long user = users.register().getId();
        query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        clearInvocations(repository);
        likes.register(user, product);
        products.changeStock(product, 9);
        brands.update(brand, "new brand", "new description");
        var hit = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        assertThat(hit.items().getFirst().likeCount()).isEqualTo(1);
        assertThat(hit.items().getFirst().brand().name()).isEqualTo("new brand");
        verify(repository, times(0)).findActiveByPriceAsc(brand, 0, 20);
    }

    @Test
    void outsidePagePriceChangeAndCreationDeletionRefreshPage() {
        long brand = brands.create("brand", null).id();
        for (int i = 1; i <= 20; i++) { products.create(brand, "p" + i, i * 1000, 1); }
        long outside = products.create(brand, "outside", 30000, 1).id();
        var old = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        assertThat(old.items()).extracting(CustomerProductInfo::id).doesNotContain(outside);
        products.update(outside, "moved in", 1);
        assertThat(query.getList(brand, 0, 20, ProductSort.PRICE_ASC).items().getFirst().id()).isEqualTo(outside);
        products.delete(outside);
        var deleted = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        assertThat(deleted.items()).hasSize(20);
        assertThat(deleted.items()).extracting(CustomerProductInfo::id).doesNotContain(outside);
        long added = products.create(brand, "added", 1, 1).id();
        var created = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        assertThat(created.items()).hasSize(20);
        assertThat(created.items().getFirst().id()).isEqualTo(added);
    }

    @Test
    void brandDeletionKeepsOtherBrandCacheAndHidesDeletedProducts() {
        long first = brands.create("first brand", null).id();
        long second = brands.create("second brand", null).id();
        products.create(first, "first", 1000, 5);
        products.create(second, "second", 1000, 5);
        query.getList(first, 0, 20, ProductSort.PRICE_ASC);
        var other = query.getList(second, 0, 20, ProductSort.PRICE_ASC);
        clearInvocations(repository);
        brands.delete(first);
        assertThatThrownBy(() -> query.getList(first, 0, 20, ProductSort.PRICE_ASC))
            .isInstanceOf(com.loopers.support.error.CoreException.class);
        assertThat(query.getList(second, 0, 20, ProductSort.PRICE_ASC)).isEqualTo(other);
        verify(repository, times(0)).findActiveByPriceAsc(second, 0, 20);
        assertThat(query.getList(null, 0, 20, ProductSort.PRICE_ASC).items()).hasSize(1);
    }

    @Test
    void rollbackPreservesCachedResultAndWriteReadsDoNotFillCache() {
        long brand = brands.create("brand", null).id();
        long product = products.create(brand, "first", 1000, 5).id();
        var before = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        String cachedJson = redis.opsForValue().get(key(brand));
        clearInvocations(cache);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            products.update(product, "temporary", 500);
            products.create(brand, "rolled back", 1, 1);
            var temporary = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
            assertThat(temporary.items()).hasSize(2);
            assertThat(temporary.items().getFirst().name()).isEqualTo("rolled back");
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(redis.opsForValue().get(key(brand))).isEqualTo(cachedJson);
        verify(cache, times(0)).evict(any(), anyInt(), anyInt());
        verify(cache, times(0)).put(any(), anyInt(), anyInt(), any());
        assertThat(query.getList(brand, 0, 20, ProductSort.PRICE_ASC)).isEqualTo(before);
    }

    @Test
    void lateFillCanRestoreOldDisplayPriceUntilExpiry() throws Exception {
        long brand = brands.create("brand", null).id();
        long product = products.create(brand, "first", 1000, 5).id();
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (first.compareAndSet(true, false)) {
                read.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("fill timeout"); }
            }
            return invocation.callRealMethod();
        }).when(cache).put(any(), anyInt(), anyInt(), any());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var old = executor.submit(() -> query.getList(brand, 0, 20, ProductSort.PRICE_ASC));
            assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
            products.update(product, "new", 500);
            var current = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
            release.countDown();
            assertThat(old.get(10, TimeUnit.SECONDS).items().getFirst().price()).isEqualTo(1000);
            assertThat(current.items().getFirst().price()).isEqualTo(500);
            assertThat(query.getList(brand, 0, 20, ProductSort.PRICE_ASC).items().getFirst().price()).isEqualTo(1000);
            assertThat(redis.keys("price-cache-it:*")).hasSize(1);
            // Accelerate only this owned key's expiry; do not claim 300-second expiry was waited out.
            expireAndWait(key(brand));
            assertThat(query.getList(brand, 0, 20, ProductSort.PRICE_ASC)).isEqualTo(current);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void redisStopDuringCommitFallsBackAndExplicitEvictionRepairsAfterRestart() throws Exception {
        long brand = brands.create("brand", null).id();
        long product = products.create(brand, "first", 1000, 5).id();
        query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        String oldKey = redis.keys("price-cache-it:*").iterator().next();
        redis.execute((RedisCallback<Void>) c -> { c.serverCommands().save(); return null; });
        var container = REDIS;
        var docker = container.getDockerClient();
        docker.stopContainerCmd(container.getContainerId()).withTimeout(1).exec();
        try {
            products.update(product, "new", 500);
            long start = System.nanoTime();
            var fallback = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
            assertThat(fallback.items().getFirst().price()).isEqualTo(500);
            assertThat((System.nanoTime() - start) / 1e9).isLessThan(3);
            assertThat(jdbc.queryForObject("SELECT price FROM product WHERE id=?", Long.class, product)).isEqualTo(500);
        } finally {
            docker.startContainerCmd(container.getContainerId()).exec();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean ready = false;
        while (!ready && System.nanoTime() < deadline) {
            try { ready = "PONG".equals(redis.execute((RedisCallback<String>) c -> c.ping())); }
            catch (RuntimeException ignored) { Thread.sleep(50); }
        }
        assertThat(ready).isTrue();
        // Reconnect can replay a timed-out DEL/SET. Explicitly clear our key to verify recovery.
        cache.evict(brand, 0, 20);
        assertThat(redis.hasKey(oldKey)).isFalse();
        clearInvocations(repository);
        var refill = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        assertThat(query.getList(brand, 0, 20, ProductSort.PRICE_ASC)).isEqualTo(refill);
        assertThat(refill.items().getFirst().price()).isEqualTo(500);
        verify(repository, times(1)).findActiveByPriceAsc(brand, 0, 20);
    }

    @Test
    void dbFailureIsNotConvertedToEmptyPage() {
        long brand = brands.create("brand", null).id();
        products.create(brand, "first", 1000, 5);
        doThrow(new DataAccessResourceFailureException("DB failure"))
            .when(repository).findActiveByPriceAsc(brand, 0, 20);
        assertThatThrownBy(() -> query.getList(brand, 0, 20, ProductSort.PRICE_ASC))
            .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void concurrentColdFillsKeepOneConsistentPage() throws Exception {
        long brand = brands.create("brand", null).id();
        products.create(brand, "first", 1000, 5);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch go = new CountDownLatch(1);
        doAnswer(invocation -> {
            ready.countDown();
            if (!go.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("fill timeout"); }
            return invocation.callRealMethod();
        }).when(cache).put(any(), anyInt(), anyInt(), any());
        var executor = Executors.newFixedThreadPool(8);
        var futures = new ArrayList<Future<ProductPage<CustomerProductInfo>>>();
        try {
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> query.getList(brand, 0, 20, ProductSort.PRICE_ASC)));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            var expected = futures.getFirst().get(10, TimeUnit.SECONDS);
            for (var future : futures) { assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(expected); }
            assertThat(query.getList(brand, 0, 20, ProductSort.PRICE_ASC)).isEqualTo(expected);
            assertThat(redis.keys("price-cache-it:*")).hasSize(1);
        } finally {
            go.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void otherPagesSizesSortsAndUnfilteredQueriesBypassRedis() {
        long brand = brands.create("brand", null).id();
        products.create(brand, "first", 1000, 5);
        clearInvocations(cache);
        query.getList(brand, 1, 20, ProductSort.PRICE_ASC);
        query.getList(brand, 0, 1, ProductSort.PRICE_ASC);
        query.getList(null, 0, 20, ProductSort.PRICE_ASC);
        query.getList(brand, 0, 20, ProductSort.LIKES_DESC);
        verify(cache, times(0)).get(any(), anyInt(), anyInt());
        verify(cache, times(0)).put(any(), anyInt(), anyInt(), any());
    }

    @Test
    void failedEvictionKeepsDbCommitAndOldDisplayUntilExpiry() throws Exception {
        long brand = brands.create("brand", null).id();
        long product = products.create(brand, "first", 1000, 5).id();
        query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        doThrow(new IllegalStateException("controlled DEL failure")).when(cache).evict(brand, 0, 20);
        products.update(product, "new", 500);
        assertThat(jdbc.queryForObject("SELECT price FROM product WHERE id=?", Long.class, product)).isEqualTo(500);
        assertThat(query.getList(brand, 0, 20, ProductSort.PRICE_ASC).items().getFirst().price()).isEqualTo(1000);
        reset(cache);
        expireAndWait(key(brand));
        var recovered = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        assertThat(recovered.items().getFirst().price()).isEqualTo(500);
        assertThat(query.getList(brand, 0, 20, ProductSort.PRICE_ASC)).isEqualTo(recovered);
    }

    @Test
    void failedEvictionStillHidesDeletedProductAndRefillsPage() {
        long brand = brands.create("brand", null).id();
        long first = products.create(brand, "first", 1000, 5).id();
        long second = products.create(brand, "second", 2000, 5).id();
        query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        doThrow(new IllegalStateException("controlled DEL failure")).when(cache).evict(brand, 0, 20);
        products.delete(first);
        var result = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        assertThat(result.items()).extracting(CustomerProductInfo::id).containsExactly(second);
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void failedEvictionStillHidesDeletedBrand() {
        long brand = brands.create("brand", null).id();
        products.create(brand, "first", 1000, 5);
        query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        doThrow(new IllegalStateException("controlled DEL failure")).when(cache).evict(brand, 0, 20);
        brands.delete(brand);
        assertThat(redis.hasKey(key(brand))).isTrue();
        assertThatThrownBy(() -> query.getList(brand, 0, 20, ProductSort.PRICE_ASC))
            .isInstanceOf(com.loopers.support.error.CoreException.class);
    }

    @Test
    void adminPriceChangeWithCachedPageKeepsPastOrderAmountAndNewOrderUsesNewPrice() throws Exception {
        long brand = brands.create("order brand", null).id();
        long product = products.create(brand, "first", 1000, 5).id();
        long user = users.register().getId();
        long pastOrder = orders.create(user, List.of(new OrderLine(product, 2))).id();
        query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        assertThat(redis.hasKey(key(brand))).isTrue();

        mvc.perform(put("/api-admin/v1/products/{id}", product).header("X-USER-ROLE", "ADMIN")
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"first\",\"price\":500}"))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v1/products").param("brandId", String.valueOf(brand)).param("sort", "price_asc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].price").value(500));
        assertThat(orderUnitPrice(pastOrder)).isEqualTo(1000);
        assertThat(orderTotal(pastOrder)).isEqualTo(2000);
        long newOrder = orders.create(user, List.of(new OrderLine(product, 2))).id();
        assertThat(orderUnitPrice(newOrder)).isEqualTo(500);
    }

    @Test
    void staleCachedPriceAfterFailedEvictionIsNeverUsedForNewOrders() {
        long brand = brands.create("stale order brand", null).id();
        long product = products.create(brand, "first", 1000, 5).id();
        long user = users.register().getId();
        query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        doThrow(new IllegalStateException("controlled DEL failure")).when(cache).evict(brand, 0, 20);
        products.update(product, "first", 500);
        assertThat(query.getList(brand, 0, 20, ProductSort.PRICE_ASC).items().getFirst().price()).isEqualTo(1000);

        long order = orders.create(user, List.of(new OrderLine(product, 1))).id();
        assertThat(orderUnitPrice(order)).isEqualTo(500);
    }

    @Test
    void brandDeletionWithCachedPagesKeepsPastOrdersAndOtherBrandPage() {
        long deleted = brands.create("deleted brand", null).id();
        long kept = brands.create("kept brand", null).id();
        long deletedProduct = products.create(deleted, "gone", 1000, 5).id();
        products.create(kept, "stay", 3000, 5);
        long user = users.register().getId();
        long pastOrder = orders.create(user, List.of(new OrderLine(deletedProduct, 1))).id();
        query.getList(deleted, 0, 20, ProductSort.PRICE_ASC);
        var keptPage = query.getList(kept, 0, 20, ProductSort.PRICE_ASC);

        brands.delete(deleted);

        assertThat(redis.hasKey(key(deleted))).isFalse();
        assertThat(query.getList(kept, 0, 20, ProductSort.PRICE_ASC)).isEqualTo(keptPage);
        assertThat(orderUnitPrice(pastOrder)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT product_name FROM order_item WHERE order_id=?", String.class, pastOrder))
            .isEqualTo("gone");
    }

    private long orderUnitPrice(long orderId) {
        return jdbc.queryForObject("SELECT unit_price FROM order_item WHERE order_id=?", Long.class, orderId);
    }

    private long orderTotal(long orderId) {
        return jdbc.queryForObject("SELECT total_amount FROM orders WHERE id=?", Long.class, orderId);
    }

    @Test
    void evictionOccursAfterCommitAndNeverDuringUncommittedChanges() {
        long brand = brands.create("brand", null).id();
        long product = products.create(brand, "first", 1000, 5).id();
        query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        transaction.executeWithoutResult(status -> {
            products.update(product, "new", 500);
            assertThat(redis.hasKey(key(brand))).isTrue();
        });
        assertThat(redis.hasKey(key(brand))).isFalse();
        assertThat(query.getList(brand, 0, 20, ProductSort.PRICE_ASC).items().getFirst().price()).isEqualTo(500);
    }

    @Test
    void dbFailureDuringHitVisibilityCheckIsNotHidden() {
        long brand = brands.create("brand", null).id();
        products.create(brand, "first", 1000, 5);
        query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        doThrow(new DataAccessResourceFailureException("visibility DB failure"))
            .when(repository).findActiveIds(anyLong(), any());
        assertThatThrownBy(() -> query.getList(brand, 0, 20, ProductSort.PRICE_ASC))
            .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void malformedCachedJsonFallsBackAndIsReplacedWithValidPage() {
        long brand = brands.create("brand", null).id();
        products.create(brand, "first", 1000, 5);
        var expected = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        String key = redis.keys("price-cache-it:*").iterator().next();
        redis.opsForValue().set(key, "{broken", java.time.Duration.ofSeconds(300));
        clearInvocations(repository);
        assertThat(query.getList(brand, 0, 20, ProductSort.PRICE_ASC)).isEqualTo(expected);
        assertThat(query.getList(brand, 0, 20, ProductSort.PRICE_ASC)).isEqualTo(expected);
        verify(repository, times(1)).findActiveByPriceAsc(brand, 0, 20);
    }

    private String key(long brand) {
        return "price-cache-it:brand:" + brand + ":sort:price_asc:page:0:size:20";
    }

    private void expireAndWait(String ownedKey) throws InterruptedException {
        assertThat(redis.expire(ownedKey, Duration.ofMillis(100))).isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (Boolean.TRUE.equals(redis.hasKey(ownedKey)) && System.nanoTime() < deadline) { Thread.sleep(25); }
        assertThat(redis.hasKey(ownedKey)).isFalse();
    }
}
