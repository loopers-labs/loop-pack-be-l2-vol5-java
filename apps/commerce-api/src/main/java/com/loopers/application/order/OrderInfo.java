package com.loopers.application.order;

import com.loopers.domain.order.OrderItemModel;
import com.loopers.domain.order.OrderModel;

import java.time.ZonedDateTime;
import java.util.List;

/** 설계 4-3-0 OrderResponse / AdminOrderResponse 의 원천. */
public record OrderInfo(
    Long id,
    Long userId,
    String status,
    Long totalAmount,
    Long paidAmount,
    ZonedDateTime confirmedAt,
    List<Item> items,
    ZonedDateTime createdAt
) {
    public record Item(Long productId, Integer quantity, Long unitPrice, Long lineAmount) {
        static Item from(OrderItemModel item) {
            return new Item(item.getProductId(), item.getQuantity(), item.getUnitPrice(), item.lineAmount());
        }
    }

    public static OrderInfo from(OrderModel order) {
        return new OrderInfo(
            order.getId(),
            order.getUserId(),
            order.getStatus().name(),
            order.getTotalAmount(),
            order.getPaidAmount(),
            order.getConfirmedAt(),
            order.getItems().stream().map(Item::from).toList(),
            order.getCreatedAt()
        );
    }
}
