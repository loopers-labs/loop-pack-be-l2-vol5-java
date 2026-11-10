package com.loopers.infrastructure.promotion;

import com.loopers.application.promotion.CouponIssueLimiterProperties;
import com.loopers.config.redis.RedisConfig;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * DR-60 Redis 세마포어 (ZSET + Lua). 실제 Redis.
 * 실행: ./gradlew :apps:commerce-api:test --tests '*RedisCouponIssueSemaphoreTest' (Docker 필요, Testcontainers).
 */
@SpringBootTest
class RedisCouponIssueSemaphoreTest {
    private static final Long PROMOTION_ID = 1L;
    private static final Duration LONG_LEASE = Duration.ofSeconds(30);

    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> masterTemplate;
    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    private RedisCouponIssueSemaphore semaphore(int permits, Duration lease) {
        return new RedisCouponIssueSemaphore(masterTemplate, new CouponIssueLimiterProperties(permits, lease, Duration.ofMillis(300)));
    }

    private Long holders(Long promotionId) {
        return masterTemplate.opsForZSet().zCard(RedisCouponIssueSemaphore.key(promotionId));
    }

    @DisplayName("permit 수까지만 얻고, 그다음은 실패한다. 토큰은 서로 다르다.")
    @Test
    void acquiresUpToPermits() {
        RedisCouponIssueSemaphore semaphore = semaphore(3, LONG_LEASE);

        List<Optional<String>> tokens = IntStream.range(0, 3).mapToObj(i -> semaphore.tryAcquire(PROMOTION_ID)).toList();

        assertThat(tokens).allMatch(Optional::isPresent);
        assertThat(tokens.stream().map(Optional::get).distinct()).hasSize(3);
        assertThat(semaphore.tryAcquire(PROMOTION_ID)).isEmpty();
        assertThat(holders(PROMOTION_ID)).isEqualTo(3);
    }

    @DisplayName("반납하면 하나를 다시 얻을 수 있다.")
    @Test
    void releaseFreesOnePermit() {
        RedisCouponIssueSemaphore semaphore = semaphore(1, LONG_LEASE);
        String token = semaphore.tryAcquire(PROMOTION_ID).orElseThrow();
        assertThat(semaphore.tryAcquire(PROMOTION_ID)).isEmpty();

        semaphore.release(PROMOTION_ID, token);

        assertThat(semaphore.tryAcquire(PROMOTION_ID)).isPresent();
    }

    @DisplayName("남의 토큰(모르는 토큰)으로 반납하면 아무 허가도 지워지지 않는다.")
    @Test
    void releaseWithUnknownToken_removesNothing() {
        RedisCouponIssueSemaphore semaphore = semaphore(1, LONG_LEASE);
        semaphore.tryAcquire(PROMOTION_ID).orElseThrow();

        semaphore.release(PROMOTION_ID, "not-mine");

        assertThat(holders(PROMOTION_ID)).isEqualTo(1);
        assertThat(semaphore.tryAcquire(PROMOTION_ID)).isEmpty();
    }

    @DisplayName("lease 가 지난 허가는 반납하지 않아도 다음 획득 때 회수된다.")
    @Test
    void expiredLease_isReclaimed() throws InterruptedException {
        RedisCouponIssueSemaphore semaphore = semaphore(1, Duration.ofMillis(200));
        semaphore.tryAcquire(PROMOTION_ID).orElseThrow();
        assertThat(semaphore.tryAcquire(PROMOTION_ID)).isEmpty();

        Thread.sleep(300);

        assertThat(semaphore.tryAcquire(PROMOTION_ID)).isPresent();
        assertThat(holders(PROMOTION_ID)).isEqualTo(1);
    }

    @DisplayName("프로모션마다 따로 센다.")
    @Test
    void countsPerPromotion() {
        RedisCouponIssueSemaphore semaphore = semaphore(1, LONG_LEASE);
        semaphore.tryAcquire(1L).orElseThrow();

        assertThat(semaphore.tryAcquire(2L)).isPresent();
        assertThat(semaphore.tryAcquire(1L)).isEmpty();
    }

    @DisplayName("50 개 스레드가 동시에 permit 10 을 시도하면 정확히 10 개만 얻는다.")
    @Test
    void concurrentAcquire_neverExceedsPermits() throws Exception {
        int threads = 50;
        RedisCouponIssueSemaphore semaphore = semaphore(10, LONG_LEASE);
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
    @SuppressWarnings("unchecked")
    void redisFailure_acquireFailsAndReleaseSwallows() {
        RedisTemplate<String, String> broken = mock(RedisTemplate.class, invocation -> {
            throw new RedisConnectionFailureException("down");
        });
        RedisCouponIssueSemaphore semaphore = new RedisCouponIssueSemaphore(
            broken, new CouponIssueLimiterProperties(10, LONG_LEASE, Duration.ofMillis(300)));

        assertThat(semaphore.tryAcquire(PROMOTION_ID)).isEmpty();
        assertThatCode(() -> semaphore.release(PROMOTION_ID, "t")).doesNotThrowAnyException();
    }
}
