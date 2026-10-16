package com.loopers.domain.product;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.user.UserModel;
import com.loopers.fixture.SqlCapture;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("고객 상품 상세는 기본정보만 캐시하고 노출 확인·좋아요 수는 DB 에서 읽어 합성한다.")
class ProductCacheReadIntegrationTest extends ProductCacheTestSupport {

    private record Prepared(BrandModel brand, ProductModel product) {
    }

    /** 좋아요 2개가 있는 상품. */
    private Prepared prepare() {
        BrandModel brand = brandFixture.createBrand("나이키");
        ProductModel product = productFixture.createProduct(brand.getId(), "운동화", 89_000L, 7L);
        UserModel first = userFixture.createUserWithPoint();
        UserModel second = userFixture.createUserWithPoint();
        likeFacade.like(first.getId(), product.getId());
        likeFacade.like(second.getId(), product.getId());
        return new Prepared(brand, product);
    }

    @DisplayName("DB-only·miss·hit 비교")
    @Nested
    class Modes {
        @DisplayName("세 경로의 응답이 같고, hit 는 기본정보를 읽는 완성 상세 SQL 없이 노출 확인·좋아요 수 SQL 만 실행한다.")
        @Test
        void sameResultAndFewerSourceReadsOnHit() {
            Prepared prepared = prepare();
            Long id = prepared.product().getId();
            ProductQueryResult expected = new ProductQueryResult(
                id, prepared.brand().getId(), "나이키", "운동화", 89_000L, 2L, 7L);

            // DB-only: 비교 스위치를 끈 경로. 캐시를 읽거나 채우지 않는다.
            doReturn(false).when(productDetailCache).isEnabled();
            SqlCapture.start();
            ProductQueryResult dbOnly = productFacade.getProduct(id);
            List<String> dbOnlySql = SqlCapture.stop();
            boolean keyAfterDbOnly = hasKey(id);
            doAnswer(invocation -> invocation.callRealMethod()).when(productDetailCache).isEnabled();

            SqlCapture.start();
            ProductQueryResult miss = productFacade.getProduct(id);
            List<String> missSql = SqlCapture.stop();
            String storedJson = rawValue(id);
            long ttlAfterFill = pttl(id);

            SqlCapture.start();
            ProductQueryResult hit = productFacade.getProduct(id);
            List<String> hitSql = SqlCapture.stop();

            System.out.println("[CACHE-MODES] dbOnly=" + dbOnlySql);
            System.out.println("[CACHE-MODES] miss=" + missSql);
            System.out.println("[CACHE-MODES] hit=" + hitSql);
            System.out.println("[CACHE-MODES] key=" + key(id) + " json=" + storedJson + " pttlAfterFill=" + ttlAfterFill);

            assertAll(
                () -> assertThat(dbOnly).isEqualTo(expected),
                () -> assertThat(miss).isEqualTo(expected),
                () -> assertThat(hit).isEqualTo(expected),
                () -> assertThat(keyAfterDbOnly).isFalse(),
                () -> assertThat(dbOnlySql).hasSize(1),
                () -> assertThat(count(dbOnlySql, ProductCacheTestSupport::isFullDetailSql)).isEqualTo(1),
                () -> assertThat(missSql).hasSize(1),
                () -> assertThat(count(missSql, ProductCacheTestSupport::isFullDetailSql)).isEqualTo(1),
                () -> assertThat(hitSql).hasSize(1),
                () -> assertThat(count(hitSql, ProductCacheTestSupport::isFullDetailSql)).isZero(),
                () -> assertThat(count(hitSql, ProductCacheTestSupport::isVisibleWithLikeCountSql)).isEqualTo(1),
                () -> assertThat(storedJson)
                    .isEqualTo("{\"name\":\"운동화\",\"brandName\":\"나이키\",\"price\":89000,\"stockQuantity\":7}"),
                () -> assertThat(ttlAfterFill).isBetween(1L, TTL.toMillis())
            );
            verify(productDetailCache, times(1)).put(eq(id), any());
        }

