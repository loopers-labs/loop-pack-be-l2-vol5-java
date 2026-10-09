package com.loopers.infrastructure.query.ordering;

import com.loopers.application.ordering.query.OrderItemView;

// 주문 품목 조회 결과 로우
public record OrderItemRow(long orderId, long productId, String productName, long unitPrice, int quantity, long amount) {
    // 응답 뷰로 변환
    OrderItemView toView() {
        return new OrderItemView(productId, productName, unitPrice, quantity, amount);
    }
}
