package com.loopers.application.promotion.query;

import java.time.ZonedDateTime;
import java.util.Map;

/** 쿠폰 조회 전용 DTO 묶음. 조회 하나당 중첩 record 하나. 엔티티를 담지 않는다 (DR-31). */
public final class CouponView {
    private CouponView() {
    }

    /** 내 쿠폰 한 장. 쿠폰 내용은 발급 시 복사하지 않고 TB-16 coupon_spec 을 함께 읽는다 (DR-57). */
    public record MyCoupon(
        Long couponId,
        Long promotionId,
        String promotionName,
        Map<String, Object> couponSpec,
        ZonedDateTime issuedAt
    ) {
    }
}
