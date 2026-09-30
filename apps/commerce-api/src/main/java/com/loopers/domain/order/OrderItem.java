package com.loopers.domain.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Order aggregate에 속하는 VO — 독립된 identity가 없고, 만들어지면 바뀌지 않는 스냅샷이다.
 * 단가는 DRAFT 생성 시점의 Product.price를 복사해둔 값이라 이후 가격 변경과 무관하다.
 * (docs/week2/design.md 3번 섹션 "Order ── OrderItem, 재고·포인트 책임" 참고)
 */
@Embeddable
public class OrderItem {

    private Long productId;
    private int quantity;
    @Column(name = "unit_price")
    private Long unitPrice;

    protected OrderItem() {}

    public OrderItem(Long productId, int quantity, Long unitPrice) {
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public Long getUnitPrice() {
        return unitPrice;
    }
}
