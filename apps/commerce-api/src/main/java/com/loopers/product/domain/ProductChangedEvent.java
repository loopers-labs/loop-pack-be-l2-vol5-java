package com.loopers.product.domain;

/**
 * 고객 목록에 보이는 상품 정보(노출·이름·가격)가 바뀌었다는 사실 (W4). 재고 변경은 목록에 보이지 않아 발행하지 않는다.
 */
public record ProductChangedEvent(Long productId) {
}
