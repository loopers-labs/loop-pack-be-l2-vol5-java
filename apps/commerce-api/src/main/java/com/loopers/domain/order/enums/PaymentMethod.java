package com.loopers.domain.order.enums;

/** 5주차 전 확정 주문은 모두 POINT */
public enum PaymentMethod {
    POINT,
    PG,
    /** 0원 주문이라 결제하지 않음 (D-68) */
    NONE
}
