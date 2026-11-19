package com.loopers.domains.coupon.domain;

import java.time.Instant;

// 발급된 쿠폰. 혜택은 발급할 때 캠페인 값을 복사해 둔 것이라 캠페인이 나중에 바뀌어도 그대로다.
public record IssuedCoupon(long id, long campaignId, long userId, long discountAmount, Instant issuedAt) {
}
