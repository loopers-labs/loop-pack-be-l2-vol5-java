package com.loopers.application.coupon.dto;

/** 발급 요청의 결과. {@code couponId} 는 PENDING 일 때 없다. */
public record CouponIssueInfo(Status status, Long couponId) {

    public enum Status {
        /** 이번 요청으로 DB 에 저장했다. */
        ISSUED,
        /** 이미 발급된 쿠폰이다. */
        EXISTING,
        /** 당첨을 약속했다. 당첨 기록이 DB 에 있고 쿠폰은 아직 저장 전이다. */
        PENDING
    }

    public static CouponIssueInfo issued(Long couponId) {
        return new CouponIssueInfo(Status.ISSUED, couponId);
    }

    public static CouponIssueInfo existing(Long couponId) {
        return new CouponIssueInfo(Status.EXISTING, couponId);
    }

    public static CouponIssueInfo pending() {
        return new CouponIssueInfo(Status.PENDING, null);
    }
}
