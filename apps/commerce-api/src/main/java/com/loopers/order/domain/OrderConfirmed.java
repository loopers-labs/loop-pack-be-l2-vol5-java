package com.loopers.order.domain;

import java.util.List;

/**
 * 주문 확정 이벤트 (W6 D-2). Red 단계의 뼈대 — 아직 값을 채우지 않는다.
 */
public record OrderConfirmed(
    String eventId,
    String eventType,
    int schemaVersion,
    Long orderId,
    String confirmedAt,
    List<Item> items
) {

    public record Item(Long productId, int quantity) {}

    public static OrderConfirmed from(String eventId, OrderModel order) {
        return new OrderConfirmed(null, null, 0, null, null, List.of());
    }
}
