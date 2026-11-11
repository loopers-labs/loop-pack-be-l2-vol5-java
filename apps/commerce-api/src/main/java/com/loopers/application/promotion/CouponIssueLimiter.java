package com.loopers.application.promotion;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 쿠폰 발급 TX 하나를 세마포어 허가 안에서 실행한다 (DR-61).
 * 허가가 없으면 세마포어가 waitTime 까지 기다린다. 그래도 없으면 재시도하지 않고 COUPON_ISSUE_BUSY.
 * 허가는 TX 를 시작하기 전에 얻고, TX 가 끝나면 (커밋·롤백·예외 모두) 반납한다.
 */
@Component
public class CouponIssueLimiter {
    private final CouponIssueSemaphore semaphore;

    public CouponIssueLimiter(CouponIssueSemaphore semaphore) {
        this.semaphore = semaphore;
    }

    public <T> T run(Long promotionId, Supplier<T> task) {
        String token = semaphore.tryAcquire(promotionId).orElseThrow(() -> busy(promotionId));
        try {
            return task.get();
        } finally {
            semaphore.release(promotionId, token);
        }
    }

    private static CoreException busy(Long promotionId) {
        return new CoreException(ErrorType.COUPON_ISSUE_BUSY, "[promotionId = " + promotionId + "] 발급 허가를 얻지 못했습니다.");
    }
}
