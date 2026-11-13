package com.loopers.application.promotion;

import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DR-63 발급 리미터: 허가는 한 번만 시도(기다림은 세마포어 몫), 없으면 COUPON_ISSUE_BUSY,
 * 얻으면 리미터 스레드 풀에서 실행하고 결과와 상관없이 반납, 풀 큐가 넘치면 반납하고 COUPON_ISSUE_BUSY.
 */
class CouponIssueLimiterTest {
    private static final Long PROMOTION_ID = 3L;

    private CouponIssueLimiter limiter;

    @AfterEach
    void tearDown() {
        if (limiter != null) {
            limiter.shutdown();
        }
    }

    /** 정한 future 를 차례로 돌려주고, 시도·반납을 기록한다. */
    private static class FakeSemaphore implements CouponIssueSemaphore {
        private final Queue<CompletableFuture<Optional<String>>> results = new ArrayDeque<>();
        private final AtomicInteger attempts = new AtomicInteger();
        private final List<String> released = new CopyOnWriteArrayList<>();

        FakeSemaphore then(CompletableFuture<Optional<String>> result) {
            results.add(result);
            return this;
        }

        FakeSemaphore then(Optional<String> result) {
            return then(CompletableFuture.completedFuture(result));
        }

        @Override
        public synchronized CompletableFuture<Optional<String>> tryAcquire(Long promotionId) {
            attempts.incrementAndGet();
            return results.remove();
        }

        @Override
        public void release(Long promotionId, String token) {
            released.add(token);
        }
    }

    private CouponIssueLimiter limiter(FakeSemaphore semaphore, int threads, int queueCapacity) {
        limiter = new CouponIssueLimiter(semaphore, new CouponIssueLimiterProperties(
            200, Duration.ofSeconds(2), Duration.ofMillis(600),
            new CouponIssueLimiterProperties.ExecutorProperties(threads, queueCapacity)));
        return limiter;
    }

    /** future 를 기다려, 실패면 CompletionException 을 벗긴 원인을 던진다. */
    private static <T> T join(CompletableFuture<T> future) throws Throwable {
        try {
            return future.join();
        } catch (CompletionException e) {
            throw e.getCause();
        }
    }

    @DisplayName("허가를 얻으면 작업을 리미터 스레드에서 실행해 결과를 돌려주고, 그 토큰을 반납한다.")
    @Test
    void acquired_runsTaskOnLimiterThreadAndReleases() throws Throwable {
        FakeSemaphore semaphore = new FakeSemaphore().then(Optional.of("t1"));

        String thread = join(limiter(semaphore, 2, 2).run(PROMOTION_ID, () -> Thread.currentThread().getName()));

        assertThat(thread).startsWith("coupon-issue-");
        assertThat(semaphore.attempts).hasValue(1);
        assertThat(semaphore.released).containsExactly("t1");
    }

    @DisplayName("허가를 기다리는 동안 호출은 바로 돌아오고, 허가가 오면 그때 작업을 실행한다.")
    @Test
    void waitingForPermit_doesNotBlockCaller() throws Throwable {
        CompletableFuture<Optional<String>> permit = new CompletableFuture<>();
        FakeSemaphore semaphore = new FakeSemaphore().then(permit);
        AtomicInteger runs = new AtomicInteger();

        CompletableFuture<Integer> result = limiter(semaphore, 2, 2).run(PROMOTION_ID, runs::incrementAndGet);

        assertThat(result).isNotDone();
        assertThat(runs).hasValue(0);

        permit.complete(Optional.of("t2"));

        assertThat(join(result)).isEqualTo(1);
        assertThat(semaphore.released).containsExactly("t2");
    }

    @DisplayName("[COUPON_ISSUE_BUSY] 허가를 얻지 못하면 재시도하지 않고, 작업을 실행하지 않으며 반납도 하지 않는다.")
    @Test
    void notAcquired_failsBusyWithoutRetry() {
        FakeSemaphore semaphore = new FakeSemaphore().then(Optional.empty());
        AtomicInteger runs = new AtomicInteger();

        CompletableFuture<Integer> result = limiter(semaphore, 2, 2).run(PROMOTION_ID, runs::incrementAndGet);

        assertThrowsErrorType(() -> join(result), ErrorType.COUPON_ISSUE_BUSY);
        assertThat(runs).hasValue(0);
        assertThat(semaphore.attempts).hasValue(1);
        assertThat(semaphore.released).isEmpty();
    }

    @DisplayName("작업이 예외를 던져도 허가를 반납하고, 예외는 그대로 전한다.")
    @Test
    void taskFailure_stillReleases() {
        FakeSemaphore semaphore = new FakeSemaphore().then(Optional.of("t3"));

        CompletableFuture<Object> result = limiter(semaphore, 2, 2).run(PROMOTION_ID, () -> {
            throw new IllegalStateException("rollback");
        });

        assertThatThrownBy(() -> join(result)).isInstanceOf(IllegalStateException.class).hasMessage("rollback");
        assertThat(semaphore.released).containsExactly("t3");
    }

    @DisplayName("[COUPON_ISSUE_BUSY] 스레드가 모두 바쁘고 큐도 차 있으면, 작업을 실행하지 않고 그 허가를 바로 반납한다.")
    @Test
    void executorFull_releasesAndFailsBusy() throws Throwable {
        FakeSemaphore semaphore = new FakeSemaphore().then(Optional.of("running")).then(Optional.of("rejected"));
        CouponIssueLimiter limiter = limiter(semaphore, 1, 0);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CompletableFuture<String> running = limiter.run(PROMOTION_ID, () -> {
            started.countDown();
            await(finish);
            return "done";
        });
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        AtomicInteger runs = new AtomicInteger();

        CompletableFuture<Integer> rejected = limiter.run(PROMOTION_ID, runs::incrementAndGet);

        assertThrowsErrorType(() -> join(rejected), ErrorType.COUPON_ISSUE_BUSY);
        assertThat(runs).hasValue(0);
        assertThat(semaphore.released).containsExactly("rejected");

        finish.countDown();
        assertThat(join(running)).isEqualTo("done");
        assertThat(semaphore.released).containsExactly("rejected", "running");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
