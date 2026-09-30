package com.loopers.application.ordering.query;

import com.loopers.application.ordering.result.OrderItemResult;

// 주문 품목 응답 뷰
public record OrderItemView(long productId, String productName, long unitPrice, int quantity, long amount) {
    // 결과를 응답 뷰로 변환
    public static OrderItemView from(OrderItemResult result) {
        return new OrderItemView(result.productId(), result.productName(), result.unitPrice(), result.quantity(),
            result.amount());
    }
}
