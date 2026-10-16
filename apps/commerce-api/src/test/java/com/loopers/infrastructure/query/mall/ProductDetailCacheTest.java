package com.loopers.infrastructure.query.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Ticker;
import com.loopers.application.mall.query.BrandSummaryView;
import com.loopers.application.mall.query.ProductDetailView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ProductDetailCacheTest {
    private final MutableTicker ticker = new MutableTicker();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProductDetailCache cache = createCache(true, 1000);

    @DisplayName("캐시 조회")
    @Nested
    class Find {
        @DisplayName("같은 상품의 전체 상세 응답을 재사용하고 DB 조회와 적중 지표를 기록한다")
        @Test
        void reusesFullDetailAndRecordsMetrics() {
            // arrange
            AtomicInteger loads = new AtomicInteger();
            ProductDetailView detail = detail(1, 7, "원본");
            Supplier<Optional<ProductDetailView>> loader = () -> {
                loads.incrementAndGet();
                return Optional.of(detail);
            };

            // act
            assertThat(cache.find(1, loader)).contains(detail);
            assertThat(cache.find(1, loader)).contains(detail);

            // assert
            assertThat(loads).hasValue(1);
            assertThat(registry.get("product.detail.db.loads").counter().count()).isEqualTo(1);
            assertThat(registry.get("cache.gets").tag("cache", "product-detail").tag("result", "hit")
                .functionCounter().count()).isEqualTo(1);
            assertThat(registry.get("cache.gets").tag("cache", "product-detail").tag("result", "miss")
                .functionCounter().count()).isEqualTo(1);
        }

        @DisplayName("읽어도 만료를 연장하지 않고 기록 시점부터 30초에 다시 조회한다")
        @Test
        void expiresAfterWriteWithoutExtendingOnRead() {
            // arrange
            ProductDetailView original = detail(1, 7, "원본");
            ProductDetailView updated = detail(1, 7, "수정");
            cache.find(1, () -> Optional.of(original));

            // act, assert
            ticker.advance(Duration.ofSeconds(29));
            assertThat(cache.find(1, () -> Optional.of(updated))).contains(original);
            ticker.advance(Duration.ofSeconds(1));
            assertThat(cache.find(1, () -> Optional.of(updated))).contains(updated);
            assertThat(registry.get("product.detail.db.loads").counter().count()).isEqualTo(2);
        }

        @DisplayName("없는 상품은 저장하지 않아 다음 요청에서 새 상품을 확인한다")
        @Test
        void doesNotCacheMissingProduct() {
            // arrange, act
            assertThat(cache.find(1, Optional::empty)).isEmpty();
            ProductDetailView created = detail(1, 7, "신규");

            // assert
            assertThat(cache.find(1, () -> Optional.of(created))).contains(created);
            assertThat(registry.get("product.detail.db.loads").counter().count()).isEqualTo(2);
        }

        @DisplayName("DB 예외를 전파하고 실패를 저장하지 않는다")
        @Test
        void doesNotCacheFailure() {
            // arrange
            IllegalStateException failure = new IllegalStateException("DB 실패");

            // act, assert
            assertThatThrownBy(() -> cache.find(1, () -> { throw failure; })).isSameAs(failure);
            ProductDetailView recovered = detail(1, 7, "복구");
            assertThat(cache.find(1, () -> Optional.of(recovered))).contains(recovered);
            assertThat(registry.get("product.detail.db.loads").counter().count()).isEqualTo(2);
        }

        @DisplayName("캐시를 끄면 저장하지 않고 매번 DB 조회 지표를 기록한다")
        @Test
        void bypassesWhenDisabled() {
            // arrange
            ProductDetailCache disabled = createCache(false, 1000);
            AtomicInteger loads = new AtomicInteger();

            // act
            disabled.find(1, () -> Optional.of(detail(1, 7, "값" + loads.incrementAndGet())));
            Optional<ProductDetailView> second = disabled.find(1,
                () -> Optional.of(detail(1, 7, "값" + loads.incrementAndGet())));

            // assert
            assertThat(second.orElseThrow().name()).isEqualTo("값2");
            assertThat(loads).hasValue(2);
            assertThat(registry.get("product.detail.db.loads").counter().count()).isEqualTo(2);
        }

        @DisplayName("이미 진행 중인 트랜잭션에서는 기존 캐시도 읽지 않고 조회한 값도 저장하지 않는다")
        @Test
        void bypassesExistingTransactionForReadAndWrite() {
            // arrange
            ProductDetailView cached = detail(1, 7, "캐시");
            ProductDetailView transactional = detail(1, 7, "트랜잭션");
            cache.find(1, () -> Optional.of(cached));

            // act, assert
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThat(cache.find(1, () -> Optional.of(transactional))).contains(transactional);
                cache.find(2, () -> Optional.of(detail(2, 7, "미커밋")));
            } finally {
                TransactionSynchronizationManager.clear();
            }
            assertThat(cache.find(1, () -> Optional.of(transactional))).contains(cached);
            assertThat(cache.find(2, Optional::empty)).isEmpty();
            assertThat(registry.get("product.detail.db.loads").counter().count()).isEqualTo(4);
        }

        @DisplayName("용량 제한 후 정리하면 저장된 항목 수가 최대 크기를 넘지 않는다")
        @Test
        void boundsStoredEntries() {
            // arrange
            ProductDetailCache limited = createCache(true, 2);

            // act
            for (long id = 1; id <= 100; id++) {
                ProductDetailView detail = detail(id, 7, "상품" + id);
                limited.find(id, () -> Optional.of(detail));
            }
            limited.cleanUp();

            // assert
            assertThat(limited.estimatedSize()).isLessThanOrEqualTo(2);
        }
    }

    @DisplayName("변경 후 무효화")
    @Nested
    class Invalidation {
        @DisplayName("지정한 상품만 제거한다")
        @Test
        void invalidatesOnlyTargetProducts() {
            // arrange
            cache.find(1, () -> Optional.of(detail(1, 7, "원본")));
            ProductDetailView untouched = detail(2, 7, "유지");
            cache.find(2, () -> Optional.of(untouched));

            // act
            cache.invalidateProducts(Set.of(1L));

            // assert
            assertThat(cache.find(1, Optional::empty)).isEmpty();
            assertThat(cache.find(2, Optional::empty)).contains(untouched);
        }

        @DisplayName("브랜드 변경은 해당 브랜드 응답만 제거한다")
        @Test
        void invalidatesOnlyTargetBrand() {
            // arrange
            cache.find(1, () -> Optional.of(detail(1, 7, "대상")));
            ProductDetailView untouched = detail(2, 8, "유지");
            cache.find(2, () -> Optional.of(untouched));

            // act
            cache.invalidateBrand(7);

            // assert
            assertThat(cache.find(1, Optional::empty)).isEmpty();
            assertThat(cache.find(2, Optional::empty)).contains(untouched);
        }

        @DisplayName("전체 재집계는 모든 상품을 제거한다")
        @Test
        void invalidatesAllProducts() {
            // arrange
            cache.find(1, () -> Optional.of(detail(1, 7, "상품")));
            cache.find(2, () -> Optional.of(detail(2, 8, "상품")));

            // act
            cache.invalidateAll();

            // assert
            assertThat(cache.find(1, Optional::empty)).isEmpty();
            assertThat(cache.find(2, Optional::empty)).isEmpty();
        }

        @DisplayName("무효화와 겹친 늦은 DB 응답은 반환하되 다시 저장하지 않는다")
        @Test
        void preventsLateFillForEveryInvalidationKind() throws Exception {
            assertLateFillSkipped(() -> cache.invalidateProducts(Set.of(1L)));
            assertLateFillSkipped(() -> cache.invalidateProducts(Set.of()));
            assertLateFillSkipped(() -> cache.invalidateBrand(7));
            assertLateFillSkipped(cache::invalidateAll);
        }

        private void assertLateFillSkipped(Runnable invalidation) throws Exception {
            // arrange
            cache.invalidateAll();
            CountDownLatch loading = new CountDownLatch(1);
            CountDownLatch finishLoading = new CountDownLatch(1);
            ProductDetailView stale = detail(1, 7, "이전");
            try (var executor = Executors.newSingleThreadExecutor()) {
                var pending = executor.submit(() -> cache.find(1, () -> {
                    loading.countDown();
                    try {
                        if (!finishLoading.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("조회 재개 시간 초과");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return Optional.of(stale);
                }));
                try {
                    assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();

                    // act
                    invalidation.run();
                } finally {
                    finishLoading.countDown();
                }

                // assert
                assertThat(pending.get(5, TimeUnit.SECONDS)).contains(stale);
                ProductDetailView fresh = detail(1, 7, "변경 후");
                assertThat(cache.find(1, () -> Optional.of(fresh))).contains(fresh);
                assertThat(cache.find(1, Optional::empty)).contains(fresh);
            }
        }
    }

    private ProductDetailCache createCache(boolean enabled, long maximumSize) {
        return new ProductDetailCache(new ProductDetailCacheProperties(enabled, maximumSize, Duration.ofSeconds(30)),
            registry, ticker);
    }

    private ProductDetailView detail(long productId, long brandId, String name) {
        return new ProductDetailView(productId, name, 1000, new BrandSummaryView(brandId, "브랜드"), 3, "설명", 10);
    }

    private static final class MutableTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong();

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }

        @Override
        public long read() {
            return nanos.get();
        }
    }
}