        @DisplayName("hit 이어도 좋아요 수는 DB 에서 다시 집계한다.")
        @Test
        void likeCountComesFromDatabaseOnHit() {
            Prepared prepared = prepare();
            Long id = prepared.product().getId();
            productFacade.getProduct(id);
            UserModel third = userFixture.createUserWithPoint();

            likeFacade.like(third.getId(), id);

            assertAll(
                () -> assertThat(hasKey(id)).isTrue(),
                () -> assertThat(productFacade.getProduct(id).likeCount()).isEqualTo(3L)
            );
        }

        @DisplayName("없는 상품은 기본정보를 채우지 않고 404 PRODUCT_NOT_FOUND 로 거절한다.")
        @Test
        void doesNotFillUnknownProduct() {
            assertThatThrownBy(() -> productFacade.getProduct(999_999L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.PRODUCT_NOT_FOUND);
            assertThat(hasKey(999_999L)).isFalse();
            verify(productDetailCache, never()).put(any(), any());
        }
    }

    @DisplayName("저장 형식 오류")
    @Nested
    @ExtendWith(OutputCaptureExtension.class)
    class MalformedPayload {
        @DisplayName("JSON 이 아니거나, 필요한 값이 없거나, JSON null 이면 miss 로 보고 DB 값으로 응답·재채움하며 로그를 남긴다.")
        @Test
        void treatsMalformedPayloadAsMiss(CapturedOutput output) {
            Prepared prepared = prepare();
            Long id = prepared.product().getId();

            for (String malformed : List.of("not-json", "{\"name\":\"운동화\",\"price\":1}", "", "null")) {
                redisTemplate.opsForValue().set(key(id), malformed, TTL);

                SqlCapture.start();
                ProductQueryResult result = productFacade.getProduct(id);
                List<String> sql = SqlCapture.stop();

                assertAll(
                    () -> assertThat(result.price()).isEqualTo(89_000L),
                    () -> assertThat(result.brandName()).isEqualTo("나이키"),
                    () -> assertThat(count(sql, ProductCacheTestSupport::isFullDetailSql)).isEqualTo(1),
                    () -> assertThat(rawValue(id))
                        .isEqualTo("{\"name\":\"운동화\",\"brandName\":\"나이키\",\"price\":89000,\"stockQuantity\":7}")
                );
            }
            assertThat(output).contains("형식이 올바르지 않아 miss 로 처리하고 DB 원본으로 다시 채웁니다");
        }

        @DisplayName("DB 조회가 실패하면 오류를 그대로 전파하고 빈 값·0 으로 숨기지 않는다.")
        @Test
        void propagatesDatabaseFailure() {
            Prepared prepared = prepare();
            Long id = prepared.product().getId();
            RuntimeException dbFailure = new IllegalStateException("주입한 DB 조회 실패");
            doAnswer(invocation -> {
                throw dbFailure;
            }).when(productQueryRepository).findDetail(id);

            assertThatThrownBy(() -> productFacade.getProduct(id)).isSameAs(dbFailure);
            assertThat(hasKey(id)).isFalse();
        }
    }

    @DisplayName("고정 TTL")
    @Nested
    class FixedTtl {
        @DisplayName("반복 hit 로 만료가 늘어나지 않고, 저장 시각 기준으로 만료된 뒤 다음 조회가 DB 로 다시 채운다.")
        @Test
        void hitDoesNotExtendExpiry() throws InterruptedException {
            Prepared prepared = prepare();
            Long id = prepared.product().getId();
            productFacade.getProduct(id);
            long filledAt = System.nanoTime();

            List<Long> observed = new ArrayList<>();
            observed.add(pttl(id));
            for (int i = 0; i < 4; i++) {
                Thread.sleep(1_000);
                productFacade.getProduct(id);
                observed.add(pttl(id));
            }
            Duration remainingWait = awaitExpiry(id, TTL.plusSeconds(2));
            Duration lifetime = Duration.ofNanos(System.nanoTime() - filledAt);
            System.out.println("[CACHE-TTL] pttl after each hit=" + observed + " lifetimeMs=" + lifetime.toMillis()
                + " waitedAfterHitsMs=" + remainingWait.toMillis());

            SqlCapture.start();
            ProductQueryResult refilled = productFacade.getProduct(id);
            List<String> sql = SqlCapture.stop();

            assertAll(
                () -> assertThat(observed).isSortedAccordingTo((a, b) -> Long.compare(b, a)),
                () -> assertThat(observed.get(observed.size() - 1)).isLessThan(observed.get(0) - 3_000),
                () -> assertThat(lifetime).isBetween(TTL.minusSeconds(1), TTL.plusSeconds(2)),
                () -> assertThat(refilled.price()).isEqualTo(89_000L),
                () -> assertThat(count(sql, ProductCacheTestSupport::isFullDetailSql)).isEqualTo(1),
                () -> assertThat(pttl(id)).isGreaterThan(TTL.toMillis() - 2_000)
            );
        }
    }

