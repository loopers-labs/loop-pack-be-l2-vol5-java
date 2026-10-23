package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderInfo;
import com.loopers.application.payment.PaymentConfirmCommand;

import java.util.List;

public class OrderV1Dto {

    public record OrderItemRequest(Long productId, Long quantity) {}

    public record OrderCreateRequest(List<OrderItemRequest> items) {}

    public record OrderConfirmRequest(String paymentAttemptKey, String cardType, String cardNo) {
        PaymentConfirmCommand toCommand() {
            return new PaymentConfirmCommand(paymentAttemptKey, cardType, cardNo);
        }

        @Override
        public String toString() {
            return "OrderConfirmRequest[paymentAttemptKey=" + paymentAttemptKey + "]";
        }
    }

    public record OrderItemResponse(Long productId, Long quantity, Long unitPrice, Long amount) {
        static OrderItemResponse from(OrderInfo.Item item) {
            return new OrderItemResponse(item.productId(), item.quantity(), item.unitPrice(), item.amount());
        }
    }

    public record OrderPaymentResponse(String method, String status, String paymentAttemptKey, Long amount,
                                      String orderUrl, String reconcileUrl, String verificationStatus) {
        static OrderPaymentResponse from(Long orderId, OrderInfo.PaymentInfo payment) {
            if (payment == null) {
                return null;
            }
            String reconcileUrl = "PG".equals(payment.method())
                ? "/api/v1/orders/" + orderId + "/payment/reconcile" : null;
            return new OrderPaymentResponse(payment.method(), payment.status(), payment.paymentAttemptKey(),
                payment.amount(), "/api/v1/orders/" + orderId, reconcileUrl, payment.verificationStatus());
        }
    }

    public record OrderResponse(Long id, String status, Long totalAmount, Long pointAmount, Long paidAmount,
                                OrderPaymentResponse payment, List<OrderItemResponse> items) {
        public static OrderResponse from(OrderInfo info) {
            return new OrderResponse(info.id(), info.status(), info.totalAmount(), info.pointAmount(),
                info.paidAmount(), OrderPaymentResponse.from(info.id(), info.payment()),
                info.items().stream().map(OrderItemResponse::from).toList());
        }
    }
}
