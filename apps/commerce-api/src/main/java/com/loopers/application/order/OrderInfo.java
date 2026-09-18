package com.loopers.application.order;

import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;

import java.util.List;

public record OrderInfo(Long id, OrderStatus status, List<OrderItemInfo> items, Long totalAmount, Long paidAmount) {
    public static OrderInfo from(OrderModel model) {
        return new OrderInfo(
            model.getId(),
            model.getStatus(),
            model.getItems().stream().map(OrderItemInfo::from).toList(),
            model.getTotalAmount(),
            model.getPaidAmount()
        );
    }
}
