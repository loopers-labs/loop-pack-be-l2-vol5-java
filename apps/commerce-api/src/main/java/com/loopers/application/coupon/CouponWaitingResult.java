package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponWaitingSnapshot;

import java.util.Objects;

/** created 는 이번 요청이 새 항목을 만든 경우만 true 다. */
public record CouponWaitingResult(boolean created, CouponWaitingSnapshot snapshot) {

    public CouponWaitingResult {
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
