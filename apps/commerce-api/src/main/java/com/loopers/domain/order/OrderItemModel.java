package com.loopers.domain.order;

import com.loopers.domain.common.Money;
import com.loopers.domain.common.MoneyConverter;
import com.loopers.domain.product.ProductSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Comment;

/**
 * 주문 당시 상품명·단가(snapshot)와 수량 (ORD-01). Order에 속하며 따로 바뀌지 않는다.
 * 상품은 식별자만 안다 — 상품 가격이 바뀌어도 이 품목은 바뀌지 않는다.
 */
@Entity
@Table(name = "order_items")
public class OrderItemModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    @Comment("상품 식별자 (ADR-01)")
    private Long productId;

    @Column(name = "product_name", nullable = false)
    @Comment("주문 당시 상품명 snapshot (ORD-01)")
    private String productName;

    @Convert(converter = MoneyConverter.class)
    @Column(name = "unit_price", nullable = false)
    @Comment("주문 당시 단가 snapshot (ORD-01)")
    private Money unitPrice;

    @Column(name = "quantity", nullable = false)
    @Comment("주문 수량, 같은 상품은 합산 (P-01)")
    private int quantity;

    protected OrderItemModel() {}

    OrderItemModel(ProductSnapshot snapshot, int quantity) {
        this.productId = snapshot.productId();
        this.productName = snapshot.name();
        this.unitPrice = snapshot.price();
        this.quantity = quantity;
    }

    /**
     * 품목 금액 = 단가 × 수량.
     */
    public Money amount() {
        return unitPrice.times(quantity);
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public Money getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }
}
