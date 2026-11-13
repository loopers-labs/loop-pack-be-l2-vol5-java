package com.loopers.application.coupon.dto;

/**
 * 마감 보정 결과. {@code issued} 는 이번에 저장한 쿠폰, {@code recorded} 는 이미 DB 에 있어 결과만 기록한 쿠폰,
 * {@code remaining} 은 자리가 없거나 저장에 실패해 다음 보정으로 넘긴 당첨자 수다.
 */
public record CouponReconcileInfo(int issued, int recorded, int remaining) {
}
