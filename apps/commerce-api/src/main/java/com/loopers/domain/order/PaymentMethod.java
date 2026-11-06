package com.loopers.domain.order;

/** 주문의 결제수단. 4주차까지 확정한 주문은 POINT, 5주차부터 시작하는 결제는 PG다. */
public enum PaymentMethod {
    POINT,
    PG
}
