package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderPaymentInfo;
import com.loopers.domain.order.OrderPaymentMethod;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptStatus;
import org.springframework.http.HttpStatus;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Customer representation of committed order facts, not a mutable JPA entity or a PG response. */
public record OrderPaymentResponse(Long orderId, OrderStatus status, List<OrderInfo.ItemInfo> items, long totalAmount,
                                   Long paidAmount, ZonedDateTime createdAt, ZonedDateTime confirmedAt,
                                   OrderPaymentMethod paymentMethod, PaymentDetails payment, Links links) {
    public OrderPaymentResponse {
        if (orderId == null || orderId <= 0 || status == null || totalAmount < 0 || createdAt == null) {
            throw invalidState();
        }
        items = List.copyOf(items);
        validatePaymentState(status, paymentMethod, payment);
        boolean confirmed = status == OrderStatus.CONFIRMED;
        if (confirmed ? paidAmount == null || paidAmount != totalAmount || confirmedAt == null
            : paidAmount != null || confirmedAt != null) {
            throw invalidState();
        }
        Links expectedLinks = paymentMethod == OrderPaymentMethod.PG ? Links.forOrder(orderId) : null;
        if (!Objects.equals(links, expectedLinks)) {
            throw invalidState();
        }
    }

    public static OrderPaymentResponse from(OrderInfo order, OrderPaymentMethod paymentMethod, PaymentDetails payment) {
        if (order == null) {
            throw invalidState();
        }
        return new OrderPaymentResponse(order.orderId(), order.status(), order.items(), order.totalAmount(),
            order.paidAmount(), order.createdAt(), order.confirmedAt(), paymentMethod, payment,
            paymentMethod == OrderPaymentMethod.PG ? Links.forOrder(order.orderId()) : null);
    }

    public static OrderPaymentResponse from(OrderPaymentInfo info) {
        var payment = info.payment();
        return from(info.order(), info.paymentMethod(), payment == null ? null
            : new PaymentDetails(payment.paymentAttemptKey(), payment.status()));
    }

    /** Only for PG confirm/reconcile responses. A successful GET returns HTTP 200 regardless of payment progress. */
    public HttpStatus confirmHttpStatus() {
        if (paymentMethod != OrderPaymentMethod.PG) {
            throw invalidState();
        }
        return switch (payment.status()) {
            case SUCCESS, FAILED -> HttpStatus.OK;
            case NOT_OBSERVED, PENDING, UNKNOWN -> HttpStatus.ACCEPTED;
        };
    }

    private static void validatePaymentState(OrderStatus status, OrderPaymentMethod method, PaymentDetails payment) {
        if (status == OrderStatus.DRAFT) {
            if (method != null || payment != null) {
                throw invalidState();
            }
            return;
        }
        if (method == OrderPaymentMethod.POINT) {
            if (status != OrderStatus.CONFIRMED || payment != null) {
                throw invalidState();
            }
            return;
        }
        if (method != OrderPaymentMethod.PG || payment == null) {
            throw invalidState();
        }
        OrderStatus expectedStatus = switch (payment.status()) {
            case SUCCESS -> OrderStatus.CONFIRMED;
            case FAILED -> OrderStatus.PAYMENT_FAILED;
            case NOT_OBSERVED, PENDING, UNKNOWN -> OrderStatus.PAYMENT_PENDING;
        };
        if (status != expectedStatus) {
            throw invalidState();
        }
    }

    private static IllegalStateException invalidState() {
        return new IllegalStateException("Stored order payment response is inconsistent");
    }

    public record PaymentDetails(UUID paymentAttemptKey, PaymentAttemptStatus status) {
        public PaymentDetails {
            if (paymentAttemptKey == null || status == null) {
                throw invalidState();
            }
        }
    }

    public record Links(String order, String reconcile) {
        private static Links forOrder(Long orderId) {
            String orderPath = "/api/v1/orders/" + orderId;
            return new Links(orderPath, orderPath + "/payment/reconcile");
        }
    }
}
