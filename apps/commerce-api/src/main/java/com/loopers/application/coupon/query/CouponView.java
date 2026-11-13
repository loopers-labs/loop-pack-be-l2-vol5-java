package com.loopers.application.coupon.query;

import java.time.Instant;

/** 발급 당시 저장된 혜택을 반환한다. 캠페인의 현재 발급 가능 상태와 무관하다. */
public record CouponView(long couponId, long campaignId, Instant issuedAt,
                         String benefitDescription, long discountAmount) {}
