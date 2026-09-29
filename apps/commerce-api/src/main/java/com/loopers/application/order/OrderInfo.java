package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;

import java.util.List;

public record OrderInfo(
    Long orderId,
    Long userId,
    String status,
    long totalAmount,
    long paidAmount,
    List<Item> items
) {
    public record Item(Long productId, int quantity, long unitPrice, long amount) {
        public static Item from(OrderItem orderItem) {
            return new Item(
                orderItem.getProductId(),
                orderItem.getQuantity(),
                orderItem.getUnitPrice(),
                orderItem.getAmount()
            );
        }
    }

    public static OrderInfo from(Order order) {
        return new OrderInfo(
            order.getId(),
            order.getUserId(),
            order.getStatus().name(),
            order.getTotalAmount(),
            order.getPaidAmount(),
            order.getItems().stream().map(Item::from).toList()
        );
    }
}
