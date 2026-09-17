package com.loopers.application.order;

import com.loopers.domain.order.BuyerOrders;

import java.util.List;

/** 설계 4-3-0 AdminOrderGroup. */
public record OrderGroupInfo(Long userId, List<OrderInfo> orders) {
    public static OrderGroupInfo from(BuyerOrders group) {
        return new OrderGroupInfo(group.userId(), group.orders().stream().map(OrderInfo::from).toList());
    }
}
