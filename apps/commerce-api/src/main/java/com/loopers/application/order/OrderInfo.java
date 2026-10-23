package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentMethod;
import com.loopers.domain.payment.PaymentStatus;

import java.util.UUID;

import java.util.List;

public record OrderInfo(
        long orderId,
        long userId,
        String status,
        List<Item> items,
        long totalAmount,
        Long paidAmount,
        String paymentResult,
        PaymentMethod paymentMethod,
        PaymentInfo payment) {
    public record PaymentInfo(UUID paymentAttemptKey, String providerOrderId,
            String transactionKey, PaymentStatus status) {
        static PaymentInfo from(Payment payment) {
            return new PaymentInfo(payment.paymentAttemptKey(), payment.providerOrderId(),
                    payment.transactionKey(), payment.status());
        }
    }
    public record Item(long productId, int quantity, long unitPrice, long lineAmount) {
    }

    public static OrderInfo from(Order order) {
        return from(order, null);
    }

    public static OrderInfo from(Order order, Payment payment) {
        return new OrderInfo(
                order.getId(),
                order.getUserId(),
                order.getStatus().name(),
                order.getItems().stream()
                        .map(
                                item ->
                                        new Item(
                                                item.getProductId(),
                                                item.getQuantity(),
                                                item.getUnitPrice(),
                                                item.getLineAmount()))
                        .toList(),
                order.getTotalAmount(),
                order.getPaidAmount(),
                order.getPaymentResult() == null ? null : order.getPaymentResult().name(),
                payment != null ? PaymentMethod.CARD : (order.getPaidAmount() != null ? PaymentMethod.POINT : null),
                payment == null ? null : PaymentInfo.from(payment));
    }
}
