package com.loopers.application.order;

import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.payment.PaymentAttempt;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 주문과 (있다면) 결제 시도의 저장 상태. 결제를 시작하지 않은 주문과 과거 POINT 주문은 payment가 null이다. */
public record OrderInfo(
    Long id,
    Long userId,
    String status,
    long totalAmount,
    long paidAmount,
    String paymentMethod,
    String reservationStatus,
    List<OrderItemInfo> items,
    PaymentSummary payment
) {
    public static OrderInfo from(OrderModel order) {
        return from(order, null);
    }

    public static OrderInfo from(OrderModel order, PaymentAttempt attempt) {
        return new OrderInfo(
            order.getId(),
            order.getUserId(),
            order.getStatus().name(),
            order.getTotalAmount(),
            order.getPaidAmount(),
            order.getPaymentMethod() == null ? null : order.getPaymentMethod().name(),
            order.getReservationStatus() == null ? null : order.getReservationStatus().name(),
            order.getItems().stream().map(OrderItemInfo::from).toList(),
            attempt == null ? null : PaymentSummary.from(attempt)
        );
    }

    /** 주문마다 결제 시도를 따로 조회하지 않도록, 미리 한 번에 읽은 시도를 주문에 붙인다. */
    static List<OrderInfo> from(List<OrderModel> orders, List<PaymentAttempt> attempts) {
        Map<Long, PaymentAttempt> byOrderId = attempts.stream()
            .collect(Collectors.toMap(PaymentAttempt::getOrderId, Function.identity()));
        return orders.stream().map(order -> from(order, byOrderId.get(order.getId()))).toList();
    }

    public record OrderItemInfo(Long productId, String productName, long unitPrice, int quantity) {
        private static OrderItemInfo from(OrderItem item) {
            return new OrderItemInfo(
                item.getProductId(), item.getProductName(), item.getUnitPrice(), item.getQuantity()
            );
        }
    }

    /** 결제 시도의 저장 상태. PG 식별자는 관리자 응답에만 내보낸다. */
    public record PaymentSummary(String status, String providerOrderId, String transactionKey) {
        private static PaymentSummary from(PaymentAttempt attempt) {
            return new PaymentSummary(
                attempt.getStatus().name(), attempt.getProviderOrderId(), attempt.getTransactionKey()
            );
        }
    }
}
