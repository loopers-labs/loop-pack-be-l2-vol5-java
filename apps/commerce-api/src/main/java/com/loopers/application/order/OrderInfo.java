package com.loopers.application.order;

import com.loopers.domain.order.OrderItemModel;
import com.loopers.domain.order.OrderModel;

import java.util.List;

public record OrderInfo(
    Long id,
    String status,
    long totalAmount,
    Long paidAmount,
    List<ItemInfo> items
) {
    public record ItemInfo(Long productId, int quantity, Long unitPrice, long amount) {
        public static ItemInfo from(OrderItemModel item) {
            return new ItemInfo(item.getProductId(), item.getQuantity(), item.getUnitPrice(), item.getAmount());
        }
    }

    public static OrderInfo from(OrderModel order) {
        return new OrderInfo(
            order.getId(),
            order.getStatus().name(),
            order.getTotalAmount(),
            order.getPaidAmount(),
            order.getItems().stream().map(ItemInfo::from).toList()
        );
    }
}
