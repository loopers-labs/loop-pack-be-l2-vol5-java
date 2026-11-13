package com.loopers.couponissue.domain;

/**
 * 당첨 기록 한 건 (commerce-api coupon_issue_outbox → coupon-issue-requests, D-2). 이 사용자에게 캠페인의 쿠폰팩을 발급한다.
 */
public record CouponIssueMessage(Long campaignId, Long userId) {

    public CouponIssueMessage {
        if (campaignId == null || campaignId < 1 || userId == null || userId < 1) {
            throw new InvalidCouponIssueMessageException("campaignId·userId는 1 이상이어야 합니다: campaignId=" + campaignId + ", userId=" + userId);
        }
    }
}