    @DisplayName("cold 동시 miss 관측 — 조회 공유·잠금 없음")
    @Nested
    class ColdConcurrentMiss {
        private static final int THREADS = 8;

        @DisplayName("모든 요청이 miss 를 확인한 뒤 진행하도록 통제하면 각 요청이 완성 상세 SQL 을 실행하고 같은 값을 채운다.")
        @Test
        void everyControlledMissReadsSource() throws Exception {
            Prepared prepared = prepare();
            Long id = prepared.product().getId();
            CyclicBarrier allMissed = new CyclicBarrier(THREADS);
            doAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                Optional<ProductDetailData.CacheValue> found = (Optional<ProductDetailData.CacheValue>) invocation.callRealMethod();
                if (found.isEmpty()) {
                    allMissed.await(5, TimeUnit.SECONDS);
                }
                return found;
            }).when(productDetailCache).find(id);

            SqlCapture.start();
            List<ProductQueryResult> results = runConcurrently(id);
            List<String> sql = SqlCapture.stop();
            long sourceReads = count(sql, ProductCacheTestSupport::isFullDetailSql);
            System.out.println("[CACHE-COLD] controlled threads=" + THREADS + " fullDetailSql=" + sourceReads
                + " visibleWithLikeCountSql=" + count(sql, ProductCacheTestSupport::isVisibleWithLikeCountSql));

            doAnswer(invocation -> invocation.callRealMethod()).when(productDetailCache).find(id);
            SqlCapture.start();
            productFacade.getProduct(id);
            List<String> warmSql = SqlCapture.stop();

            assertAll(
                () -> assertThat(results).hasSize(THREADS).allMatch(result -> result.equals(results.get(0))),
                () -> assertThat(results.get(0).price()).isEqualTo(89_000L),
                () -> assertThat(sourceReads).isEqualTo(THREADS),
                () -> assertThat(count(warmSql, ProductCacheTestSupport::isFullDetailSql)).isZero(),
                () -> assertThat(count(warmSql, ProductCacheTestSupport::isVisibleWithLikeCountSql)).isEqualTo(1)
            );
            verify(productDetailCache, times(THREADS)).put(eq(id), any());
        }

        @DisplayName("동시 시작만 맞추면 miss 수는 실행마다 달라질 수 있으며, 실제 원본 조회 수를 기록한다.")
        @Test
        void observesUncontrolledConcurrentStart() throws Exception {
            Prepared prepared = prepare();
            Long id = prepared.product().getId();

            SqlCapture.start();
            List<ProductQueryResult> results = runConcurrently(id);
            List<String> sql = SqlCapture.stop();
            long sourceReads = count(sql, ProductCacheTestSupport::isFullDetailSql);
            long visibleReads = count(sql, ProductCacheTestSupport::isVisibleWithLikeCountSql);
            System.out.println("[CACHE-COLD] start-only threads=" + THREADS + " fullDetailSql=" + sourceReads
                + " visibleWithLikeCountSql=" + visibleReads);

            assertAll(
                () -> assertThat(results).hasSize(THREADS).allMatch(result -> result.equals(results.get(0))),
                () -> assertThat(sourceReads).isBetween(1L, (long) THREADS),
                () -> assertThat(sourceReads + visibleReads).isEqualTo(THREADS)
            );
        }

        private List<ProductQueryResult> runConcurrently(Long id) throws Exception {
            ExecutorService executor = Executors.newFixedThreadPool(THREADS);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<ProductQueryResult>> futures = new ArrayList<>();
                for (int i = 0; i < THREADS; i++) {
                    futures.add(executor.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        return productFacade.getProduct(id);
                    }));
                }
                start.countDown();
                List<ProductQueryResult> results = new ArrayList<>();
                for (Future<ProductQueryResult> future : futures) {
                    results.add(future.get(10, TimeUnit.SECONDS));
                }
                return results;
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }
}
