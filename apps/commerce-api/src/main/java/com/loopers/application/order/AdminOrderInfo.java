package com.loopers.application.order;

import com.loopers.domain.order.Order;

import java.util.Map;

public record AdminOrderInfo(Long userId, OrderInfo order) {
    public static AdminOrderInfo of(Order order, Map<Long, String> productNames) {
        return new AdminOrderInfo(order.getUserId(), OrderInfo.of(order, productNames));
    }
}
