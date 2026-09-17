package com.loopers.application.order;

import com.loopers.domain.common.Money;
import com.loopers.domain.order.OrderItemModel;
import com.loopers.domain.order.OrderModel;

import java.util.List;

/**
 * 트랜잭션 안에서 구성한 주문 결과. Controller 가 지연 로딩에 의존하지 않도록 값으로 복사한다.
 */
public record OrderInfo(
    Long id,
    Long userId,
    String status,
    Long orderTotal,
    Long usedPointAmount,
    Long paymentAmount,
    List<Item> items
) {
    public record Item(Long productId, Long quantity, Long unitPrice, Long amount) {
        public static Item from(OrderItemModel item) {
            return new Item(
                item.getProductId(),
                item.getQuantity(),
                item.getUnitPrice().toWon(),
                item.calculateAmount().toWon()
            );
        }
    }

    public static OrderInfo from(OrderModel order) {
        Money paymentAmount = order.getPaymentAmount();
        return new OrderInfo(
            order.getId(),
            order.getUserId(),
            order.getStatus().name(),
            order.getOrderTotal().toWon(),
            order.getUsedPointAmount(),
            paymentAmount != null ? paymentAmount.toWon() : null,
            order.getItems().stream().map(Item::from).toList()
        );
    }
}
