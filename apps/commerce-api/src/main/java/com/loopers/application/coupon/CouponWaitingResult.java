package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponWaitingSnapshot;

import java.util.Objects;

/** 대기 등록·조회 결과(설계 §12.11). created 는 이번 요청이 새 항목을 만든 경우만 true 다(재등록·기존 결과·조회는 false). */
public record CouponWaitingResult(boolean created, CouponWaitingSnapshot snapshot) {

    public CouponWaitingResult {
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
