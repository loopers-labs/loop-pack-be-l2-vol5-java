package com.loopers.application.promotion;

import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DR-60 발급 리미터: 한 번 재시도, 허가 없으면 COUPON_ISSUE_BUSY, 작업 결과와 상관없이 반납. */
class CouponIssueLimiterTest {
    private static final Long PROMOTION_ID = 3L;
    private static final Duration RETRY_DELAY = Duration.ofMillis(100);

    /** 미리 정한 결과를 차례로 돌려주고, 시도·반납을 기록한다. */
    private static class FakeSemaphore implements CouponIssueSemaphore {
        private final Deque<Optional<String>> results;
        private final List<Long> attemptNanos = new ArrayList<>();
        private final List<String> released = new ArrayList<>();

        @SafeVarargs
        FakeSemaphore(Optional<String>... results) {
            this.results = new ArrayDeque<>(List.of(results));
        }

        @Override
        public Optional<String> tryAcquire(Long promotionId) {
            attemptNanos.add(System.nanoTime());
            return results.isEmpty() ? Optional.empty() : results.poll();
        }

        @Override
        public void release(Long promotionId, String token) {
            released.add(token);
        }
    }

    private static CouponIssueLimiter limiter(CouponIssueSemaphore semaphore) {
        return new CouponIssueLimiter(semaphore, new CouponIssueLimiterProperties(200, Duration.ofMillis(200), RETRY_DELAY));
    }

    @DisplayName("첫 시도에 허가를 얻으면 작업을 실행해 결과를 돌려주고, 그 토큰을 반납한다.")
    @Test
    void firstAttempt_runsTaskAndReleases() {
        FakeSemaphore semaphore = new FakeSemaphore(Optional.of("t1"));

        String result = limiter(semaphore).run(PROMOTION_ID, () -> "issued");

        assertThat(result).isEqualTo("issued");
        assertThat(semaphore.attemptNanos).hasSize(1);
        assertThat(semaphore.released).containsExactly("t1");
    }

    @DisplayName("첫 시도에 실패하면 retryDelay 뒤 한 번 더 시도하고, 얻으면 작업을 실행하고 반납한다.")
    @Test
    void retriesOnceAfterDelay() {
        FakeSemaphore semaphore = new FakeSemaphore(Optional.empty(), Optional.of("t2"));

        String result = limiter(semaphore).run(PROMOTION_ID, () -> "issued");

        assertThat(result).isEqualTo("issued");
        assertThat(semaphore.attemptNanos).hasSize(2);
        long waitedNanos = semaphore.attemptNanos.get(1) - semaphore.attemptNanos.get(0);
        assertThat(Duration.ofNanos(waitedNanos)).isGreaterThanOrEqualTo(RETRY_DELAY);
        assertThat(semaphore.released).containsExactly("t2");
    }

    @DisplayName("[COUPON_ISSUE_BUSY] 재시도도 실패하면 작업을 실행하지 않고 반납도 하지 않는다. 시도는 두 번뿐이다.")
    @Test
    void bothAttemptsFail_throwsBusy() {
        FakeSemaphore semaphore = new FakeSemaphore(Optional.empty(), Optional.empty());
        AtomicInteger runs = new AtomicInteger();

        assertThrowsErrorType(() -> limiter(semaphore).run(PROMOTION_ID, runs::incrementAndGet), ErrorType.COUPON_ISSUE_BUSY);

        assertThat(runs).hasValue(0);
        assertThat(semaphore.attemptNanos).hasSize(2);
        assertThat(semaphore.released).isEmpty();
    }

    @DisplayName("작업이 예외를 던져도 허가를 반납하고, 예외는 그대로 전한다.")
    @Test
    void taskFailure_stillReleases() {
        FakeSemaphore semaphore = new FakeSemaphore(Optional.of("t3"));

        assertThatThrownBy(() -> limiter(semaphore).run(PROMOTION_ID, () -> {
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("rollback");

        assertThat(semaphore.released).containsExactly("t3");
    }
}
