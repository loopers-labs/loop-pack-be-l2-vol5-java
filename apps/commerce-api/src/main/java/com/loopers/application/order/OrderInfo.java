package com.loopers.application.order;

import com.loopers.domain.common.Money;
import com.loopers.domain.order.OrderItemModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * 주문 상세 결과: 품목·수량·금액·상태·결제 결과 (7-1·7-2).
 */
public record OrderInfo(
    Long id,
    Long userId,
    OrderStatus status,
    List<Item> items,
    long totalAmount,
    Long paidAmount,
    PaymentMethod paymentMethod,
    ZonedDateTime confirmedAt,
    ZonedDateTime createdAt
) {
    public record Item(Long productId, String productName, long unitPrice, int quantity, long amount) {
        static Item from(OrderItemModel item) {
            return new Item(
                item.getProductId(),
                item.getProductName(),
                item.getUnitPrice().amount(),
                item.getQuantity(),
                item.amount().amount()
            );
        }
    }

    public static OrderInfo from(OrderModel order) {
        return new OrderInfo(
            order.getId(),
            order.getUserId(),
            order.getStatus(),
            order.getItems().stream().map(Item::from).toList(),
            order.getTotalAmount().amount(),
            amountOrNull(order.getPaidAmount()),
            order.getPaymentMethod(),
            order.getConfirmedAt(),
            order.getCreatedAt()
        );
    }

    static Long amountOrNull(Money money) {
        return money == null ? null : money.amount();
    }
}
