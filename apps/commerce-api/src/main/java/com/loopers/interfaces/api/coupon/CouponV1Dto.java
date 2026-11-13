package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponIssueResult;
import com.loopers.domain.coupon.CouponBenefitType;
import com.loopers.domain.coupon.CouponWaitingSnapshot;
import com.loopers.domain.coupon.CouponWaitingState;
import com.loopers.domain.coupon.OwnedCoupon;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class CouponV1Dto {

    /** API 시각 표기: UTC·마이크로초 6자리·Z. Redis 밀리초 이하 자리는 0 이다. */
    private static final DateTimeFormatter UTC_MICROS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
        .withZone(ZoneOffset.UTC);

    public record CouponResponse(
        Long couponId,
        Long campaignId,
        String campaignName,
        CouponBenefitType benefitType,
        Long benefitValue
    ) {
        public static CouponResponse from(CouponIssueResult result) {
            return new CouponResponse(result.couponId(), result.campaignId(), result.campaignName(),
                result.benefitType(), result.benefitValue());
        }
    }

    public record OwnedCouponResponse(
        Long couponId,
        Long campaignId,
        String campaignName,
        CouponBenefitType benefitType,
        Long benefitValue,
        String createdAt
    ) {
        public static OwnedCouponResponse from(OwnedCoupon coupon) {
            return new OwnedCouponResponse(coupon.couponId(), coupon.campaignId(), coupon.campaignName(),
                coupon.benefitType(), coupon.benefitValue(), UTC_MICROS.format(coupon.createdAt()));
        }
    }

    /** 상태에 없는 필드는 null 이라 JSON 에서 빠진다. */
    public record WaitingResponse(
        Long campaignId,
        String waitingId,
        CouponWaitingState status,
        Long position,
        String expiresAt,
        Long couponId
    ) {
        public static WaitingResponse from(CouponWaitingSnapshot snapshot) {
            return new WaitingResponse(snapshot.campaignId(), snapshot.waitingId(), snapshot.status(), snapshot.position(),
                snapshot.expiresAt() == null ? null : UTC_MICROS.format(snapshot.expiresAt()), snapshot.couponId());
        }
    }
}
