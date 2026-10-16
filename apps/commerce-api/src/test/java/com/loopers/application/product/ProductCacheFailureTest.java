package com.loopers.application.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderInfo;
import com.loopers.config.cache.ProductCacheRedisConfig;
import com.loopers.domain.point.Point;
import com.loopers.domain.product.ProductListCache;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.RedisProductListCache;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 쓰기 실패·무효화 실패·늦은 채움 장면에서 캐시된 목록이 어떻게 보이는지 실제 MySQL + Redis 로 확인한다.
 * 기준 데이터는 seed 의 브랜드 7 첫 페이지(ProductQueryFixture), 첫 상품은 5406(가격 122, 재고 100) 이다.
 *
 * 표시 신선도 정책(확정): 무효화 실패·늦은 채움 때 가격·상품명·브랜드명·좋아요 수·순서·새 상품 노출은
 * TTL 만료 후 다음 miss 까지 옛 값을 허용한다. 삭제 상품 비노출·재고·주문 금액은 항상 DB 기준으로 보호한다.
 */
@SpringBootTest
@Sql("/sql/product-perf-seed.sql")
class ProductCacheFailureTest {

    /**
     * ProductListCache 를 테스트 포장지로 바꾼다. 무효화 실패용 두 번째 RedisProductListCache 는
     * DEL 만 예외를 던지는 RedisTemplate 을 쓰므로, 운영 코드의 afterCommit catch 경로가 실제로 실행된다.
     */
    @TestConfiguration
    static class FaultInjectionConfig {
        @Primary
        @Bean
        FaultInjectingProductListCache faultInjectingProductListCache(
            RedisProductListCache realCache,
            @Qualifier(ProductCacheRedisConfig.PRODUCT_CACHE_REDIS_TEMPLATE) RedisTemplate<String, String> cacheRedisTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry
        ) {
            RedisTemplate<String, String> failingDelete = new RedisTemplate<>() {
                @Override
                public Long delete(Collection<String> keys) {
                    throw new RedisSystemException("테스트: 캐시 삭제 실패를 흉내 낸다", new RuntimeException("DEL failed"));
                }
            };
            StringRedisSerializer serializer = new StringRedisSerializer();
            failingDelete.setKeySerializer(serializer);
            failingDelete.setValueSerializer(serializer);
            failingDelete.setConnectionFactory(cacheRedisTemplate.getConnectionFactory());
            failingDelete.afterPropertiesSet();

            return new FaultInjectingProductListCache(
                realCache, new RedisProductListCache(failingDelete, objectMapper, meterRegistry));
        }
    }

    private static final Long FIRST_PRODUCT_ID = 5406L;
    private static final long NEW_PRICE = 99_999L;
    private static final String CACHE_KEY = ProductQueryFixture.CACHE_KEY;
    private static final Duration EXPIRE_WAIT_LIMIT = Duration.ofSeconds(1);

    private final ProductFacade productFacade;
    private final OrderFacade orderFacade;
    private final PointJpaRepository pointJpaRepository;
    private final FaultInjectingProductListCache faultInjectingCache;
    private final RedisTemplate<String, String> cacheRedisTemplate;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    public ProductCacheFailureTest(
        ProductFacade productFacade,
        OrderFacade orderFacade,
        PointJpaRepository pointJpaRepository,
        ProductListCache productListCache,
        @Qualifier(ProductCacheRedisConfig.PRODUCT_CACHE_REDIS_TEMPLATE) RedisTemplate<String, String> cacheRedisTemplate,
        MeterRegistry meterRegistry,
        PlatformTransactionManager transactionManager,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp,
        RedisCleanUp redisCleanUp
    ) {
        this.productFacade = productFacade;
        this.orderFacade = orderFacade;
        this.pointJpaRepository = pointJpaRepository;
        this.faultInjectingCache = (FaultInjectingProductListCache) productListCache;
        this.cacheRedisTemplate = cacheRedisTemplate;
        this.meterRegistry = meterRegistry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
    }

