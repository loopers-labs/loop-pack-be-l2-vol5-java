package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderPaymentInfo;

import java.util.List;

public class OrderV1Dto {

    public record CreateRequest(List<OrderLineRequest> items) {}

    public record OrderLineRequest(Long productId, int quantity) {}

    public record ConfirmRequest(String paymentAttemptKey, String cardType, String cardNo) {
        @Override
        public String toString() {
            return "ConfirmRequest[paymentAttemptKey=" + paymentAttemptKey + ", card=REDACTED]";
        }
    }

    public record PaymentResponse(
        Long orderId,
        String orderStatus,
        String paymentMethod,
        String reservationStatus,
        String paymentStatus,
        long amount,
        boolean checked,
        String statusPath
    ) {
        public static PaymentResponse from(OrderPaymentInfo payment) {
            OrderInfo order = payment.order();
            return new PaymentResponse(
                order.id(),
                order.status(),
                order.paymentMethod(),
                order.reservationStatus(),
                payment.paymentStatus(),
                payment.amount(),
                payment.checked(),
                "/api/v1/orders/" + order.id()
            );
        }
    }

    public record OrderResponse(
        Long id,
        String status,
        long totalAmount,
        long paidAmount,
        String paymentMethod,
        String reservationStatus,
        String paymentStatus,
        List<OrderItemResponse> items
    ) {
        public static OrderResponse from(OrderInfo order) {
            return new OrderResponse(
                order.id(),
                order.status(),
                order.totalAmount(),
                order.paidAmount(),
                order.paymentMethod(),
                order.reservationStatus(),
                order.payment() == null ? null : order.payment().status(),
                order.items().stream().map(OrderItemResponse::from).toList()
            );
        }
    }

    public record OrderItemResponse(Long productId, String productName, long unitPrice, int quantity) {
        public static OrderItemResponse from(OrderInfo.OrderItemInfo item) {
            return new OrderItemResponse(
                item.productId(), item.productName(), item.unitPrice(), item.quantity()
            );
        }
    }

    public record AdminOrderResponse(
        Long id,
        Long userId,
        String status,
        long totalAmount,
        long paidAmount,
        String paymentMethod,
        String reservationStatus,
        String paymentStatus,
        String providerOrderId,
        String transactionKey,
        List<OrderItemResponse> items
    ) {
        public static AdminOrderResponse from(OrderInfo order) {
            OrderInfo.PaymentSummary payment = order.payment();
            return new AdminOrderResponse(
                order.id(),
                order.userId(),
                order.status(),
                order.totalAmount(),
                order.paidAmount(),
                order.paymentMethod(),
                order.reservationStatus(),
                payment == null ? null : payment.status(),
                payment == null ? null : payment.providerOrderId(),
                payment == null ? null : payment.transactionKey(),
                order.items().stream().map(OrderItemResponse::from).toList()
            );
        }
    }
}
