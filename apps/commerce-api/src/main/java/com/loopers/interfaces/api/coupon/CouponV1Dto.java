package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponInfo;
import com.loopers.application.coupon.CouponIssueStatus;
import java.time.Instant;

public class CouponV1Dto {
    public record CouponResponse(Long couponId, Long campaignId, Instant issuedAt, String benefitDescription) {
        public static CouponResponse from(CouponInfo info) {
            return new CouponResponse(info.couponId(), info.campaignId(), info.issuedAt(), info.benefitDescription());
        }
    }

    /** 발급 상태. status는 ISSUED / PENDING / CANCELLED / NONE이다. 발급됨이면 coupon, 취소됨이면 cancelReason이 있다. */
    public record IssueStatusResponse(Long campaignId, String status, CouponResponse coupon, String cancelReason) {
        public static IssueStatusResponse from(CouponIssueStatus status) {
            return new IssueStatusResponse(status.campaignId(), status.state().name(),
                status.coupon() == null ? null : CouponResponse.from(status.coupon()), status.cancelReason());
        }

        public static IssueStatusResponse pending(Long campaignId) {
            return new IssueStatusResponse(campaignId, CouponIssueStatus.State.PENDING.name(), null, null);
        }
    }
}