    @AfterEach
    void tearDown() {
        faultInjectingCache.reset();
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private List<ProductInfo> queryFixturePage() {
        return productFacade.getProducts(
            ProductQueryFixture.BRAND_ID, ProductQueryFixture.SORT, ProductQueryFixture.PAGE, ProductQueryFixture.SIZE);
    }

    private void fillCache() {
        queryFixturePage();
        assertThat(cacheRedisTemplate.hasKey(CACHE_KEY)).isTrue();
    }

    private Map<String, Object> productRow(Long productId) {
        return jdbcTemplate.queryForMap("SELECT name, amount, deleted_at FROM products WHERE id = ?", productId);
    }

    private long priceInDb(Long productId) {
        return ((Number) productRow(productId).get("amount")).longValue();
    }

    /**
     * TTL 만료를 테스트에서 재현한다. 키에 1ms 만료를 걸고 사라질 때까지 짧게 기다린다 (상한 1초).
     */
    private void expireCacheKey() throws InterruptedException {
        cacheRedisTemplate.expire(CACHE_KEY, Duration.ofMillis(1));
        long deadline = System.nanoTime() + EXPIRE_WAIT_LIMIT.toNanos();
        while (Boolean.TRUE.equals(cacheRedisTemplate.hasKey(CACHE_KEY))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("캐시 키가 " + EXPIRE_WAIT_LIMIT + " 안에 만료되지 않았다");
            }
            Thread.sleep(10);
        }
    }

    /**
     * 첫 상품(5406)이 빠졌을 때의 기대 목록: 기존 2~20번째 + 21번째.
     */
    private List<ProductInfo> expectedWithoutFirst() {
        List<ProductInfo> expected = new ArrayList<>(ProductQueryFixture.EXPECTED_PAGE_0.subList(1, 20));
        expected.add(ProductQueryFixture.EXPECTED_21ST);
        return expected;
    }

    private Long saveUserPoint(Long userId, long balance) {
        Point point = new Point(userId);
        point.charge(balance);
        pointJpaRepository.save(point);
        return userId;
    }

    @DisplayName("장면 A. 쓰기 경로가 변경 뒤 예외로 rollback 되면, ")
    @Nested
    class WriteRolledBack {
        @DisplayName("DB 는 변경 전 그대로이고 캐시 키는 남아 있으며, 재조회 결과는 변경 전과 같다.")
        @Test
        void keepsDbAndCache_whenUpdateIsRolledBack() {
            // arrange
            fillCache();

            // act: 같은 트랜잭션 안에서 가격·이름을 바꾼 뒤 예외를 던져 전체를 rollback 한다
            assertThrows(IllegalStateException.class, () -> transactionTemplate.executeWithoutResult(status -> {
                productFacade.updateProduct(FIRST_PRODUCT_ID, "변경 시도", NEW_PRICE);
                throw new IllegalStateException("변경 뒤 실패를 흉내 낸다");
            }));

            // assert
            Map<String, Object> row = productRow(FIRST_PRODUCT_ID);
            assertThat(row.get("name")).isEqualTo("product-5406");
            assertThat(((Number) row.get("amount")).longValue()).isEqualTo(122L);
            assertThat(cacheRedisTemplate.hasKey(CACHE_KEY)).isTrue();
            assertThat(queryFixturePage()).containsExactlyElementsOf(ProductQueryFixture.EXPECTED_PAGE_0);
        }
    }

