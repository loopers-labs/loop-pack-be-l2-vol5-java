package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponIssueInfo;
import com.loopers.domain.coupon.CouponIssueRequest;
import com.loopers.domain.coupon.CouponIssueRequestStatus;

import java.time.Instant;

public final class CouponIssueRequestV1Dto {

    private CouponIssueRequestV1Dto() {}

    /**
     * 대기열 작업 응답. 작업 없이 기존 쿠폰을 바로 돌려줄 때는 requestId와 requestedAt이 비어 있다.
     */
    public record IssueRequestResponse(String requestId, Long campaignId, CouponIssueRequestStatus status,
                                       Instant requestedAt, Long couponId, String reasonCode, String message) {

        public static IssueRequestResponse from(CouponIssueRequest request) {
            return new IssueRequestResponse(request.getRequestId(), request.getCampaignId(), request.getStatus(),
                request.getRequestedAt(), request.getCouponId(),
                request.getReason() == null ? null : request.getReason().name(), request.getMessage());
        }

        public static IssueRequestResponse from(CouponIssueInfo coupon) {
            return new IssueRequestResponse(null, coupon.campaignId(), CouponIssueRequestStatus.SUCCEEDED, null,
                coupon.couponId(), null, null);
        }
    }
}
