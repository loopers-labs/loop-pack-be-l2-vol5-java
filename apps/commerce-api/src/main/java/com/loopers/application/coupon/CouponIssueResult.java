package com.loopers.application.coupon;

/**
 * 발급 요청의 결과. CREATED·EXISTING은 DB commit이 끝난 실제 쿠폰이고,
 * PENDING은 작업 큐 모드에서 접수만 된 상태(쿠폰은 Worker가 저장한 뒤 생긴다)다.
 */
public record CouponIssueResult(Status status, Long campaignId, CouponInfo coupon) {
    public enum Status { CREATED, EXISTING, PENDING }

    static CouponIssueResult created(CouponInfo coupon) { return new CouponIssueResult(Status.CREATED, coupon.campaignId(), coupon); }
    static CouponIssueResult existing(CouponInfo coupon) { return new CouponIssueResult(Status.EXISTING, coupon.campaignId(), coupon); }
    static CouponIssueResult pending(Long campaignId) { return new CouponIssueResult(Status.PENDING, campaignId, null); }

    /** 이번 실행이 쿠폰을 새로 만들었는지. */
    public boolean created() { return status == Status.CREATED; }

    public boolean pending() { return status == Status.PENDING; }
}
