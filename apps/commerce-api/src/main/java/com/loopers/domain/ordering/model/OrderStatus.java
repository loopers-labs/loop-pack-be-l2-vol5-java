package com.loopers.domain.ordering.model;

// 주문 상태
public enum OrderStatus {
    DRAFT,
    CONFIRMED,
    PAYMENT_PENDING,
    PAYMENT_FAILED
}
