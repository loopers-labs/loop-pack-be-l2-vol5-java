package com.loopers.product.domain;

/**
 * 재고 선점 행의 상태 (ADR-W5-02). RESERVED에서만 CONSUMED 또는 RELEASED로 한 번 바뀌고, 그 뒤로는 바뀌지 않는다.
 */
public enum StockReservationStatus {
    RESERVED,
    CONSUMED,
    RELEASED
}
