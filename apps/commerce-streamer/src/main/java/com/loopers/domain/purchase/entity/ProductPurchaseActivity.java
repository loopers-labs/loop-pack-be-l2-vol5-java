package com.loopers.domain.purchase.entity;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

/**
 * 확정 주문에서 상품을 몇 개 샀는가. 9주차 랭킹의 입력.
 * productId 는 과거 사실의 식별자로 보존하며 현재 상품 행의 존재 · 가격에 의존하지 않음 (6주차 설계 4.1)
 */
@Getter
@Entity
@Table(name = "product_purchase_activity", uniqueConstraints = {
    // order_id 로 시작해 주문별 조회(재전달 판정 · 랭킹 join)에도 쓰임
    @UniqueConstraint(name = "uk_product_purchase_activity_order_product", columnNames = {"order_id", "product_id"})
})
public class ProductPurchaseActivity extends BaseEntity {

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    protected ProductPurchaseActivity() {}

    public ProductPurchaseActivity(Long orderId, Long productId, int quantity) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
    }
}
