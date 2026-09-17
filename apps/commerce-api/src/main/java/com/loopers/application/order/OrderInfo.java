package com.loopers.application.order;

import com.loopers.domain.order.Order;

public record OrderInfo(Long id, Long userId, String status, long totalAmount) {
    public static OrderInfo from(Order order) {
        return new OrderInfo(order.getId(), order.getUserId(), order.getStatus().name(), order.getTotalAmount());
    }
}
