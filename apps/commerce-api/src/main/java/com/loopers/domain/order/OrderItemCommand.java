package com.loopers.domain.order;

/**
 * 주문 생성 요청의 품목. 단가는 요청값을 믿지 않고 주문 시점의 Product 가격을 사용한다.
 */
public record OrderItemCommand(Long productId, long quantity) {
}
