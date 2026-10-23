package com.loopers.application.order.dto;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderStatus;

import java.time.ZonedDateTime;

/**
 * 주문 목록의 항목. 품목 자체는 담지 않고 개수만 담는다. 품목은 상세에서 본다.
 * 결제 결과({@code payment})는 확정된 주문에만 있다.
 */
public record OrderSummaryInfo(
    Long id,
    Long userId,
    OrderStatus status,
    long totalAmount,
    int itemCount,
    PaymentInfo payment,
    ZonedDateTime orderedAt
) {
    public static OrderSummaryInfo from(Order order) {
        return new OrderSummaryInfo(
            order.getId(),
            order.getUserId(),
            order.getStatus(),
            order.getTotalAmount().amount(),
            order.getItems().size(),
            PaymentInfo.from(order.getPaymentResult()),
            order.getCreatedAt()
        );
    }
}