    @DisplayName("장면 B. commit 뒤 캐시 삭제가 실패하면, ")
    @Nested
    class EvictionFailsAfterCommit {
        @DisplayName("가격 수정은 성공 응답이고 DB 에는 새 가격이지만, 바로 조회하면 옛 가격이 보인다(정책상 허용). 만료 뒤 재조회는 DB 와 일치한다.")
        @Test
        void showsOldPriceUntilExpiry_thenRecovers() throws InterruptedException {
            // arrange
            fillCache();
            faultInjectingCache.setMode(FaultInjectingProductListCache.Mode.FAIL_EVICT_DELETE);

            // act
            ProductInfo updated = productFacade.updateProduct(FIRST_PRODUCT_ID, "새 이름", NEW_PRICE);
            boolean keyExistsAfterWrite = cacheRedisTemplate.hasKey(CACHE_KEY);
            List<ProductInfo> immediately = queryFixturePage();

            // assert: 쓰기는 성공, DB 는 새 값, 캐시 키는 남아 옛 가격이 보인다
            assertThat(updated.price()).isEqualTo(NEW_PRICE);
            assertThat(priceInDb(FIRST_PRODUCT_ID)).isEqualTo(NEW_PRICE);
            assertThat(keyExistsAfterWrite).isTrue();
            assertThat(immediately.get(0).productId()).isEqualTo(FIRST_PRODUCT_ID);
            assertThat(immediately.get(0).price()).isEqualTo(122L);
            assertThat(immediately.get(0).productName()).isEqualTo("product-5406");

            // act: TTL 만료 → 다음 조회는 miss 로 DB 를 읽는다
            expireCacheKey();
            List<ProductInfo> afterExpiry = queryFixturePage();

            // assert: 비싸진 5406 은 첫 페이지에서 빠진다
            assertThat(afterExpiry).containsExactlyElementsOf(expectedWithoutFirst());
            assertThat(cacheRedisTemplate.hasKey(CACHE_KEY)).isTrue();
        }

        @DisplayName("commit 뒤 삭제 실패 1회는 evict_error 만 1 늘고, 쓰기 자체는 성공이다.")
        @Test
        void countsEvictError() {
            // arrange
            fillCache();
            faultInjectingCache.setMode(FaultInjectingProductListCache.Mode.FAIL_EVICT_DELETE);
            CacheCounterSnapshot before = CacheCounterSnapshot.take(meterRegistry);

            // act
            productFacade.updateProduct(FIRST_PRODUCT_ID, "새 이름", NEW_PRICE);

            // assert
            assertThat(priceInDb(FIRST_PRODUCT_ID)).isEqualTo(NEW_PRICE);
            assertThat(before.deltaTo(CacheCounterSnapshot.take(meterRegistry)))
                .isEqualTo(CacheCounterSnapshot.only("evict_error"));
        }

        @DisplayName("상품 삭제라면 캐시 키가 남아 있어도 삭제 상품은 조회 결과에 없다 (hit 의 삭제 여부 확인).")
        @Test
        void hidesDeletedProduct_evenWhenCacheRemains() {
            // arrange
            fillCache();
            faultInjectingCache.setMode(FaultInjectingProductListCache.Mode.FAIL_EVICT_DELETE);

            // act
            productFacade.deleteProduct(FIRST_PRODUCT_ID);
            boolean keyExistsAfterWrite = cacheRedisTemplate.hasKey(CACHE_KEY);
            List<ProductInfo> result = queryFixturePage();

            // assert
            assertThat(productRow(FIRST_PRODUCT_ID).get("deleted_at")).isNotNull();
            assertThat(keyExistsAfterWrite).isTrue();
            assertThat(result).containsExactlyElementsOf(expectedWithoutFirst());
        }

        @DisplayName("옛 가격이 보이는 동안에도 주문 금액은 DB 의 새 가격이고, 주문 확정 뒤 재고는 새 값이다.")
        @Test
        void ordersUseDbPrice_andStockIsFresh_whileOldPriceIsShown() {
            // arrange
            Long userId = saveUserPoint(1L, 300_000L);
            fillCache();
            faultInjectingCache.setMode(FaultInjectingProductListCache.Mode.FAIL_EVICT_DELETE);
            productFacade.updateProduct(FIRST_PRODUCT_ID, "새 이름", NEW_PRICE);
            assertThat(queryFixturePage().get(0).price()).isEqualTo(122L);

            // act
            OrderInfo order = orderFacade.createOrder(userId, List.of(new OrderCommand.Item(FIRST_PRODUCT_ID, 2)));
            OrderInfo confirmed = orderFacade.confirmOrder(userId, order.orderId());
            List<ProductInfo> result = queryFixturePage();

            // assert
            assertThat(order.items().get(0).unitPrice()).isEqualTo(NEW_PRICE);
            assertThat(confirmed.paidAmount()).isEqualTo(NEW_PRICE * 2);
            assertThat(result.get(0).productId()).isEqualTo(FIRST_PRODUCT_ID);
            assertThat(result.get(0).price()).isEqualTo(122L);
            assertThat(result.get(0).stockQuantity()).isEqualTo(98);
        }
    }

