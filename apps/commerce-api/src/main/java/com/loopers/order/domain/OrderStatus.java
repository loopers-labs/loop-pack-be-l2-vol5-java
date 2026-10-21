package com.loopers.order.domain;

/**
 * 주문 상태. POINT 주문은 DRAFT → CONFIRMED, PG 주문은 DRAFT → PAYMENT_PENDING → CONFIRMED | PAYMENT_FAILED (W5 plan.md 3-1).
 */
public enum OrderStatus {
    DRAFT,
    PAYMENT_PENDING,
    CONFIRMED,
    PAYMENT_FAILED
}
