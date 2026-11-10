package com.loopers.application.promotion;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 쿠폰 발급 TX 하나를 세마포어 허가 안에서 실행한다 (DR-60).
 * 허가를 기다리지 않고 시도하고, 실패하면 retryDelay 뒤 한 번 더 시도한다. 그래도 없으면 COUPON_ISSUE_BUSY.
 * 허가는 TX 를 시작하기 전에 얻고, TX 가 끝나면 (커밋·롤백·예외 모두) 반납한다.
 */
@Component
public class CouponIssueLimiter {
    private final CouponIssueSemaphore semaphore;
    private final Duration retryDelay;

    public CouponIssueLimiter(CouponIssueSemaphore semaphore, CouponIssueLimiterProperties properties) {
        this.semaphore = semaphore;
        this.retryDelay = properties.retryDelay();
    }

    public <T> T run(Long promotionId, Supplier<T> task) {
        String token = acquire(promotionId);
        try {
            return task.get();
        } finally {
            semaphore.release(promotionId, token);
        }
    }

    private String acquire(Long promotionId) {
        Optional<String> token = semaphore.tryAcquire(promotionId);
        if (token.isPresent()) {
            return token.get();
        }
        sleepBeforeRetry(promotionId);
        return semaphore.tryAcquire(promotionId).orElseThrow(() -> busy(promotionId));
    }

    private void sleepBeforeRetry(Long promotionId) {
        try {
            Thread.sleep(retryDelay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw busy(promotionId);
        }
    }

    private static CoreException busy(Long promotionId) {
        return new CoreException(ErrorType.COUPON_ISSUE_BUSY, "[promotionId = " + promotionId + "] 발급 허가를 얻지 못했습니다.");
    }
}
