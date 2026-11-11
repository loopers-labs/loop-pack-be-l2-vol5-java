package com.loopers.application.promotion;

import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DR-61 발급 리미터: 허가는 한 번만 시도(기다림은 세마포어 몫), 없으면 COUPON_ISSUE_BUSY, 작업 결과와 상관없이 반납. */
class CouponIssueLimiterTest {
    private static final Long PROMOTION_ID = 3L;

    /** 정한 결과를 돌려주고, 시도·반납을 기록한다. */
    private static class FakeSemaphore implements CouponIssueSemaphore {
        private final Optional<String> result;
        private int attempts;
        private final List<String> released = new ArrayList<>();

        FakeSemaphore(Optional<String> result) {
            this.result = result;
        }

        @Override
        public Optional<String> tryAcquire(Long promotionId) {
            attempts++;
            return result;
        }

        @Override
        public void release(Long promotionId, String token) {
            released.add(token);
        }
    }

    @DisplayName("허가를 얻으면 작업을 실행해 결과를 돌려주고, 그 토큰을 반납한다.")
    @Test
    void acquired_runsTaskAndReleases() {
        FakeSemaphore semaphore = new FakeSemaphore(Optional.of("t1"));

        String result = new CouponIssueLimiter(semaphore).run(PROMOTION_ID, () -> "issued");

        assertThat(result).isEqualTo("issued");
        assertThat(semaphore.attempts).isEqualTo(1);
        assertThat(semaphore.released).containsExactly("t1");
    }

    @DisplayName("[COUPON_ISSUE_BUSY] 허가를 얻지 못하면 재시도하지 않고, 작업을 실행하지 않으며 반납도 하지 않는다.")
    @Test
    void notAcquired_throwsBusyWithoutRetry() {
        FakeSemaphore semaphore = new FakeSemaphore(Optional.empty());
        AtomicInteger runs = new AtomicInteger();

        assertThrowsErrorType(() -> new CouponIssueLimiter(semaphore).run(PROMOTION_ID, runs::incrementAndGet), ErrorType.COUPON_ISSUE_BUSY);

        assertThat(runs).hasValue(0);
        assertThat(semaphore.attempts).isEqualTo(1);
        assertThat(semaphore.released).isEmpty();
    }

    @DisplayName("작업이 예외를 던져도 허가를 반납하고, 예외는 그대로 전한다.")
    @Test
    void taskFailure_stillReleases() {
        FakeSemaphore semaphore = new FakeSemaphore(Optional.of("t3"));

        assertThatThrownBy(() -> new CouponIssueLimiter(semaphore).run(PROMOTION_ID, () -> {
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("rollback");

        assertThat(semaphore.released).containsExactly("t3");
    }
}
