package com.loopers.application.order;

import com.loopers.domain.order.OrderItem;

public record OrderItemInfo(Long productId, int quantity, Long unitPrice, Long subtotal) {
    public static OrderItemInfo from(OrderItem item) {
        return new OrderItemInfo(item.getProductId(), item.getQuantity(), item.getUnitPrice(), item.getSubtotal());
    }
}
