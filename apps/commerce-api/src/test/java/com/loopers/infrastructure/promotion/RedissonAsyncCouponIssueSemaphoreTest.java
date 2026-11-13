package com.loopers.infrastructure.promotion;

import com.loopers.application.promotion.CouponIssueLimiterProperties;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.misc.CompletableFutureWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DR-63 Redisson async 세마포어 (RPermitExpirableSemaphore 의 async API). 실제 Redis.
 * 실행: ./gradlew :apps:commerce-api:test --tests '*RedissonAsyncCouponIssueSemaphoreTest' (Docker 필요, Testcontainers).
 */
@SpringBootTest
class RedissonAsyncCouponIssueSemaphoreTest {
    private static final Long PROMOTION_ID = 1L;
    private static final Duration LONG_LEASE = Duration.ofSeconds(30);
    private static final Duration NO_WAIT = Duration.ZERO;
    private static final CouponIssueLimiterProperties.ExecutorProperties EXECUTOR = new CouponIssueLimiterProperties.ExecutorProperties(1, 1);
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

    private RedissonAsyncCouponIssueSemaphore semaphore(int permits, Duration lease, Duration waitTime) {
        return new RedissonAsyncCouponIssueSemaphore(redissonClient, new CouponIssueLimiterProperties(permits, lease, waitTime, EXECUTOR));
    }

    private int holders(Long promotionId) {
        return redissonClient.getPermitExpirableSemaphore(RedissonAsyncCouponIssueSemaphore.key(promotionId)).acquiredPermits();
    }

