package com.loopers.domain.order;

/**
 * DRAFT 는 확정 전, CONFIRMED 는 결제를 마친 상태다. 수령 후의 구매확정과는 다르다 (설계 5).
 * PG 결제는 PAYMENT_PENDING 을 거쳐 CONFIRMED 또는 PAYMENT_FAILED 로 끝남 (5주차 설계 2.1)
 */
public enum OrderStatus {
    DRAFT,
    /** 재고를 확보하고 PG 결과를 기다리는 중 */
    PAYMENT_PENDING,
    CONFIRMED,
    /** 결제가 확정 실패함. 같은 주문으로 다시 결제하지 않음 */
    PAYMENT_FAILED
}
