package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderGroupInfo;
import com.loopers.application.order.OrderInfo;

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
        public static AdminOrderResponse from(OrderInfo info) {
            OrderV1Dto.OrderResponse base = OrderV1Dto.OrderResponse.from(info);
            return new AdminOrderResponse(
                base.id(), info.userId(), base.status(), base.totalAmount(), base.paidAmount(), base.confirmedAt(),
                base.items(), base.createdAt());
        }
    }

    /** 설계 4-3-0 AdminOrderGroup. */
    public record AdminOrderGroupResponse(Long userId, List<AdminOrderResponse> orders) {
        public static AdminOrderGroupResponse from(OrderGroupInfo info) {
            return new AdminOrderGroupResponse(info.userId(), info.orders().stream().map(AdminOrderResponse::from).toList());
        }
    }
}
