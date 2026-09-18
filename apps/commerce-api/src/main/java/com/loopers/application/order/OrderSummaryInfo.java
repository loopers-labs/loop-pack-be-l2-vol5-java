package com.loopers.application.order;

import com.loopers.domain.order.OrderSummary;

import java.time.ZonedDateTime;

public record OrderSummaryInfo(
    Long id,
    Long userId,
    String status,
    long totalAmount,
    Long paymentAmount,
    int itemCount,
    String representativeProductName,
    ZonedDateTime orderedAt
) {
    public static OrderSummaryInfo from(OrderSummary summary) {
        return new OrderSummaryInfo(
            summary.id(),
            summary.userId(),
            summary.status().name(),
            summary.totalAmount(),
            summary.paymentAmount(),
            summary.itemCount(),
            summary.representativeProductName(),
            summary.orderedAt()
        );
    }
}
