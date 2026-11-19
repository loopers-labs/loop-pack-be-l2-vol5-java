package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponAdmissionFacade.AdmissionStatusInfo;
import com.loopers.application.coupon.CouponAdmissionFacade.StatusInfo;
import com.loopers.domain.coupon.Coupon;

import java.time.Instant;
import java.time.ZonedDateTime;

public class CouponV1Dto {

    /** 202 신규 접수. seq 는 접수 번호 (§8-1, D13) */
    public record ApplyResponse(long seq) {}

    /** 200 이미 발급됨 / 201 신규 발급 (§8-1) */
    public record CouponIdResponse(Long couponId) {}

    /** status ∈ WAITING·ADMITTED·ISSUED·SOLD_OUT·NONE. rank 는 WAITING 일 때만, couponId 는 ISSUED 일 때만 */
    public record StatusResponse(String status, Long rank, Long couponId) {
        public static StatusResponse from(StatusInfo info) {
            return new StatusResponse(info.status().name(), info.rank(), info.couponId());
        }
    }

    public record CouponResponse(
        Long couponId,
        Long campaignId,
        String benefitType,
        long benefitValue,
        String status,
        ZonedDateTime issuedAt,
        ZonedDateTime expiresAt
    ) {
        public static CouponResponse from(Coupon coupon) {
            return new CouponResponse(
                coupon.getId(),
                coupon.getCampaignId(),
                coupon.getBenefitType().name(),
                coupon.getBenefitValue(),
                coupon.getStatus().name(),
                coupon.getIssuedAt(),
                coupon.getExpiresAt()
            );
        }
    }

    /** 관리자 상태 조회 (§8-1). redisUnavailable 이 true 면 Redis 에서 읽는 항목(generationMatches·waiting·admitted·permits·seq)은 null 이다 */
    public record AdmissionStatusResponse(
        boolean open,
        String closeReason,
        Instant closedAt,
        Boolean generationMatches,
        String dbCircuitState,
        Long waiting,
        Long admitted,
        Long permits,
        Long seq,
        int activeRequests,
        boolean autoReopenEnabled,
        int recentAutoReopens,
        boolean redisUnavailable
    ) {
        public static AdmissionStatusResponse from(AdmissionStatusInfo info) {
            return new AdmissionStatusResponse(
                info.open(),
                info.closeReason() == null ? null : info.closeReason().name(),
                info.closedAt(),
                info.generationMatches(),
                info.dbCircuitState().name(),
                info.waiting(),
                info.admitted(),
                info.permits(),
                info.seq(),
                info.activeRequests(),
                info.autoReopenEnabled(),
                info.recentAutoReopens(),
                info.redisUnavailable()
            );
        }
    }
}
