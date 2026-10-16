package com.loopers.domain.product;

import com.loopers.domain.brand.BrandModel;
import com.loopers.fixture.SqlCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("commit 뒤 무효화 실패·늦은 채움으로 남은 옛 기본정보는 고정 TTL 만료까지 허용하고, 만료 뒤 DB 값으로 다시 채운다.")
class ProductCacheFailureIntegrationTest extends ProductCacheTestSupport {

    private ProductModel cachedProduct(long price) {
        BrandModel brand = brandFixture.createBrand("나이키");
        ProductModel product = productFixture.createProduct(brand.getId(), "운동화", price, 10L);
        productFacade.getProduct(product.getId());
        return product;
    }

    @DisplayName("commit 뒤 DEL 실패")
    @Nested
    @ExtendWith(OutputCaptureExtension.class)
    class EvictionFailure {
        @DisplayName("DB 변경은 성공으로 유지하고 로그만 남긴다. 옛 기본정보는 실제 만료까지 보이고 만료 뒤 새 값으로 다시 채운다.")
        @Test
        void keepsCommitAndRecoversAfterExpiry(CapturedOutput output) throws InterruptedException {
            ProductModel shoes = cachedProduct(1_000L);
            Long id = shoes.getId();
            long ttlBeforeUpdate = pttl(id);
            doThrow(new RedisConnectionFailureException("주입한 DEL 실패")).when(productDetailCache).evictAll(anyCollection());

            ProductQueryResult adminResponse = productFacade.update(id, "운동화", 2_000L);
            doAnswer(invocation -> invocation.callRealMethod()).when(productDetailCache).evictAll(anyCollection());

            long committedPrice = productJpaRepository.findById(id).orElseThrow().getPrice().toWon();
            ProductQueryResult staleHit = productFacade.getProduct(id);
            Duration waited = awaitExpiry(id, TTL.plusSeconds(2));

            SqlCapture.start();
            ProductQueryResult recovered = productFacade.getProduct(id);
            List<String> sql = SqlCapture.stop();
            System.out.println("[CACHE-DEL-FAIL] pttlBeforeUpdate=" + ttlBeforeUpdate + " staleHitPrice=" + staleHit.price()
                + " waitedForExpiryMs=" + waited.toMillis() + " recoveredPrice=" + recovered.price() + " json=" + rawValue(id));

            assertAll(
                () -> assertThat(adminResponse.price()).isEqualTo(2_000L),
                () -> assertThat(committedPrice).isEqualTo(2_000L),
                () -> assertThat(output).contains("commit 뒤 작업이 실패했습니다. DB commit 결과는 유지하고 재시도하지 않습니다.")
                    .contains("주입한 DEL 실패"),
                () -> assertThat(staleHit.price()).isEqualTo(1_000L),
                () -> assertThat(waited).isLessThanOrEqualTo(Duration.ofMillis(ttlBeforeUpdate).plusSeconds(1)),
                () -> assertThat(recovered.price()).isEqualTo(2_000L),
                () -> assertThat(count(sql, ProductCacheTestSupport::isFullDetailSql)).isEqualTo(1),
                () -> assertThat(rawValue(id)).contains("\"price\":2000")
            );
            verify(productDetailCache, times(1)).evictAll(List.of(id));
        }
    }

    @DisplayName("이전 조회의 늦은 채움")
    @Nested
    class LateFill {
        @DisplayName("옛 DB 읽기 → 가격 변경 commit·DEL → 옛 값 SET 순서를 재현하면, 옛 값이 새 TTL 로 실제 만료될 때까지 보이고 만료 뒤 DB 새 값으로 다시 채운다.")
        @Test
        void staleFillExpiresAndRefills() throws Exception {
            ProductModel shoes = cachedProduct(1_000L);
            Long id = shoes.getId();
            redisTemplate.delete(key(id));

            CountDownLatch oldReadDone = new CountDownLatch(1);
            CountDownLatch releaseFill = new CountDownLatch(1);
            AtomicBoolean firstPut = new AtomicBoolean(true);
            doAnswer(invocation -> {
                if (firstPut.compareAndSet(true, false)) {
                    oldReadDone.countDown();
                    assertThat(releaseFill.await(5, TimeUnit.SECONDS)).isTrue();
                }
                return invocation.callRealMethod();
            }).when(productDetailCache).put(eq(id), any());

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<ProductQueryResult> oldReader = executor.submit(() -> productFacade.getProduct(id));
                assertThat(oldReadDone.await(5, TimeUnit.SECONDS)).isTrue();

                productFacade.update(id, "운동화", 2_000L);
                boolean keyAfterCommitAndDel = hasKey(id);

                releaseFill.countDown();
                ProductQueryResult oldResponse = oldReader.get(5, TimeUnit.SECONDS);
                long staleSetAt = System.nanoTime();
                String staleJson = rawValue(id);

                List<Long> pttls = new ArrayList<>();
                List<Long> observedPrices = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    observedPrices.add(productFacade.getProduct(id).price());
                    pttls.add(pttl(id));
                    Thread.sleep(1_000);
                }
                awaitExpiry(id, TTL.plusSeconds(2));
                Duration staleLifetime = Duration.ofNanos(System.nanoTime() - staleSetAt);

                SqlCapture.start();
                ProductQueryResult afterExpiry = productFacade.getProduct(id);
                List<String> sql = SqlCapture.stop();
                System.out.println("[CACHE-LATE-FILL] keyAfterCommitAndDel=" + keyAfterCommitAndDel
                    + " oldResponsePrice=" + oldResponse.price() + " staleJson=" + staleJson
                    + " stalePrices=" + observedPrices + " pttls=" + pttls + " staleLifetimeMs=" + staleLifetime.toMillis()
                    + " afterExpiryPrice=" + afterExpiry.price() + " json=" + rawValue(id));

                assertAll(
                    () -> assertThat(keyAfterCommitAndDel).isFalse(),
                    () -> assertThat(oldResponse.price()).isEqualTo(1_000L),
                    () -> assertThat(staleJson).contains("\"price\":1000"),
                    () -> assertThat(observedPrices).containsOnly(1_000L),
                    () -> assertThat(pttls).isSortedAccordingTo((a, b) -> Long.compare(b, a)),
                    () -> assertThat(staleLifetime).isBetween(TTL.minusSeconds(1), TTL.plusSeconds(2)),
                    () -> assertThat(afterExpiry.price()).isEqualTo(2_000L),
                    () -> assertThat(count(sql, ProductCacheTestSupport::isFullDetailSql)).isEqualTo(1),
                    () -> assertThat(rawValue(id)).contains("\"price\":2000"),
                    () -> assertThat(productJpaRepository.findById(id).orElseThrow().getPrice().toWon()).isEqualTo(2_000L)
                );
            } finally {
                releaseFill.countDown();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }
}
