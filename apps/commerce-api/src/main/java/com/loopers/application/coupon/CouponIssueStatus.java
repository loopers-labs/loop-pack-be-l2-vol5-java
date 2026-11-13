package com.loopers.application.coupon;

/** 한 사용자·캠페인의 발급 상태(작업 큐 모드의 결과 확인용). 발급됨이면 쿠폰이, 취소됨이면 이유가 있다. */
public record CouponIssueStatus(Long campaignId, State state, CouponInfo coupon, String cancelReason) {
    public enum State {
        /** 쿠폰이 저장됐다. */
        ISSUED,
        /** "받음"이라고 답했고 Worker가 저장하기를 기다린다. */
        PENDING,
        /** "받음"이라고 답했지만 Worker가 확정 거절했다. */
        CANCELLED,
        /** 이 캠페인에 받은 기록이 없다. */
        NONE
    }

    static CouponIssueStatus issued(CouponInfo coupon) { return new CouponIssueStatus(coupon.campaignId(), State.ISSUED, coupon, null); }
    static CouponIssueStatus pending(Long campaignId) { return new CouponIssueStatus(campaignId, State.PENDING, null, null); }
    static CouponIssueStatus cancelled(Long campaignId, String reason) { return new CouponIssueStatus(campaignId, State.CANCELLED, null, reason); }
    static CouponIssueStatus none(Long campaignId) { return new CouponIssueStatus(campaignId, State.NONE, null, null); }
}
