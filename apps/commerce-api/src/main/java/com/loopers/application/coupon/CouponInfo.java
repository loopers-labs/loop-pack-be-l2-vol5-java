package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponModel;
import java.time.Instant;

/** 발급된 쿠폰. reservationId는 작업 큐 Worker가 재처리를 가리는 내부 값이며 응답에 내보내지 않는다. */
public record CouponInfo(Long couponId, Long campaignId, Instant issuedAt, String benefitDescription, String reservationId) {
    static CouponInfo from(CouponModel coupon) {
        return new CouponInfo(coupon.getId(), coupon.getCampaignId(), coupon.getIssuedAt(), coupon.getBenefitDescription(),
            coupon.getReservationId());
    }
}
