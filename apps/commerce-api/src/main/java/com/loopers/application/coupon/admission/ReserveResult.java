package com.loopers.application.coupon.admission;

/** {@code couponId} 는 ISSUED 일 때만 있다. */
public record ReserveResult(ReserveOutcome outcome, Long couponId) {

    public static ReserveResult of(ReserveOutcome outcome) {
        return new ReserveResult(outcome, null);
    }

    public static ReserveResult issued(Long couponId) {
        return new ReserveResult(ReserveOutcome.ISSUED, couponId);
    }
}
