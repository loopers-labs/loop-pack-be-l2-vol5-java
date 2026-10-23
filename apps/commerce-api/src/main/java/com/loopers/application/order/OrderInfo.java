package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.payment.entity.Payment;

import java.time.ZonedDateTime;
import java.util.List;

/** paymentUnavailable 은 이번 요청의 PG 조회 실패를 뜻하며, 저장된 상태는 그대로임. paymentConflict 는 관리자 응답에만 담음 */
public record OrderInfo(
    Long id,
    Long userId,
    String status,
    long totalAmount,
    Long paymentAmount,
    ZonedDateTime paidAt,
    ZonedDateTime orderedAt,
    List<Item> items,
    String paymentMethod,
    String paymentStatus,
    boolean paymentInProgress,
    boolean paymentUnavailable,
    String paymentConflict
) {
    public record Item(Long productId, String productName, long unitPrice, int quantity, long amount) {
        static Item from(OrderItem item) {
            return new Item(item.getProductId(), item.getProductName(), item.getUnitPrice(), item.getQuantity(), item.getAmount());
        }
    }

    public static OrderInfo from(Order order) {
        return from(order, null);
    }

    public static OrderInfo from(Order order, Payment payment) {
        return new OrderInfo(
            order.getId(),
            order.getUserId(),
            order.getStatus().name(),
            order.getTotalAmount(),
            order.getPaymentAmount(),
            order.getPaidAt(),
            order.getCreatedAt(),
            order.getItems().stream().map(Item::from).toList(),
            order.getPaymentMethod() == null ? null : order.getPaymentMethod().name(),
            payment == null ? null : payment.getStatus().name(),
            payment != null && !payment.getStatus().isTerminal(),
            false,
            payment == null || payment.getConflictReason() == null ? null : payment.getConflictReason().name()
        );
    }

    /** 조회 실패 시 결과를 지어내지 않고 저장된 상태를 돌려줌 */
    public OrderInfo withPaymentUnavailable() {
        return new OrderInfo(id, userId, status, totalAmount, paymentAmount, paidAt, orderedAt, items,
            paymentMethod, paymentStatus, paymentInProgress, true, paymentConflict);
    }
}
