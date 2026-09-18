package com.loopers.application.order;

import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;

import java.util.List;

public record OrderAdminInfo(
    Long id, Long userId, OrderStatus status, List<OrderItemInfo> items, Long totalAmount, Long paidAmount
) {
    public static OrderAdminInfo from(OrderModel model) {
        return new OrderAdminInfo(
            model.getId(),
            model.getUserId(),
            model.getStatus(),
            model.getItems().stream().map(OrderItemInfo::from).toList(),
            model.getTotalAmount(),
            model.getPaidAmount()
        );
    }
}
