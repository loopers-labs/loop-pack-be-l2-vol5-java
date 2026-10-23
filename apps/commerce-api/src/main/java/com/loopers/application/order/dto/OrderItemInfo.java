package com.loopers.application.order.dto;

import com.loopers.domain.order.OrderItem;

/** 주문 품목 조회 결과. 상품명과 단가는 주문 당시 값이다. */
public record OrderItemInfo(Long productId, String productName, int quantity, long unitPrice, long amount) {
    public static OrderItemInfo from(OrderItem item) {
        return new OrderItemInfo(
            item.getProductId(),
            item.getProductName(),
            item.getQuantity(),
            item.getUnitPrice().amount(),
            item.getAmount().amount()
        );
    }
}
