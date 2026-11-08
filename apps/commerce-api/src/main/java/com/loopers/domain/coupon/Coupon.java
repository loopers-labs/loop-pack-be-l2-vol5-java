package com.loopers.domain.coupon;

import java.time.Instant;

public record Coupon(
    Long id,
    Long campaignId,
    Long userId,
    DiscountAmount discountAmount,
    Instant issuedAt
) {
}
