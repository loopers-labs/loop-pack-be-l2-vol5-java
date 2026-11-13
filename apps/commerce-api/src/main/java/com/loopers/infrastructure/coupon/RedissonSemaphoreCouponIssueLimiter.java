package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponIssueLimiter;
import java.time.Duration;
import org.redisson.api.RSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 캠페인마다 Redisson 세마포어(자리 N개). 자리를 얻은 실행만 DB 발급 TX에 들어간다.
 * 자리에는 만료가 없어, 쥔 채 앱이 죽으면 자리가 줄어든 채로 남는다(비교 실험의 한계).
 */
@Component
@ConditionalOnProperty(name = "commerce.coupon.issue-limit.mode", havingValue = "SEMAPHORE")
public class RedissonSemaphoreCouponIssueLimiter implements CouponIssueLimiter {
    private final RedissonClient redisson;
    private final int permits;
    private final Duration waitTimeout;

    public RedissonSemaphoreCouponIssueLimiter(RedissonClient redisson,
        @Value("${commerce.coupon.issue-limit.permits:3}") int permits,
        @Value("${commerce.coupon.issue-limit.wait-timeout:60s}") Duration waitTimeout) {
        if (permits < 1) {
            throw new IllegalArgumentException("Coupon issue permits must be positive");
        }
        this.redisson = redisson;
        this.permits = permits;
        this.waitTimeout = waitTimeout;
    }

    @Override
    public boolean acquire(Long campaignId) {
        RSemaphore semaphore = semaphore(campaignId);
        // 처음 쓰는 캠페인이면 자리 수를 정한다. 이미 있으면 바꾸지 않는다.
        semaphore.trySetPermits(permits);
        try {
            return semaphore.tryAcquire(waitTimeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void release(Long campaignId) {
        semaphore(campaignId).release();
    }

    private RSemaphore semaphore(Long campaignId) {
        return redisson.getSemaphore("coupon-issue-semaphore:{" + campaignId + "}");
    }
}
