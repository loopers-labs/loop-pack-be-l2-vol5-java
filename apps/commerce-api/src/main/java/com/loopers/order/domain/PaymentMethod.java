package com.loopers.order.domain;

/**
 * 결제 수단. POINT는 W2~W4에 확정한 주문이 그대로 가진다 (W5 plan.md 1).
 */
public enum PaymentMethod {
    POINT,
    PG
}