    /** 반납은 기다리지 않으므로, 보유 수가 기대값이 될 때까지 (최대 2 초) 기다린다. */
    private void awaitHolders(Long promotionId, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (holders(promotionId) != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(holders(promotionId)).isEqualTo(expected);
    }

    private static Optional<String> acquire(RedissonAsyncCouponIssueSemaphore semaphore, Long promotionId) {
        return semaphore.tryAcquire(promotionId).join();
    }

    @DisplayName("permit 수까지만 얻고, 그다음은 실패한다. 토큰은 서로 다르다.")
    @Test
    void acquiresUpToPermits() {
        RedissonAsyncCouponIssueSemaphore semaphore = semaphore(3, LONG_LEASE, NO_WAIT);

        List<Optional<String>> tokens = IntStream.range(0, 3).mapToObj(i -> acquire(semaphore, PROMOTION_ID)).toList();

        assertThat(tokens).allMatch(Optional::isPresent);
        assertThat(tokens.stream().map(Optional::get).distinct()).hasSize(3);
        assertThat(acquire(semaphore, PROMOTION_ID)).isEmpty();
        assertThat(holders(PROMOTION_ID)).isEqualTo(3);
    }

    @DisplayName("허가를 기다리는 동안 호출은 바로 돌아오고, future 는 waitTime 이 지나야 끝난다.")
    @Test
    void doesNotBlockCallerWhileWaiting() {
        RedissonAsyncCouponIssueSemaphore semaphore = semaphore(1, LONG_LEASE, Duration.ofMillis(500));
        acquire(semaphore, PROMOTION_ID).orElseThrow();

        long start = System.nanoTime();
        CompletableFuture<Optional<String>> waiting = semaphore.tryAcquire(PROMOTION_ID);
        Duration returnedIn = Duration.ofNanos(System.nanoTime() - start);

        assertThat(returnedIn).isLessThan(Duration.ofMillis(200));
        assertThat(waiting).isNotDone();
        assertThat(waiting.join()).isEmpty();
    }

    @DisplayName("반납하면 하나를 다시 얻을 수 있다.")
    @Test
    void releaseFreesOnePermit() throws InterruptedException {
        RedissonAsyncCouponIssueSemaphore semaphore = semaphore(1, LONG_LEASE, NO_WAIT);
        String token = acquire(semaphore, PROMOTION_ID).orElseThrow();
        assertThat(acquire(semaphore, PROMOTION_ID)).isEmpty();

        semaphore.release(PROMOTION_ID, token);
        awaitHolders(PROMOTION_ID, 0);

        assertThat(acquire(semaphore, PROMOTION_ID)).isPresent();
    }

    @DisplayName("남의 토큰(모르는 토큰)으로 반납하면 아무 허가도 풀리지 않고, 예외도 없다.")
    @Test
    void releaseWithUnknownToken_removesNothing() throws InterruptedException {
        RedissonAsyncCouponIssueSemaphore semaphore = semaphore(1, LONG_LEASE, NO_WAIT);
        acquire(semaphore, PROMOTION_ID).orElseThrow();

        assertThatCode(() -> semaphore.release(PROMOTION_ID, UNKNOWN_PERMIT_ID)).doesNotThrowAnyException();
        Thread.sleep(200);

        assertThat(holders(PROMOTION_ID)).isEqualTo(1);
        assertThat(acquire(semaphore, PROMOTION_ID)).isEmpty();
    }

    @DisplayName("lease 가 지난 허가는 반납하지 않아도 다음 획득 때 회수된다.")
    @Test
    void expiredLease_isReclaimed() throws InterruptedException {
        RedissonAsyncCouponIssueSemaphore semaphore = semaphore(1, Duration.ofMillis(200), NO_WAIT);
        acquire(semaphore, PROMOTION_ID).orElseThrow();
        assertThat(acquire(semaphore, PROMOTION_ID)).isEmpty();

        Thread.sleep(300);

        assertThat(acquire(semaphore, PROMOTION_ID)).isPresent();
        assertThat(holders(PROMOTION_ID)).isEqualTo(1);
    }

    @DisplayName("허가가 없으면 기다리다가, waitTime 안에 반납되면 그때 얻는다.")
    @Test
    void waitsUntilReleased() {
        RedissonAsyncCouponIssueSemaphore semaphore = semaphore(1, LONG_LEASE, Duration.ofSeconds(3));
        String held = acquire(semaphore, PROMOTION_ID).orElseThrow();
        CompletableFuture.runAsync(() -> semaphore.release(PROMOTION_ID, held),
            CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS));

        long start = System.nanoTime();
        Optional<String> token = acquire(semaphore, PROMOTION_ID);
        Duration waited = Duration.ofNanos(System.nanoTime() - start);

        assertThat(token).isPresent();
        assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(250)).isLessThan(Duration.ofSeconds(3));
    }

    @DisplayName("허가가 없으면 기다리다가, waitTime 안에 lease 가 지나면 그 허가를 얻는다.")
    @Test
    void waitsUntilLeaseExpires() {
        RedissonAsyncCouponIssueSemaphore semaphore = semaphore(1, Duration.ofMillis(300), Duration.ofSeconds(3));
        acquire(semaphore, PROMOTION_ID).orElseThrow();

        long start = System.nanoTime();
        Optional<String> token = acquire(semaphore, PROMOTION_ID);
        Duration waited = Duration.ofNanos(System.nanoTime() - start);

        assertThat(token).isPresent();
        assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(200)).isLessThan(Duration.ofSeconds(3));
    }

    @DisplayName("waitTime 동안 허가가 돌아오지 않으면 empty 다.")
    @Test
    void waitTimeElapsed_returnsEmpty() {
        RedissonAsyncCouponIssueSemaphore semaphore = semaphore(1, LONG_LEASE, Duration.ofMillis(300));
        acquire(semaphore, PROMOTION_ID).orElseThrow();

        long start = System.nanoTime();
        Optional<String> token = acquire(semaphore, PROMOTION_ID);
        Duration waited = Duration.ofNanos(System.nanoTime() - start);

        assertThat(token).isEmpty();
        assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }

    @DisplayName("프로모션마다 따로 센다.")
    @Test
    void countsPerPromotion() {
        RedissonAsyncCouponIssueSemaphore semaphore = semaphore(1, LONG_LEASE, NO_WAIT);
        acquire(semaphore, 1L).orElseThrow();

        assertThat(acquire(semaphore, 2L)).isPresent();
        assertThat(acquire(semaphore, 1L)).isEmpty();
    }

    @DisplayName("세마포어 키가 사라지면 다음 획득 때 permit 수를 다시 넣는다.")
    @Test
    void keyLost_permitsAreSetAgain() {
        RedissonAsyncCouponIssueSemaphore semaphore = semaphore(1, LONG_LEASE, NO_WAIT);
        acquire(semaphore, PROMOTION_ID).orElseThrow();

        redisCleanUp.truncateAll();

        assertThat(acquire(semaphore, PROMOTION_ID)).isPresent();
        assertThat(acquire(semaphore, PROMOTION_ID)).isEmpty();
    }

    @DisplayName("50 개 획득을 한꺼번에 걸면 permit 10 만큼만 얻는다.")
    @Test
    void concurrentAcquire_neverExceedsPermits() {
        RedissonAsyncCouponIssueSemaphore semaphore = semaphore(10, LONG_LEASE, NO_WAIT);

        List<CompletableFuture<Optional<String>>> futures = IntStream.range(0, 50)
            .mapToObj(i -> semaphore.tryAcquire(PROMOTION_ID)).toList();
        long acquired = futures.stream().map(CompletableFuture::join).filter(Optional::isPresent).count();

        assertThat(acquired).isEqualTo(10);
        assertThat(holders(PROMOTION_ID)).isEqualTo(10);
    }

    @DisplayName("[fail-closed] Redis 호출이 바로 예외를 던지면 획득은 empty 로 끝나고, 반납은 예외 없이 끝난다.")
    @Test
    void redisFailureThrown_acquireEmptyAndReleaseSwallows() {
        RPermitExpirableSemaphore brokenSemaphore = mock(RPermitExpirableSemaphore.class, invocation -> {
            throw new RedisConnectionException("down");
        });
        RedissonAsyncCouponIssueSemaphore semaphore = brokenSemaphore(brokenSemaphore);

        assertThat(semaphore.tryAcquire(PROMOTION_ID).join()).isEmpty();
        assertThatCode(() -> semaphore.release(PROMOTION_ID, "t")).doesNotThrowAnyException();
    }

    @DisplayName("[fail-closed] Redis 호출의 future 가 실패하면 획득은 empty 로 끝나고, 반납은 예외 없이 끝난다.")
    @Test
    void redisFailureAsync_acquireEmptyAndReleaseSwallows() {
        RedisConnectionException down = new RedisConnectionException("down");
        RPermitExpirableSemaphore brokenSemaphore = mock(RPermitExpirableSemaphore.class);
        when(brokenSemaphore.trySetPermitsAsync(anyInt())).thenReturn(new CompletableFutureWrapper<>(true));
        when(brokenSemaphore.tryAcquireAsync(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(new CompletableFutureWrapper<>(down));
        when(brokenSemaphore.tryReleaseAsync(anyString())).thenReturn(new CompletableFutureWrapper<>(down));
        RedissonAsyncCouponIssueSemaphore semaphore = brokenSemaphore(brokenSemaphore);

        assertThat(semaphore.tryAcquire(PROMOTION_ID).join()).isEmpty();
        assertThatCode(() -> semaphore.release(PROMOTION_ID, "t")).doesNotThrowAnyException();
    }

    private static RedissonAsyncCouponIssueSemaphore brokenSemaphore(RPermitExpirableSemaphore brokenSemaphore) {
        RedissonClient broken = mock(RedissonClient.class);
        when(broken.getPermitExpirableSemaphore(anyString())).thenReturn(brokenSemaphore);
        return new RedissonAsyncCouponIssueSemaphore(broken, new CouponIssueLimiterProperties(10, LONG_LEASE, NO_WAIT, EXECUTOR));
    }
}
