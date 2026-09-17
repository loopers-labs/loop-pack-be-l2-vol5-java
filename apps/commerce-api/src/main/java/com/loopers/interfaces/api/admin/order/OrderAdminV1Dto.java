package com.loopers.interfaces.api.admin.order;

import com.loopers.application.order.OrderAdminInfo;
import com.loopers.application.order.OrderListAdminInfo;
import com.loopers.interfaces.api.order.OrderV1Dto;

import java.util.List;

public class OrderAdminV1Dto {
    public record OrderResponse(
        Long id,
        Long userId,
        String status,
        long totalAmount,
        Long paidAmount,
        List<OrderV1Dto.ItemResponse> items
    ) {
        public static OrderResponse from(OrderAdminInfo info) {
            return new OrderResponse(
                info.id(), info.userId(), info.status(), info.totalAmount(), info.paidAmount(),
                info.items().stream().map(OrderV1Dto.ItemResponse::from).toList()
            );
        }
    }

    public record OrdersResponse(List<OrderResponse> items, int page, int size, long totalCount) {
        public static OrdersResponse from(OrderListAdminInfo info) {
            return new OrdersResponse(
                info.items().stream().map(OrderResponse::from).toList(),
                info.page(), info.size(), info.totalCount()
            );
        }
    }
}
