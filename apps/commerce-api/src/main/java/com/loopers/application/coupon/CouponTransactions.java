package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class CouponTransactions {

    private final CouponService couponService;

    @Transactional
    public Coupon issue(Long campaignId, Long userId, int issueNumber, Instant reservedAt) {
        return couponService.issue(campaignId, userId, issueNumber, reservedAt);
    }
}
