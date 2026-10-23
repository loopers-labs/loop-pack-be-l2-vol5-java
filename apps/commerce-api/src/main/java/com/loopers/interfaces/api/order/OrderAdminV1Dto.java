package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderInfo;

import java.util.List;

public class OrderAdminV1Dto {

    public record AdminOrderItemResponse(Long productId, Long quantity, Long unitPrice, Long amount) {
        static AdminOrderItemResponse from(OrderInfo.Item item) {
            return new AdminOrderItemResponse(item.productId(), item.quantity(), item.unitPrice(), item.amount());
        }
    }

    public record AdminOrderPaymentResponse(String method, String status, String paymentAttemptKey, Long amount,
                                      String orderUrl, String reconcileUrl, String verificationStatus) {
        static AdminOrderPaymentResponse from(Long orderId, OrderInfo.PaymentInfo payment) {
            if (payment == null) {
                return null;
            }
            String reconcileUrl = "PG".equals(payment.method())
                ? "/api/v1/orders/" + orderId + "/payment/reconcile" : null;
            return new AdminOrderPaymentResponse(payment.method(), payment.status(), payment.paymentAttemptKey(),
                payment.amount(), "/api-admin/v1/orders/" + orderId, reconcileUrl, payment.verificationStatus());
        }
    }

    public record AdminOrderResponse(Long id, Long userId, String status, Long totalAmount, Long pointAmount, Long paidAmount,
                                AdminOrderPaymentResponse payment, List<AdminOrderItemResponse> items) {
        public static AdminOrderResponse from(OrderInfo info) {
            return new AdminOrderResponse(info.id(), info.userId(), info.status(), info.totalAmount(), info.pointAmount(),
                info.paidAmount(), AdminOrderPaymentResponse.from(info.id(), info.payment()),
                info.items().stream().map(AdminOrderItemResponse::from).toList());
        }
    }
}
