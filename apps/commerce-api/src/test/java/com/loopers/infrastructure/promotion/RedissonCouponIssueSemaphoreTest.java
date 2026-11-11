package com.loopers.infrastructure.promotion;

import com.loopers.application.promotion.CouponIssueLimiterProperties;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DR-61 Redisson 세마포어 (RPermitExpirableSemaphore). 실제 Redis.
 * 실행: ./gradlew :apps:commerce-api:test --tests '*RedissonCouponIssueSemaphoreTest' (Docker 필요, Testcontainers).
 */
@SpringBootTest
class RedissonCouponIssueSemaphoreTest {
    private static final Long PROMOTION_ID = 1L;
    private static final Duration LONG_LEASE = Duration.ofSeconds(30);
    private static final Duration NO_WAIT = Duration.ZERO;
    /** Redisson permitId 형식(16 바이트 hex)이지만 발급된 적 없는 값. */
    private static final String UNKNOWN_PERMIT_ID = "00000000000000000000000000000000";

    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    private RedissonCouponIssueSemaphore semaphore(int permits, Duration lease, Duration waitTime) {
        return new RedissonCouponIssueSemaphore(redissonClient, new CouponIssueLimiterProperties(permits, lease, waitTime));
    }

    private int holders(Long promotionId) {
        return redissonClient.getPermitExpirableSemaphore(RedissonCouponIssueSemaphore.key(promotionId)).acquiredPermits();
    }

    @DisplayName("permit 수까지만 얻고, 그다음은 실패한다. 토큰은 서로 다르다.")
    @Test
    void acquiresUpToPermits() {
        RedissonCouponIssueSemaphore semaphore = semaphore(3, LONG_LEASE, NO_WAIT);

        List<Optional<String>> tokens = IntStream.range(0, 3).mapToObj(i -> semaphore.tryAcquire(PROMOTION_ID)).toList();

        assertThat(tokens).allMatch(Optional::isPresent);
        assertThat(tokens.stream().map(Optional::get).distinct()).hasSize(3);
        assertThat(semaphore.tryAcquire(PROMOTION_ID)).isEmpty();
        assertThat(holders(PROMOTION_ID)).isEqualTo(3);
    }

    @DisplayName("반납하면 하나를 다시 얻을 수 있다.")
    @Test
    void releaseFreesOnePermit() {
        RedissonCouponIssueSemaphore semaphore = semaphore(1, LONG_LEASE, NO_WAIT);
        String token = semaphore.tryAcquire(PROMOTION_ID).orElseThrow();
        assertThat(semaphore.tryAcquire(PROMOTION_ID)).isEmpty();

        semaphore.release(PROMOTION_ID, token);

        assertThat(semaphore.tryAcquire(PROMOTION_ID)).isPresent();
    }

    @DisplayName("남의 토큰(모르는 토큰)으로 반납하면 아무 허가도 풀리지 않고, 예외도 없다.")
    @Test
    void releaseWithUnknownToken_removesNothing() {
        RedissonCouponIssueSemaphore semaphore = semaphore(1, LONG_LEASE, NO_WAIT);
        semaphore.tryAcquire(PROMOTION_ID).orElseThrow();

        assertThatCode(() -> semaphore.release(PROMOTION_ID, UNKNOWN_PERMIT_ID)).doesNotThrowAnyException();

        assertThat(holders(PROMOTION_ID)).isEqualTo(1);
        assertThat(semaphore.tryAcquire(PROMOTION_ID)).isEmpty();
    }

    @DisplayName("lease 가 지난 허가는 반납하지 않아도 다음 획득 때 회수된다.")
    @Test
    void expiredLease_isReclaimed() throws InterruptedException {
        RedissonCouponIssueSemaphore semaphore = semaphore(1, Duration.ofMillis(200), NO_WAIT);
        semaphore.tryAcquire(PROMOTION_ID).orElseThrow();
        assertThat(semaphore.tryAcquire(PROMOTION_ID)).isEmpty();

        Thread.sleep(300);

        assertThat(semaphore.tryAcquire(PROMOTION_ID)).isPresent();
        assertThat(holders(PROMOTION_ID)).isEqualTo(1);
    }

    @DisplayName("허가가 없으면 기다리다가, waitTime 안에 반납되면 그때 얻는다.")
    @Test
    void waitsUntilReleased() throws Exception {
        RedissonCouponIssueSemaphore semaphore = semaphore(1, LONG_LEASE, Duration.ofSeconds(3));
        String held = semaphore.tryAcquire(PROMOTION_ID).orElseThrow();
        CompletableFuture.runAsync(() -> semaphore.release(PROMOTION_ID, held),
            CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS));

