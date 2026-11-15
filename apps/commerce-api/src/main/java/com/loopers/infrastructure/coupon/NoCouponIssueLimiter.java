package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponIssueLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 기본값. DB 발급 TX 앞에서 기다리지 않는다. */
@Component
@ConditionalOnProperty(name = "commerce.coupon.issue-limit.mode", havingValue = "NONE", matchIfMissing = true)
public class NoCouponIssueLimiter implements CouponIssueLimiter {
    @Override
    public boolean acquire(Long campaignId) {
        return true;
    }

    @Override
    public void release(Long campaignId) {
    }
}
