package com.loopers.purchase.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "product_purchase_activity")
public class ProductPurchaseActivity {

    @EmbeddedId
    private ProductPurchaseActivityId id;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "confirmed_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant confirmedAt;

    protected ProductPurchaseActivity() {
    }

    public ProductPurchaseActivity(Long orderId, Long productId, int quantity, Instant confirmedAt) {
        this.id = new ProductPurchaseActivityId(orderId, productId);
        this.quantity = quantity;
        this.confirmedAt = confirmedAt;
    }

    public ProductPurchaseActivityId getId() {
        return id;
    }

    public int getQuantity() {
        return quantity;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }
}
