package com.loopers.infrastructure.product;

import com.loopers.config.ProductCacheRedisConfig;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.ProductView;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 상품 목록 캐시를 실제 Redis 로 확인함 (4주차 docs/week4/cache-design.md).
 * 원본은 호출 수를 세는 loader 로 대신한다. 조회 경로 · 쓰기 경로와의 연결은 Facade 테스트에서 확인함
 */
@SpringBootTest
class RedisProductListCacheTest {

    private static final long BRAND = 7L;
    private static final long OTHER_BRAND = 8L;

    @Autowired
    private RedisProductListCache cache;

    @Autowired
    @Qualifier(ProductCacheRedisConfig.MASTER_TEMPLATE)
    private StringRedisTemplate redis;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private Clock clock;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    /** 원본 대역. 부를 때마다 수를 세고 같은 페이지를 돌려준다 */
    private static final class CountingLoader implements Supplier<Page<ProductView>> {
        private final AtomicInteger calls = new AtomicInteger();
        private final Page<ProductView> page;

        private CountingLoader(Page<ProductView> page) {
            this.page = page;
        }

        @Override
        public Page<ProductView> get() {
            calls.incrementAndGet();
            return page;
        }

        int calls() {
            return calls.get();
        }
    }

    private static Page<ProductView> page(long... productIds) {
        List<ProductView> views = Arrays.stream(productIds)
            .mapToObj(id -> new ProductView(id, "상품" + id, 1_000L * id, (int) (id % 2), id * 3, BRAND, "브랜드", null))
            .toList();
        return new PageImpl<>(views, PageRequest.of(0, 20), 94);
    }

    @DisplayName("첫 페이지를 읽을 때, ")
    @Nested
    class GetFirstPage {
        @DisplayName("처음에는 원본을 읽어 저장하고, 다음에는 원본 없이 같은 결과를 돌려준다.")
        @Test
        void loadsOnce_andReturnsSamePageFromCache() {
            // arrange
            CountingLoader loader = new CountingLoader(page(1, 2, 3));

            // act
            Page<ProductView> miss = cache.getFirstPage(BRAND, ProductSort.PRICE_ASC, loader);
            Page<ProductView> hit = cache.getFirstPage(BRAND, ProductSort.PRICE_ASC, loader);

            // assert
            assertAll(
                () -> assertThat(loader.calls()).isEqualTo(1),
                () -> assertThat(hit.getContent()).isEqualTo(miss.getContent()),
                () -> assertThat(hit.getTotalElements()).isEqualTo(94),
                () -> assertThat(hit.getNumber()).isZero(),
                () -> assertThat(hit.getSize()).isEqualTo(20),
                () -> assertThat(hit.getContent().get(0).brandDescription()).isNull()
            );
        }

        @DisplayName("정렬이 다르면, 서로 다른 캐시다.")
        @Test
        void keepsSortsSeparate() {
            // arrange
            CountingLoader byPrice = new CountingLoader(page(1, 2));
            CountingLoader byLatest = new CountingLoader(page(2, 1));
            cache.getFirstPage(BRAND, ProductSort.PRICE_ASC, byPrice);

            // act
            Page<ProductView> result = cache.getFirstPage(BRAND, ProductSort.LATEST, byLatest);

            // assert
            assertAll(
                () -> assertThat(byLatest.calls()).isEqualTo(1),
                () -> assertThat(result.getContent()).extracting(ProductView::id).containsExactly(2L, 1L)
            );
        }

        @DisplayName("원본을 읽다 실패하면, 그 오류가 그대로 나가고 아무것도 저장하지 않는다.")
        @Test
        void propagatesLoaderFailure_andStoresNothing() {
            // arrange
            IllegalStateException failure = new IllegalStateException("원본 실패");

            // act
            IllegalStateException result = assertThrows(IllegalStateException.class,
                () -> cache.getFirstPage(BRAND, ProductSort.PRICE_ASC, () -> {
                    throw failure;
                }));
            CountingLoader loader = new CountingLoader(page(1));
            cache.getFirstPage(BRAND, ProductSort.PRICE_ASC, loader);

            // assert
            assertAll(
                () -> assertThat(result).isSameAs(failure),
                () -> assertThat(loader.calls()).isEqualTo(1)
            );
        }

