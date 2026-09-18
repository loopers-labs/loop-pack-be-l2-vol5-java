package com.loopers.interfaces.api.order;

import com.loopers.domain.common.PageWindow;
import com.loopers.domain.order.Order;

import java.time.Instant;
import java.util.List;

public class OrderAdminV1Dto {

    public record AdminOrderResponse(
        Long id,
        Long userId,
        String status,
        long totalAmount,
        Long paidAmount,
        Instant placedAt,
        Instant confirmedAt,
        List<OrderV1Dto.OrderItemResponse> items
    ) {
        public static AdminOrderResponse from(Order order) {
            OrderV1Dto.OrderResponse base = OrderV1Dto.OrderResponse.from(order);
            return new AdminOrderResponse(
                base.id(), order.getUserId(), base.status(), base.totalAmount(),
                base.paidAmount(), base.placedAt(), base.confirmedAt(), base.items());
        }
    }

    public record AdminOrderPageResponse(
        List<AdminOrderResponse> items, int page, int size, boolean hasNext) {
        public static AdminOrderPageResponse of(PageWindow<Order> orderPage, int page, int size) {
            return new AdminOrderPageResponse(
                orderPage.items().stream().map(AdminOrderResponse::from).toList(),
                page, size, orderPage.hasNext());
        }
    }
}
