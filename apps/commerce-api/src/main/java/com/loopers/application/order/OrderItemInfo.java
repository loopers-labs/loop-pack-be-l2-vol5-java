package com.loopers.application.order;

import com.loopers.domain.order.OrderItem;

public record OrderItemInfo(Long productId, String productName, long quantity, long unitPrice, long amount) {
    public static OrderItemInfo of(OrderItem item, String productName) {
        return new OrderItemInfo(item.getProductId(), productName, item.getQuantity(), item.getUnitPrice(), item.getAmount());
    }
}
