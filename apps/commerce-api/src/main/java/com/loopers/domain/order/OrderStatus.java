package com.loopers.domain.order;

public enum OrderStatus {
    DRAFT,
    /** PG 결제를 시작했고 결과를 기다리거나 모른다. 재고는 확보(예약)돼 있다. */
    PAYMENT_PENDING,
    CONFIRMED,
    /** PG 결제가 확정 실패했다. 확보한 재고는 한 번 반환됐다. */
    PAYMENT_FAILED
}
