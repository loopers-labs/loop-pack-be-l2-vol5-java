package com.loopers.application.order;

import com.loopers.domain.order.OrderModel;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Collectors;

public record OrderConfirmedEvent(
    String eventId,
    long orderId,
    ZonedDateTime confirmedAt,
    List<Item> items
) {
    public record Item(long productId, int quantity) {}

    public static OrderConfirmedEvent from(OrderModel order) {
        String eventId = "order-confirmed:v1:" + order.getId();

        List<Item> normalizedItems = order.getItems().stream()
            .collect(Collectors.groupingBy(
                item -> item.getProductId(),
                Collectors.summingInt(item -> item.getQuantity())
            ))
            .entrySet().stream()
            .map(e -> new Item(e.getKey(), e.getValue()))
            .toList();

        return new OrderConfirmedEvent(eventId, order.getId(), order.getConfirmedAt(), normalizedItems);
    }
}