        @DisplayName("저장된 값을 읽을 수 없으면, 원본으로 우회하고 다음 저장으로 바로잡는다.")
        @Test
        void bypassesUnreadableEntry() {
            // arrange
            CountingLoader loader = new CountingLoader(page(1, 2));
            cache.getFirstPage(BRAND, ProductSort.PRICE_ASC, loader);
            long version = Long.parseLong(redis.opsForValue().get(RedisProductListCache.versionKey("brand=" + BRAND)));
            redis.opsForValue().set(RedisProductListCache.dataKey("brand=" + BRAND, version, ProductSort.PRICE_ASC), "{깨진 값");

            // act
            Page<ProductView> result = cache.getFirstPage(BRAND, ProductSort.PRICE_ASC, loader);

            // assert
            assertAll(
                () -> assertThat(loader.calls()).isEqualTo(2),
                () -> assertThat(result.getContent()).extracting(ProductView::id).containsExactly(1L, 2L)
            );
        }
    }

    @DisplayName("범위 버전이 없을 때, ")
    @Nested
    class VersionInitialization {
        @DisplayName("현재 시각(ms) 이상의 값으로 만든다. 0 · 1 부터 세지 않는다. (3.5)")
        @Test
        void createsVersionFromCurrentMillis() {
            // arrange
            long before = clock.millis();

            // act
            cache.getFirstPage(BRAND, ProductSort.PRICE_ASC, new CountingLoader(page(1)));

            // assert
            assertThat(Long.parseLong(redis.opsForValue().get(RedisProductListCache.versionKey("brand=" + BRAND))))
                .isGreaterThanOrEqualTo(before);
        }

        @DisplayName("버전 키 없이 무효화해도, 현재 시각 기준으로 만든 뒤 올린다.")
        @Test
        void invalidatesFromCurrentMillis_whenVersionIsMissing() {
            // arrange
            long before = clock.millis();

            // act
            cache.invalidate(BRAND);

            // assert
            assertThat(Long.parseLong(redis.opsForValue().get(RedisProductListCache.versionKey("brand=" + BRAND))))
                .isGreaterThan(before);
        }
    }

    @DisplayName("무효화할 때, ")
    @Nested
    class Invalidate {
        @DisplayName("트랜잭션 밖이면 바로, 그 브랜드와 전체 목록만 다시 원본을 읽게 한다. (3.2)")
        @Test
        void invalidatesBrandAndAll_only() {
            // arrange
            CountingLoader brand = new CountingLoader(page(1));
            CountingLoader otherBrand = new CountingLoader(page(2));
            CountingLoader all = new CountingLoader(page(1, 2));
            cache.getFirstPage(BRAND, ProductSort.PRICE_ASC, brand);
            cache.getFirstPage(OTHER_BRAND, ProductSort.PRICE_ASC, otherBrand);
            cache.getFirstPage(null, ProductSort.PRICE_ASC, all);

            // act
            cache.invalidate(BRAND);
            cache.getFirstPage(BRAND, ProductSort.PRICE_ASC, brand);
            cache.getFirstPage(OTHER_BRAND, ProductSort.PRICE_ASC, otherBrand);
            cache.getFirstPage(null, ProductSort.PRICE_ASC, all);

            // assert
            assertAll(
                () -> assertThat(brand.calls()).isEqualTo(2),
                () -> assertThat(otherBrand.calls()).isEqualTo(1),
                () -> assertThat(all.calls()).isEqualTo(2)
            );
        }

        @DisplayName("트랜잭션 안이면 commit 뒤에 한다. commit 전에는 이전 캐시를 그대로 읽는다. (3.4)")
        @Test
        void invalidatesAfterCommit() {
            // arrange
            CountingLoader loader = new CountingLoader(page(1));
            cache.getFirstPage(BRAND, ProductSort.PRICE_ASC, loader);

            // act
            transactionTemplate.executeWithoutResult(status -> {
                cache.invalidate(BRAND);
                cache.getFirstPage(BRAND, ProductSort.PRICE_ASC, loader);
            });
            int callsBeforeCommit = loader.calls();
            cache.getFirstPage(BRAND, ProductSort.PRICE_ASC, loader);

            // assert
            assertAll(
                () -> assertThat(callsBeforeCommit).isEqualTo(1),
                () -> assertThat(loader.calls()).isEqualTo(2)
            );
        }

        @DisplayName("트랜잭션이 rollback 되면 하지 않는다. (3.4)")
        @Test
        void doesNotInvalidate_whenRolledBack() {
            // arrange
            CountingLoader loader = new CountingLoader(page(1));
            cache.getFirstPage(BRAND, ProductSort.PRICE_ASC, loader);

            // act
            transactionTemplate.executeWithoutResult(status -> {
                cache.invalidate(BRAND);
                status.setRollbackOnly();
            });
            cache.getFirstPage(BRAND, ProductSort.PRICE_ASC, loader);

            // assert
            assertAll(
                () -> assertThat(loader.calls()).isEqualTo(1),
                () -> assertThat(cache.hasPendingInvalidation(BRAND)).isFalse()
            );
        }
    }
}
