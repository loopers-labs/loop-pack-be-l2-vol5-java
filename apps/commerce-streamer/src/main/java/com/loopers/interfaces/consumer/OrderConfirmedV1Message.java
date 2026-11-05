package com.loopers.interfaces.consumer;

import java.util.List;

record OrderConfirmedV1Message(
    String eventType,
    Integer schemaVersion,
    String eventId,
    Long orderId,
    String confirmedAt,
    List<Item> items
) {

    record Item(Long productId, Integer quantity) {
    }
}
