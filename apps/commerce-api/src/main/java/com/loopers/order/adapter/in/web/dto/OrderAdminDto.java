package com.loopers.order.adapter.in.web.dto;

import com.loopers.order.application.port.in.OrderInfo;
import com.loopers.order.application.port.in.OrderSummaryInfo;
import com.loopers.order.domain.OrderStatus;
import com.loopers.order.domain.PaymentMethod;

import java.time.ZonedDateTime;
import java.util.List;

public class OrderAdminDto {

    /**
     * 관리자 주문 상세: 고객 상세 + 구매자 userId (7-2).
     */
    public record OrderResponse(
        Long id,
        Long userId,
        OrderStatus status,
        List<OrderDto.ItemResponse> items,
        long totalAmount,
        Long paidAmount,
        PaymentMethod paymentMethod,
        ZonedDateTime confirmedAt,
        ZonedDateTime createdAt
    ) {
        public static OrderResponse from(OrderInfo info) {
            return new OrderResponse(
                info.id(),
                info.userId(),
                info.status(),
                info.items().stream().map(OrderDto.ItemResponse::from).toList(),
                info.totalAmount(),
                info.paidAmount(),
                info.paymentMethod(),
                info.confirmedAt(),
                info.createdAt()
            );
        }
    }

    public record OrderSummaryResponse(Long id, Long userId, OrderStatus status, long totalAmount, Long paidAmount, ZonedDateTime createdAt) {
        public static OrderSummaryResponse from(OrderSummaryInfo info) {
            return new OrderSummaryResponse(info.id(), info.userId(), info.status(), info.totalAmount(), info.paidAmount(), info.createdAt());
        }
    }
}
