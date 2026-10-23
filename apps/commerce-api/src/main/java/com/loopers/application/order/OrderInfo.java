package com.loopers.application.order;

import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.payment.PaymentAttemptModel;

import java.util.List;

/**
 * @param paymentMethod DRAFT는 비어 있다. 4주차까지 확정한 주문은 POINT, 5주차부터 시작한 결제는 PG다
 * @param payment       PG 결제 시도가 없으면(DRAFT, 이전 POINT 주문) 비어 있다
 */
public record OrderInfo(
    Long id,
    OrderStatus status,
    List<OrderItemInfo> items,
    Long totalAmount,
    Long paidAmount,
    PaymentMethod paymentMethod,
    PaymentInfo payment
) {
    public static OrderInfo from(OrderModel model) {
        return from(model, null);
    }

    public static OrderInfo from(OrderModel model, PaymentAttemptModel attempt) {
        return new OrderInfo(
            model.getId(),
            model.getStatus(),
            model.getItems().stream().map(OrderItemInfo::from).toList(),
            model.getTotalAmount(),
            model.getPaidAmount(),
            model.getPaymentMethod(),
            attempt == null ? null : PaymentInfo.from(attempt)
        );
    }

    public OrderInfo withLookupUnavailable() {
        return new OrderInfo(id, status, items, totalAmount, paidAmount, paymentMethod, payment == null ? null : payment.withLookupUnavailable());
    }
}
