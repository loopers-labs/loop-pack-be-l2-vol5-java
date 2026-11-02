package com.loopers.interfaces.consumer;

import com.loopers.domain.purchase.OrderConfirmation;

import java.time.OffsetDateTime;
import java.util.List;

record OrderConfirmedMessageV1(
    String eventType,
    int schemaVersion,
    String eventId,
    long orderId,
    String confirmedAt,
    List<Item> items
) {
    OrderConfirmedMessageV1 {
        items = List.copyOf(items);
    }

    OrderConfirmation toOrderConfirmation() {
        return new OrderConfirmation(eventId, orderId, OffsetDateTime.parse(confirmedAt).toInstant(),
            items.stream().map(item -> new OrderConfirmation.Item(item.productId(), item.quantity())).toList());
    }

    record Item(long productId, int quantity) {}
}
