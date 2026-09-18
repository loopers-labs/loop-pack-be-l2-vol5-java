package com.loopers.application.order;

import com.loopers.domain.order.OrderStatus;

import java.time.ZonedDateTime;
import java.util.List;

public record AdminOrderInfo(Long orderId, OrderStatus status, List<OrderInfo.ItemInfo> items, long totalAmount,
                             Long paidAmount, ZonedDateTime createdAt, ZonedDateTime confirmedAt, String userId) {
    public AdminOrderInfo {
        items = List.copyOf(items);
    }

    public static AdminOrderInfo from(OrderInfo order, String userId) {
        return new AdminOrderInfo(order.orderId(), order.status(), order.items(), order.totalAmount(),
            order.paidAmount(), order.createdAt(), order.confirmedAt(), userId);
    }
}
