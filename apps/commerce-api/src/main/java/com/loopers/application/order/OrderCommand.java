package com.loopers.application.order;

public class OrderCommand {
    /**
     * 주문 요청 품목. 단가는 주문 시점에 상품에서 가져오므로 입력에 포함하지 않는다.
     */
    public record Item(Long productId, int quantity) {}
}
