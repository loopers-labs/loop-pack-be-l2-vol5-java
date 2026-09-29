package com.loopers.interfaces.api.admin.order;

import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderSummaryInfo;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.interfaces.api.order.OrderV1Dto;

import java.time.ZonedDateTime;
import java.util.List;

public class OrderAdminV1Dto {

    /**
     * 관리자 주문 상세: 고객 상세 + 구매자 userId (7-2).
     */
    public record OrderResponse(
        Long id,
        Long userId,
        OrderStatus status,
        List<OrderV1Dto.ItemResponse> items,
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
                info.items().stream().map(OrderV1Dto.ItemResponse::from).toList(),
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
