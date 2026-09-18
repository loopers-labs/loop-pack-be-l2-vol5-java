package com.loopers.domain.order;

/** DRAFT 는 확정 전, CONFIRMED 는 결제를 마친 상태다. 수령 후의 구매확정과는 다르다 (설계 5). */
public enum OrderStatus {
    DRAFT,
    CONFIRMED
}
