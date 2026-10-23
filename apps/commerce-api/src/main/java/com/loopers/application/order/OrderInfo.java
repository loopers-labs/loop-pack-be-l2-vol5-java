package com.loopers.application.order;

import com.loopers.application.payment.PaymentStatusInfo;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.payment.PaymentAttempt;
import java.util.List;

public record OrderInfo(
    Long id,
    Long userId,
    String status,
    long totalAmount,
    Long paymentAmount,
    String paymentResult,
    List<OrderItemInfo> items,
    PaymentStatusInfo payment
) {
    // 시도가 없는 DRAFT·이전 POINT 주문은 attempt가 null이다.
    public static OrderInfo from(Order order, PaymentAttempt attempt) {
        return new OrderInfo(
            order.getId(),
            order.getUserId(),
            order.getStatus().name(),
            order.getTotalAmount(),
            order.getPaymentAmount(),
            order.getPaymentResult() == null ? null : order.getPaymentResult().name(),
            order.getItems().stream().map(OrderItemInfo::from).toList(),
            PaymentStatusInfo.from(order, attempt)
        );
    }

    public record OrderItemInfo(Long productId, String productName, long unitPrice, int quantity, long amount) {
        static OrderItemInfo from(OrderItem item) {
            return new OrderItemInfo(item.getProductId(), item.getProductName(), item.getUnitPrice(), item.getQuantity(), item.getAmount());
        }
    }
}
