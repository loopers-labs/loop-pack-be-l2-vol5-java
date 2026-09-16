package com.loopers.application.order;

import com.loopers.domain.order.OrderModel;

import java.util.List;

public record OrderAdminInfo(
    Long id,
    Long userId,
    String status,
    long totalAmount,
    Long paidAmount,
    List<OrderInfo.ItemInfo> items
) {
    public static OrderAdminInfo from(OrderModel order) {
        return new OrderAdminInfo(
            order.getId(),
            order.getUserId(),
            order.getStatus().name(),
            order.getTotalAmount(),
            order.getPaidAmount(),
            order.getItems().stream().map(OrderInfo.ItemInfo::from).toList()
        );
    }
}
