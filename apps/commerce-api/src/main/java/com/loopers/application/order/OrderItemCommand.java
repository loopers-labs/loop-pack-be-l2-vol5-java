package com.loopers.application.order;

/** FR-ORDER-01 입력 품목: 상품 ID 와 수량. 단가는 Facade 가 카탈로그에서 가져온다. */
public record OrderItemCommand(Long productId, Integer quantity) {
}
