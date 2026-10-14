package com.loopers.interfaces.api.product;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.common.PageWindow;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.ProductListingCache;
import com.loopers.domain.product.ProductViewQuery;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ProductCacheFailureE2ETest.InjectedFailures.class)
class ProductCacheFailureE2ETest {

    private static final String CUSTOMER = "/api/v1/products";
    private static final String PAGE_KEYS = "product:list:v1:brand=*";
    private static final Long USER = 1L;
    private static final long TIMEOUT_SECONDS = 10;

    private static final ParameterizedTypeReference<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> PAGE =
        new ParameterizedTypeReference<>() {};

    private final TestRestTemplate testRestTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;
    private final FailingListingCache cache;

    private Long brandId;

    @Autowired
    ProductCacheFailureE2ETest(
        TestRestTemplate testRestTemplate,
        JdbcTemplate jdbcTemplate,
        RedisTemplate<String, String> redisTemplate,
        DatabaseCleanUp databaseCleanUp,
        RedisCleanUp redisCleanUp,
        BrandFacade brandFacade,
        ProductFacade productFacade,
        FailingListingCache cache
    ) {
        this.testRestTemplate = testRestTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
        this.cache = cache;
    }

    @BeforeEach
    void setUp() {
        brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
    }

    @AfterEach
    void tearDown() {
        cache.reset();
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private Long product(String name, long price) {
        return productFacade.register(brandId, name, Price.of(price)).getId();
    }

    private String firstPriceAscPage() {
        return CUSTOMER + "?brandId=" + brandId + "&sort=price_asc";
    }

    private long listedPrice() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(USER));
        return testRestTemplate.exchange(firstPriceAscPage(), HttpMethod.GET, new HttpEntity<>(headers), PAGE)
            .getBody().data().items().get(0).price();
    }

    private ResponseEntity<String> changePriceAsAdmin(Long productId, long price) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return testRestTemplate.withBasicAuth("admin", "local-only-not-a-secret").exchange(
            "/api-admin/v1/products/" + productId, HttpMethod.PUT,
            new HttpEntity<>(Map.of("name", "셔츠", "price", price), headers), String.class);
    }

    private long priceInDatabase(Long productId) {
        Long price = jdbcTemplate.queryForObject("SELECT price FROM product WHERE id = ?", Long.class, productId);
        return price == null ? -1 : price;
    }

    private Set<String> pageKeys() {
        return redisTemplate.keys(PAGE_KEYS);
    }

    @DisplayName("Q-5 · 커밋 뒤 무효화가 잠깐 실패해도 다시 시도해 회복한다 — 다음 조회는 새 가격이다")
    @Test
    void retryRecoversTransientInvalidationFailure() {
        Long productId = product("셔츠", 10_000);
        listedPrice();
        cache.failNextInvalidations(2);

        ResponseEntity<String> response = changePriceAsAdmin(productId, 5_000);

        assertAll(
            () -> assertThat(response.getStatusCode().value()).isEqualTo(200),
            () -> assertThat(listedPrice()).isEqualTo(5_000L)
        );
    }

    @DisplayName("Q-5 · 다시 시도해도 무효화가 실패하면 커밋은 그대로이고, 옛 가격은 TTL(60 초) 까지만 보이며, 만료 뒤 원본과 같아진다")
    @Test
    void exhaustedRetriesFallBackToTtl() {
        Long productId = product("셔츠", 10_000);
        listedPrice();
        cache.failNextInvalidations(Integer.MAX_VALUE);

        ResponseEntity<String> response = changePriceAsAdmin(productId, 5_000);
        long staleWhileCached = listedPrice();
        Set<String> keys = pageKeys();
        long secondsLeft = keys.stream().mapToLong(key -> redisTemplate.getExpire(key, TimeUnit.SECONDS)).max().orElse(-1);

        redisTemplate.delete(keys);

        assertAll(
            () -> assertThat(response.getStatusCode().value()).as("커밋은 실패로 바뀌지 않는다").isEqualTo(200),
            () -> assertThat(priceInDatabase(productId)).isEqualTo(5_000L),
            () -> assertThat(staleWhileCached).as("무효화되지 않은 동안은 옛 가격").isEqualTo(10_000L),
            () -> assertThat(secondsLeft).as("옛 키는 TTL 이 있다").isBetween(1L, 60L),
            () -> assertThat(listedPrice()).as("만료 뒤 첫 조회는 원본과 같다").isEqualTo(priceInDatabase(productId))
        );
    }

    @DisplayName("Q-5 · 이전 조회가 옛 DB 값을 변경 커밋 뒤에 늦게 채우면, 옛 가격이 TTL(60 초) 까지만 보이고 만료 뒤 원본과 같아진다")
    @Test
    void lateFillStaysStaleOnlyUntilTtl() throws Exception {
        Long productId = product("셔츠", 10_000);
        cache.holdNextSave();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Long> earlier = executor.submit(this::listedPrice);
            assertThat(cache.awaitHeldSave(TIMEOUT_SECONDS)).as("이전 조회가 옛 DB 값을 읽고 저장 직전에 멈췄다").isTrue();

            assertThat(changePriceAsAdmin(productId, 5_000).getStatusCode().value()).isEqualTo(200);
            cache.releaseHeldSave();
            long earlierSaw = earlier.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            long staleAfterLateFill = listedPrice();
            Set<String> keys = pageKeys();
            long secondsLeft = keys.stream().mapToLong(key -> redisTemplate.getExpire(key, TimeUnit.SECONDS)).max().orElse(-1);

            redisTemplate.delete(keys);

            assertAll(
                () -> assertThat(earlierSaw).as("이전 조회는 자기가 읽은 시점의 값").isEqualTo(10_000L),
                () -> assertThat(staleAfterLateFill).as("늦은 값이 살아 있는 키에 들어가 옛 가격이 보인다").isEqualTo(10_000L),
                () -> assertThat(secondsLeft).as("늦은 키도 TTL 이 있다").isBetween(1L, 60L),
                () -> assertThat(listedPrice()).as("만료 뒤 첫 조회는 원본과 같다").isEqualTo(priceInDatabase(productId))
            );
        } finally {
            cache.releaseHeldSave();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    @TestConfiguration
    static class InjectedFailures {

        @Bean
        @Primary
        FailingListingCache failingListingCache(@Qualifier("redisProductListingCache") ProductListingCache real) {
            return new FailingListingCache(real);
        }
    }

    static class FailingListingCache implements ProductListingCache {

        private final ProductListingCache real;
        private final AtomicInteger invalidationFailures = new AtomicInteger();
        private volatile CountDownLatch saveEntered;
        private volatile CountDownLatch saveRelease;

        FailingListingCache(ProductListingCache real) {
            this.real = real;
        }

        void failNextInvalidations(int times) {
            invalidationFailures.set(times);
        }

        void holdNextSave() {
            saveEntered = new CountDownLatch(1);
            saveRelease = new CountDownLatch(1);
        }

        boolean awaitHeldSave(long seconds) throws InterruptedException {
            return saveEntered.await(seconds, TimeUnit.SECONDS);
        }

        void releaseHeldSave() {
            if (saveRelease != null) {
                saveRelease.countDown();
            }
        }

        void reset() {
            invalidationFailures.set(0);
            releaseHeldSave();
            saveEntered = null;
            saveRelease = null;
        }

        @Override
        public Optional<PageWindow<ProductViewQuery.Listing>> find(Long brandId, ProductViewQuery.Sort sort) {
            return real.find(brandId, sort);
        }

        @Override
        public void save(Long brandId, ProductViewQuery.Sort sort, PageWindow<ProductViewQuery.Listing> page) {
            CountDownLatch entered = saveEntered;
            CountDownLatch release = saveRelease;
            if (entered != null && entered.getCount() > 0) {
                entered.countDown();
                try {
                    if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("붙들어 둔 저장을 놓아줄 신호가 오지 않았다");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            real.save(brandId, sort, page);
        }

        @Override
        public boolean invalidate(Long brandId) {
            if (invalidationFailures.getAndUpdate(left -> left > 0 ? left - 1 : 0) > 0) {
                return false;
            }
            return real.invalidate(brandId);
        }
    }
}
