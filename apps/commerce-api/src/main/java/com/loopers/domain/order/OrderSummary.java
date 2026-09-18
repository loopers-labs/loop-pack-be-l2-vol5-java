package com.loopers.domain.order;

import java.time.ZonedDateTime;

/** 주문 목록의 한 줄. 품목 전체 대신 개수와 대표 상품명(첫 품목)만 담는다 (설계 6.4). */
public record OrderSummary(
    Long id,
    Long userId,
    OrderStatus status,
    long totalAmount,
    Long paymentAmount,
    int itemCount,
    String representativeProductName,
    ZonedDateTime orderedAt
) {
    public static OrderSummary from(Order order) {
        return new OrderSummary(
            order.getId(),
            order.getUserId(),
            order.getStatus(),
            order.getTotalAmount(),
            order.getPaymentAmount(),
            order.getItems().size(),
            order.getItems().get(0).getProductName(),
            order.getCreatedAt()
        );
    }
}
