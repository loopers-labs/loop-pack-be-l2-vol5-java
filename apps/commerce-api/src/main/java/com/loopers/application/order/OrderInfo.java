package com.loopers.application.order;

import com.loopers.domain.order.Order;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

public record OrderInfo(
    Long orderId,
    String status,
    List<OrderItemInfo> items,
    long totalAmount,
    Long paidAmount,
    ZonedDateTime createdAt,
    ZonedDateTime confirmedAt
) {
    public static OrderInfo of(Order order, Map<Long, String> productNames) {
        List<OrderItemInfo> items = order.getItems().stream()
            .map(item -> OrderItemInfo.of(item, productNames.get(item.getProductId())))
            .toList();
        return new OrderInfo(
            order.getId(),
            order.getStatus().name(),
            items,
            order.getTotalAmount(),
            order.getPaidAmount(),
            order.getCreatedAt(),
            order.getConfirmedAt()
        );
    }
}
