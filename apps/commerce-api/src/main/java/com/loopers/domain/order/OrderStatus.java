package com.loopers.domain.order;

/**
 * 주문 축의 상태 — 고객·업무에게 이 주문이 어디까지 왔는지를 나타낸다(docs/week5/design.md 2번 섹션).
 * PAYMENT_PENDING·PAYMENT_FAILED는 PG 결제에만 생긴다. 재고 예약 상태는 이 값에서 계산한다(D-003):
 * PAYMENT_PENDING = 예약 유지, CONFIRMED = 소비, PAYMENT_FAILED = 반환.
 */
public enum OrderStatus {
    DRAFT,
    PAYMENT_PENDING,
    CONFIRMED,
    PAYMENT_FAILED
}
