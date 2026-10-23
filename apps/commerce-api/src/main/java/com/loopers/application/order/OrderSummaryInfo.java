package com.loopers.application.order;

import com.loopers.domain.order.OrderSummary;
import com.loopers.domain.payment.entity.Payment;

import java.time.ZonedDateTime;
import java.util.Map;

/** paymentStatus 는 결제 시도가 없으면(DRAFT · 이전 POINT 주문) 비어 있음 */
public record OrderSummaryInfo(
    Long id,
    Long userId,
    String status,
    long totalAmount,
    Long paymentAmount,
    int itemCount,
    String representativeProductName,
    ZonedDateTime orderedAt,
    String paymentMethod,
    String paymentStatus
) {
    /** paymentsByOrderId 는 한 페이지의 결제를 한 번에 조회한 것 */
    public static OrderSummaryInfo of(OrderSummary summary, Map<Long, Payment> paymentsByOrderId) {
        Payment payment = paymentsByOrderId.get(summary.id());
        return new OrderSummaryInfo(
            summary.id(),
            summary.userId(),
            summary.status().name(),
            summary.totalAmount(),
            summary.paymentAmount(),
            summary.itemCount(),
            summary.representativeProductName(),
            summary.orderedAt(),
            summary.paymentMethod() == null ? null : summary.paymentMethod().name(),
            payment == null ? null : payment.getStatus().name()
        );
    }
}
