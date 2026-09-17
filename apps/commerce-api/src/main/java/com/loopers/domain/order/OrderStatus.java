package com.loopers.domain.order;

/** ST-03 주문 상태. DRAFT → CONFIRMED 만 허용. FAILED 없음 (ASM-12). */
public enum OrderStatus {
    DRAFT,
    CONFIRMED
}
