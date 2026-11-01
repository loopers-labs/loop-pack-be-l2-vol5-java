package com.loopers.domain.purchase;

/** 한 주문에서 구매한 상품과 수량. */
public record PurchaseLine(Long productId, int quantity) {

    public PurchaseLine {
        if (productId == null) {
            throw new IllegalArgumentException("상품 ID는 필수입니다.");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("구매 수량은 1 이상이어야 합니다.");
        }
    }
}
