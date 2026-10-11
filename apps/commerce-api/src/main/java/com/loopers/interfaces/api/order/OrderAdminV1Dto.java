package com.loopers.interfaces.api.order;

import com.loopers.application.order.query.OrderView;

import java.time.ZonedDateTime;
import java.util.List;

public class OrderAdminV1Dto {
    /** 설계 4-3-0 AdminOrderResponse = OrderResponse + userId(구매자, DR-09). */
    public record AdminOrderResponse(
        Long id,
        Long userId,
        String status,
        Long totalAmount,
        Long paidAmount,
        ZonedDateTime confirmedAt,
        List<OrderV1Dto.OrderItemResponse> items,
        ZonedDateTime createdAt
    ) {
        public static AdminOrderResponse from(OrderView.Detail view) {
            OrderV1Dto.OrderResponse base = OrderV1Dto.OrderResponse.from(view);
            return new AdminOrderResponse(
                base.id(), view.userId(), base.status(), base.totalAmount(), base.paidAmount(), base.confirmedAt(),
                base.items(), base.createdAt());
        }
    }

    /** 설계 4-3-0 AdminOrderGroup. */
    public record AdminOrderGroupResponse(Long userId, List<AdminOrderResponse> orders) {
        public static AdminOrderGroupResponse from(OrderView.BuyerGroup group) {
            return new AdminOrderGroupResponse(group.userId(), group.orders().stream().map(AdminOrderResponse::from).toList());
        }
    }
}
