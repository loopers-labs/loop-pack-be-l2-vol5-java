package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * AG-06 포함 개념 주문 품목 (TB-07 order_item). OrderModel 안에서만 만들어진다 (설계 5-3).
 * 단가는 생성 시점 상품 가격의 사본 (ASM-10). 항목 금액은 저장하지 않고 계산한다 (DR-13).
 */
@Entity
@Table(name = "order_item",
    uniqueConstraints = @UniqueConstraint(name = "ux_order_item_order_product", columnNames = {"order_id", "product_id"}))
public class OrderItemModel extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private OrderModel order;

    /** 참조 개념 상품 (BC-02). ID 만. 상품이 DELETED 여도 유지 (ASM-22). */
    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    /** INV-07 > 0. 같은 상품은 합산된 값 (ASM-11). */
    @Column(name = "quantity", nullable = false, updatable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false, updatable = false)
    private Long unitPrice;

    protected OrderItemModel() {}

    OrderItemModel(OrderModel order, Long productId, Integer quantity, Long unitPrice) {
        this.order = order;
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    /** INV-06 항목 금액 = 단가 × 수량. 생성 시 범위 검사를 통과한 값이라 여기서는 넘치지 않는다. */
    public long lineAmount() {
        return unitPrice * quantity;
    }

    public Long getProductId() {
        return productId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public Long getUnitPrice() {
        return unitPrice;
    }
}
