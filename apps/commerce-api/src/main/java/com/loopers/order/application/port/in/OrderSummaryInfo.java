package com.loopers.order.application.port.in;

import com.loopers.order.domain.OrderModel;
import com.loopers.order.domain.OrderStatus;

import java.time.ZonedDateTime;

/**
 * 주문 목록 결과. 품목을 읽지 않아 목록 조회에서 품목 지연 로딩이 일어나지 않는다.
 */
public record OrderSummaryInfo(Long id, Long userId, OrderStatus status, long totalAmount, Long paidAmount, ZonedDateTime createdAt) {
    public static OrderSummaryInfo from(OrderModel order) {
        return new OrderSummaryInfo(
            order.getId(),
            order.getUserId(),
            order.getStatus(),
            order.getTotalAmount().amount(),
            OrderInfo.amountOrNull(order.getPaidAmount()),
            order.getCreatedAt()
        );
    }
}
