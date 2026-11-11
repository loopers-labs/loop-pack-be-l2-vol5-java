package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponIssueResult;
import com.loopers.domain.coupon.CouponBenefitType;
import com.loopers.domain.coupon.OwnedCoupon;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class CouponV1Dto {

    /** 발급 응답. 최초 201 과 기존 결과 200 이 같은 다섯 필드를 사용한다. */
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

    /** 보유 목록 항목. 다섯 필드와 DB 에 저장된 발급 시각을 UTC·마이크로초 6자리·Z 로 제공한다. */
    public record OwnedCouponResponse(
        Long couponId,
        Long campaignId,
        String campaignName,
        CouponBenefitType benefitType,
        Long benefitValue,
        String createdAt
    ) {
        private static final DateTimeFormatter UTC_MICROS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

        public static OwnedCouponResponse from(OwnedCoupon coupon) {
            return new OwnedCouponResponse(coupon.couponId(), coupon.campaignId(), coupon.campaignName(),
                coupon.benefitType(), coupon.benefitValue(), formatUtc(coupon.createdAt()));
        }

        static String formatUtc(ZonedDateTime value) {
            return value.withZoneSameInstant(ZoneOffset.UTC).format(UTC_MICROS);
        }
    }
}
