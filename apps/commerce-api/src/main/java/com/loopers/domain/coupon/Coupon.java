package com.loopers.domain.coupon;

import java.time.ZonedDateTime;

public record Coupon(
        long couponId,
        long campaignId,
        long userId,
        String benefitDescription,
        ZonedDateTime issuedAt) {
}
