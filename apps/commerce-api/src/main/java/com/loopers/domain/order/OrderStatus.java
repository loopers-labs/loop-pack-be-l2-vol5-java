package com.loopers.domain.order;

/**
 * DRAFT 는 결제 시작 전, PAYMENT_PENDING 은 PG 결제 시작 후 결과 미확정(재고 확보 유지),
 * CONFIRMED 는 결제 완료, PAYMENT_FAILED 는 PG 결제 실패 확정(확보 재고 반환)이다.
 * 기존 DB 의 MySQL ENUM 열을 유지하므로 값을 추가할 때 보존 전환(설계 3.6)도 함께 맞춘다.
 */
public enum OrderStatus {
    DRAFT,
    CONFIRMED,
    PAYMENT_PENDING,
    PAYMENT_FAILED
}
