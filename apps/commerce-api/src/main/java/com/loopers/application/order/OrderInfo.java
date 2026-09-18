package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;

import java.time.ZonedDateTime;
import java.util.List;

public record OrderInfo(
    Long id,
    Long userId,
    String status,
    long totalAmount,
    Long paymentAmount,
    ZonedDateTime paidAt,
    ZonedDateTime orderedAt,
    List<Item> items
) {
    public record Item(Long productId, String productName, long unitPrice, int quantity, long amount) {
        static Item from(OrderItem item) {
            return new Item(item.getProductId(), item.getProductName(), item.getUnitPrice(), item.getQuantity(), item.getAmount());
        }
    }

    public static OrderInfo from(Order order) {
        return new OrderInfo(
            order.getId(),
            order.getUserId(),
            order.getStatus().name(),
            order.getTotalAmount(),
            order.getPaymentAmount(),
            order.getPaidAt(),
            order.getCreatedAt(),
            order.getItems().stream().map(Item::from).toList()
        );
    }
}
