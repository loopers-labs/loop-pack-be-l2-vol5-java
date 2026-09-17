package com.loopers.interfaces.api.admin.order;

import com.loopers.application.order.AdminOrderInfo;
import com.loopers.application.order.OrderInfo;
import com.loopers.interfaces.api.order.OrderDto;

import java.time.ZonedDateTime;
import java.util.List;

public class AdminOrderDto {
    // 고객 주문 응답 + 구매자 ID (7-0 관리자 주문)
    public record OrderResponse(
        Long orderId,
        Long userId,
        String status,
        List<OrderDto.ItemResponse> items,
        Long totalAmount,
        Long paidAmount,
        ZonedDateTime createdAt,
        ZonedDateTime confirmedAt
    ) {
        public static OrderResponse from(AdminOrderInfo info) {
            OrderInfo order = info.order();
            return new OrderResponse(
                order.orderId(),
                info.userId(),
                order.status(),
                order.items().stream().map(OrderDto.ItemResponse::from).toList(),
                order.totalAmount(),
                order.paidAmount(),
                order.createdAt(),
                order.confirmedAt()
            );
        }
    }
}