        long start = System.nanoTime();
        Optional<String> token = semaphore.tryAcquire(PROMOTION_ID);
        Duration waited = Duration.ofNanos(System.nanoTime() - start);

        assertThat(token).isPresent();
        assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(250)).isLessThan(Duration.ofSeconds(3));
    }

    @DisplayName("허가가 없으면 기다리다가, waitTime 안에 lease 가 지나면 그 허가를 얻는다.")
    @Test
    void waitsUntilLeaseExpires() {
        RedissonCouponIssueSemaphore semaphore = semaphore(1, Duration.ofMillis(300), Duration.ofSeconds(3));
        semaphore.tryAcquire(PROMOTION_ID).orElseThrow();

        long start = System.nanoTime();
        Optional<String> token = semaphore.tryAcquire(PROMOTION_ID);
        Duration waited = Duration.ofNanos(System.nanoTime() - start);

        assertThat(token).isPresent();
        assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(200)).isLessThan(Duration.ofSeconds(3));
    }

    @DisplayName("waitTime 동안 허가가 돌아오지 않으면 empty 다.")
    @Test
    void waitTimeElapsed_returnsEmpty() {
        RedissonCouponIssueSemaphore semaphore = semaphore(1, LONG_LEASE, Duration.ofMillis(300));
        semaphore.tryAcquire(PROMOTION_ID).orElseThrow();

        long start = System.nanoTime();
        Optional<String> token = semaphore.tryAcquire(PROMOTION_ID);
        Duration waited = Duration.ofNanos(System.nanoTime() - start);

        assertThat(token).isEmpty();
        assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }

    @DisplayName("프로모션마다 따로 센다.")
    @Test
    void countsPerPromotion() {
        RedissonCouponIssueSemaphore semaphore = semaphore(1, LONG_LEASE, NO_WAIT);
        semaphore.tryAcquire(1L).orElseThrow();

        assertThat(semaphore.tryAcquire(2L)).isPresent();
        assertThat(semaphore.tryAcquire(1L)).isEmpty();
    }

    @DisplayName("세마포어 키가 사라지면 다음 획득 때 permit 수를 다시 넣는다.")
    @Test
    void keyLost_permitsAreSetAgain() {
        RedissonCouponIssueSemaphore semaphore = semaphore(1, LONG_LEASE, NO_WAIT);
        semaphore.tryAcquire(PROMOTION_ID).orElseThrow();

        redisCleanUp.truncateAll();

        assertThat(semaphore.tryAcquire(PROMOTION_ID)).isPresent();
        assertThat(semaphore.tryAcquire(PROMOTION_ID)).isEmpty();
    }

    @DisplayName("50 개 스레드가 동시에 permit 10 을 시도하면 정확히 10 개만 얻는다.")
    @Test
    void concurrentAcquire_neverExceedsPermits() throws Exception {
        int threads = 50;
        RedissonCouponIssueSemaphore semaphore = semaphore(10, LONG_LEASE, NO_WAIT);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return semaphore.tryAcquire(PROMOTION_ID);
                }));
            }
            start.countDown();
            long acquired = 0;
            for (Future<Optional<String>> future : futures) {
                acquired += future.get().isPresent() ? 1 : 0;
            }

            assertThat(acquired).isEqualTo(10);
            assertThat(holders(PROMOTION_ID)).isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
    }

    @DisplayName("[fail-closed] Redis 오류면 획득은 실패(empty)로, 반납은 예외 없이 끝난다.")
    @Test
    void redisFailure_acquireFailsAndReleaseSwallows() {
        RPermitExpirableSemaphore brokenSemaphore = mock(RPermitExpirableSemaphore.class, invocation -> {
            throw new RedisConnectionException("down");
        });
        RedissonClient broken = mock(RedissonClient.class);
        when(broken.getPermitExpirableSemaphore(anyString())).thenReturn(brokenSemaphore);
        RedissonCouponIssueSemaphore semaphore = new RedissonCouponIssueSemaphore(
            broken, new CouponIssueLimiterProperties(10, LONG_LEASE, NO_WAIT));

        assertThat(semaphore.tryAcquire(PROMOTION_ID)).isEmpty();
        assertThatCode(() -> semaphore.release(PROMOTION_ID, "t")).doesNotThrowAnyException();
    }
}
