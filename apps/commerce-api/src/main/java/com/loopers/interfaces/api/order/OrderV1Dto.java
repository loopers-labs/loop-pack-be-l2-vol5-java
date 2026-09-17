package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderFacade.OrderRequestItem;
import com.loopers.application.order.OrderInfo;

import java.util.List;

public class OrderV1Dto {
    public record CreateRequest(List<OrderRequestItem> items) {}
    public record OrderResponse(Long id, Long userId, String status, long totalAmount) {
        public static OrderResponse from(OrderInfo info) {
            return new OrderResponse(info.id(), info.userId(), info.status(), info.totalAmount());
        }
    }
}
