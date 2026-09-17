package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import java.util.List;

public record OrderInfo(Long id, Long userId, String status, long totalAmount, List<OrderItemInfo> items) {
    public static OrderInfo from(Order order) {
        return new OrderInfo(order.getId(), order.getUserId(), order.getStatus().name(), order.getTotalAmount(),
            order.getItems().stream().map(OrderItemInfo::from).toList());
    }

    public record OrderItemInfo(Long productId, String productName, long unitPrice, int quantity, long amount) {
        static OrderItemInfo from(OrderItem item) {
            return new OrderItemInfo(item.getProductId(), item.getProductName(), item.getUnitPrice(), item.getQuantity(), item.getAmount());
        }
    }
}
