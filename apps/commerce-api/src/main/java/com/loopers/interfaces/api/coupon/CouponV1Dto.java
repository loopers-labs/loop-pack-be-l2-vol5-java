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
        public static OwnedCouponResponse from(OwnedCoupon coupon) {
            return new OwnedCouponResponse(coupon.couponId(), coupon.campaignId(), coupon.campaignName(),
                coupon.benefitType(), coupon.benefitValue(), UTC_MICROS.format(coupon.createdAt()));
        }
    }

    /**
     * 대기 등록·조회와 발급 진행 중(202) 응답(설계 §12.7·12.11). 상태에 없는 필드는 null 이고 공통 설정으로 JSON 에서 빠진다.
     * position 은 WAITING, expiresAt 은 READY, couponId 는 DB 에서 확인한 ISSUED 에만 있다. 기존 쿠폰만 있으면 waitingId 도 없다.
     */
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
