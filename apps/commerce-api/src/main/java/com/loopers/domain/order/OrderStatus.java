package com.loopers.domain.order;

/** ST-03 주문 상태. DRAFT → AWAITING_PAYMENT(재고 예약, PG 결제 요청) → CONFIRMED(결제 성공) | FAILED(결제 실패, 재고 반환). 종료 상태는 CONFIRMED·FAILED. */
public enum OrderStatus {
    DRAFT,
    AWAITING_PAYMENT,
    CONFIRMED,
    FAILED
}
