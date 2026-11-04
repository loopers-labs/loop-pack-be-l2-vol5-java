package com.loopers.domain.purchase;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.Instant;

@Getter
@Entity
@Table(name = "product_purchase_activity", uniqueConstraints = {
    @UniqueConstraint(name = "uk_purchase_order_product", columnNames = {"order_id", "product_id"})
})
public class ProductPurchaseActivity extends BaseEntity {
    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "confirmed_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private Instant confirmedAt;

    protected ProductPurchaseActivity() {}

    public ProductPurchaseActivity(Long orderId, Long productId, int quantity, Instant confirmedAt) {
        if (orderId == null || orderId <= 0 || productId == null || productId <= 0
            || quantity <= 0 || confirmedAt == null) {
            throw new IllegalArgumentException("상품 구매 활동이 올바르지 않습니다.");
        }
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.confirmedAt = confirmedAt;
    }
}
