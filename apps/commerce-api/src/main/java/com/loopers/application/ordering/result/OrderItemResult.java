package com.loopers.application.ordering.result;

import com.loopers.domain.ordering.model.OrderItem;

// 주문 품목 결과
public record OrderItemResult(long productId, String productName, long unitPrice, int quantity, long amount) {
    // 도메인 객체를 결과로 변환
    public static OrderItemResult from(OrderItem item) {
        return new OrderItemResult(item.getProductId(), item.getProductName(), item.getUnitPrice(),
            item.getQuantity(), item.getAmount());
    }
}
