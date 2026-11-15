package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponIssueLimiter;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 캠페인마다 Redisson 분산락 하나. 락을 얻은 실행만 DB 발급 TX에 들어간다.
 * 만료 시간을 주지 않아 Redisson watchdog이 락을 쥔 동안 만료를 늘린다(앱이 죽으면 watchdog 주기 뒤 풀린다).
 */
@Component
@ConditionalOnProperty(name = "commerce.coupon.issue-limit.mode", havingValue = "LOCK")
public class RedissonLockCouponIssueLimiter implements CouponIssueLimiter {
    private final RedissonClient redisson;
    private final Duration waitTimeout;

    public RedissonLockCouponIssueLimiter(RedissonClient redisson,
        @Value("${commerce.coupon.issue-limit.wait-timeout:60s}") Duration waitTimeout) {
        this.redisson = redisson;
        this.waitTimeout = waitTimeout;
    }

    @Override
    public boolean acquire(Long campaignId) {
        try {
            return lock(campaignId).tryLock(waitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void release(Long campaignId) {
        RLock lock = lock(campaignId);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    private RLock lock(Long campaignId) {
        return redisson.getLock("coupon-issue-lock:{" + campaignId + "}");
    }
}
