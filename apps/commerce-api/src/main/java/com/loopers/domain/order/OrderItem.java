package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 주문 품목. 상품은 식별자로만 보관하고, 상품명 · 단가는 DRAFT 생성 시점의 스냅샷이다 (설계 2.3).
 * 관리자가 상품을 바꾸거나 삭제해도 주문 품목은 그대로 남는다.
 */
@Entity
@Table(name = "order_items")
public class OrderItem extends BaseEntity {

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    @Column(nullable = false)
    private int quantity;

    protected OrderItem() {}

    OrderItem(Long productId, String productName, long unitPrice, int quantity) {
        this.productId = productId;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }

    public Long getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public long getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getAmount() {
        return unitPrice * quantity;
    }

    /** 확정 시 스냅샷 단가와 현재 가격이 같아야 한다. 가격이 내려간 경우도 다르다고 본다 (ORD-09, 설계 2.3). */
    public boolean isPriceMatched(long currentPrice) {
        return unitPrice == currentPrice;
    }
}
