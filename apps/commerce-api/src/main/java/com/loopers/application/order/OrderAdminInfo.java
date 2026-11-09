package com.loopers.application.order;

import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.payment.PaymentAttemptModel;

import java.util.List;

public record OrderAdminInfo(
    Long id,
    Long userId,
    OrderStatus status,
    List<OrderItemInfo> items,
    Long totalAmount,
    Long paidAmount,
    PaymentMethod paymentMethod,
    PaymentInfo payment
) {
    public static OrderAdminInfo from(OrderModel model) {
        return from(model, null);
    }

    public static OrderAdminInfo from(OrderModel model, PaymentAttemptModel attempt) {
        return new OrderAdminInfo(
            model.getId(),
            model.getUserId(),
            model.getStatus(),
            model.getItems().stream().map(OrderItemInfo::from).toList(),
            model.getTotalAmount(),
            model.getPaidAmount(),
            model.getPaymentMethod(),
            attempt == null ? null : PaymentInfo.from(attempt)
        );
    }
}
