package com.loopers.application.coupon.dto;

/** 당첨자 쿠폰을 DB 에 저장한 결과. {@code couponId} 는 QUOTA_EXHAUSTED 일 때 없다. */
public record CouponWriteResult(Status status, Long couponId) {

    public enum Status {
        ISSUED,
        ALREADY_ISSUED,
        /** DB 한도 방어선이 막았다. Redis 판정을 거친 요청이라면 Redis 와 DB 가 어긋난 것이다. */
        QUOTA_EXHAUSTED
    }

    public static CouponWriteResult issued(Long couponId) {
        return new CouponWriteResult(Status.ISSUED, couponId);
    }

    public static CouponWriteResult alreadyIssued(Long couponId) {
        return new CouponWriteResult(Status.ALREADY_ISSUED, couponId);
    }

    public static CouponWriteResult quotaExhausted() {
        return new CouponWriteResult(Status.QUOTA_EXHAUSTED, null);
    }
}
