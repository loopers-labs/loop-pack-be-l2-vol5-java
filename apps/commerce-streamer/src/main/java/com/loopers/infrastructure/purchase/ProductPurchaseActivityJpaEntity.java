package com.loopers.infrastructure.purchase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "product_purchase_activity", uniqueConstraints =
    @UniqueConstraint(name = "uk_product_purchase_activity_order_product", columnNames = {"order_id", "product_id"}))
public class ProductPurchaseActivityJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private long orderId;

    @Column(nullable = false, updatable = false)
    private long productId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(nullable = false, updatable = false)
    private Instant confirmedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected ProductPurchaseActivityJpaEntity() {
    }

    ProductPurchaseActivityJpaEntity(long orderId, long productId, int quantity, Instant confirmedAt, Instant createdAt) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.confirmedAt = confirmedAt;
        this.createdAt = createdAt;
    }
}