    @DisplayName("장면 C. 이전 조회가 DB 를 읽은 뒤 관리자 변경이 commit 되고, 그 다음에 이전 결과가 캐시에 저장되면, ")
    @Nested
    class LateCacheFill {
        /**
         * 순서: 느린 조회가 DB 읽기 → (save 직전에 멈춤) → 관리자 변경 commit·무효화 → 느린 조회가 옛 결과 저장 → 새 조회.
         * 느린 조회는 별도 스레드에서 실제 ProductFacade.getProducts 로 실행하고, 포장지의 래치로 save 시점을 통제한다.
         */
        private List<ProductInfo> fillLateAround(Runnable adminWrite) throws Exception {
            faultInjectingCache.setMode(FaultInjectingProductListCache.Mode.HOLD_SAVE);
            ExecutorService slowReader = Executors.newSingleThreadExecutor();
            try {
                Future<List<ProductInfo>> staleRead = slowReader.submit(ProductCacheFailureTest.this::queryFixturePage);
                assertThat(faultInjectingCache.awaitSaveReached()).as("느린 조회가 DB 를 읽고 save 직전에 도착").isTrue();

                adminWrite.run();
                assertThat(cacheRedisTemplate.hasKey(CACHE_KEY)).as("변경 commit 직후에는 지울 키가 없다").isFalse();

                faultInjectingCache.releaseSave();
                List<ProductInfo> stale = staleRead.get(5, TimeUnit.SECONDS);
                assertThat(cacheRedisTemplate.hasKey(CACHE_KEY)).as("옛 결과가 늦게 저장됐다").isTrue();
                return stale;
            } finally {
                faultInjectingCache.releaseSave();
                slowReader.shutdownNow();
                assertThat(slowReader.awaitTermination(5, TimeUnit.SECONDS)).as("느린 조회 스레드 종료").isTrue();
            }
        }

        @DisplayName("가격 수정이면 새 조회에 옛 가격이 보인다(정책상 허용). 만료 뒤 재조회는 DB 와 일치한다.")
        @Test
        void showsStalePrice_thenRecoversAfterExpiry() throws Exception {
            // act
            List<ProductInfo> stale = fillLateAround(
                () -> productFacade.updateProduct(FIRST_PRODUCT_ID, "새 이름", NEW_PRICE));
            List<ProductInfo> afterLateFill = queryFixturePage();

            // assert: 느린 조회는 자신이 읽은 시점(변경 전)의 결과를, 새 조회는 늦게 저장된 옛 값을 본다
            assertThat(stale).containsExactlyElementsOf(ProductQueryFixture.EXPECTED_PAGE_0);
            assertThat(priceInDb(FIRST_PRODUCT_ID)).isEqualTo(NEW_PRICE);
            assertThat(afterLateFill.get(0).productId()).isEqualTo(FIRST_PRODUCT_ID);
            assertThat(afterLateFill.get(0).price()).isEqualTo(122L);

            // act: TTL 만료 → 다음 조회는 miss 로 DB 를 읽는다
            expireCacheKey();

            // assert
            assertThat(queryFixturePage()).containsExactlyElementsOf(expectedWithoutFirst());
        }

        @DisplayName("상품 삭제면 옛 결과가 늦게 저장돼도 삭제 상품은 새 조회 결과에 없다.")
        @Test
        void hidesDeletedProduct_evenAfterLateFill() throws Exception {
            // act
            List<ProductInfo> stale = fillLateAround(() -> productFacade.deleteProduct(FIRST_PRODUCT_ID));
            List<ProductInfo> afterLateFill = queryFixturePage();

            // assert
            assertThat(stale).extracting(ProductInfo::productId).contains(FIRST_PRODUCT_ID);
            assertThat(productRow(FIRST_PRODUCT_ID).get("deleted_at")).isNotNull();
            assertThat(afterLateFill).containsExactlyElementsOf(expectedWithoutFirst());
        }
    }
}
