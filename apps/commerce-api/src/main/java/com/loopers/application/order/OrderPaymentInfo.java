package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderPaymentMethod;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptStatus;

import java.util.UUID;

/** A committed order/payment snapshot. No provider identifiers, fingerprints or transient card inputs are exposed. */
public record OrderPaymentInfo(OrderInfo order, OrderPaymentMethod paymentMethod, PaymentDetails payment) {
    public static OrderPaymentInfo from(Order order, PaymentAttempt attempt) {
        OrderPaymentMethod method = order.getPaymentMethod();
        if (method != OrderPaymentMethod.PG) {
            if (attempt != null || (method == null ? order.getStatus() != OrderStatus.DRAFT
                : method != OrderPaymentMethod.POINT || order.getStatus() != OrderStatus.CONFIRMED)) {
                throw inconsistentState();
            }
            return new OrderPaymentInfo(OrderInfo.from(order), method, null);
        }
        order.validatePgPaymentState();
        if (attempt == null || attempt.getOrderId() != order.getId() || attempt.getAmount() != order.getTotalAmount()
            || attempt.getSendStartedAt() == null || attempt.getAttemptKey() == null || attempt.getStatus() == null) {
            throw inconsistentState();
        }
        OrderStatus expected = switch (attempt.getStatus()) {
            case SUCCESS -> OrderStatus.CONFIRMED;
            case FAILED -> OrderStatus.PAYMENT_FAILED;
            case NOT_OBSERVED, PENDING, UNKNOWN -> OrderStatus.PAYMENT_PENDING;
        };
        if (order.getStatus() != expected) {
            throw inconsistentState();
        }
        return new OrderPaymentInfo(OrderInfo.from(order), method,
            new PaymentDetails(attempt.getAttemptKey(), attempt.getStatus()));
    }

    private static IllegalStateException inconsistentState() {
        return new IllegalStateException("Stored order and payment snapshot are inconsistent");
    }

    public record PaymentDetails(UUID paymentAttemptKey, PaymentAttemptStatus status) {
    }
}
