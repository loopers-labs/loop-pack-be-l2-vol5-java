package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderFacade;
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
        List<OrderV1Dto.OrderItemResponse> items,
        String paymentMethod,
        String paymentStatus
    ) {
        public static AdminOrderResponse from(Order order) {
            return of(order, OrderV1Dto.OrderResponse.from(order));
        }

        public static AdminOrderResponse from(OrderFacade.OrderDetail detail) {
            return of(detail.order(), OrderV1Dto.OrderResponse.from(detail));
        }

        private static AdminOrderResponse of(Order order, OrderV1Dto.OrderResponse base) {
            return new AdminOrderResponse(
                base.id(), order.getUserId(), base.status(), base.totalAmount(),
                base.paidAmount(), base.placedAt(), base.confirmedAt(), base.items(),
                base.paymentMethod(), base.paymentStatus());
        }
    }
}
