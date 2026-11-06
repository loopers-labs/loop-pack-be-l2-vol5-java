package com.loopers.domain.activity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;

@Entity
@Table(name = "product_purchase_activity", uniqueConstraints = {
    @UniqueConstraint(name = "uq_ppa_order_product", columnNames = {"order_id", "product_id"})
})
public class ProductPurchaseActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "confirmed_at", nullable = false)
    private ZonedDateTime confirmedAt;

    protected ProductPurchaseActivity() {
    }

    public ProductPurchaseActivity(Long orderId, Long productId, int quantity, ZonedDateTime confirmedAt) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.confirmedAt = confirmedAt;
    }

    public Long getOrderId() { return orderId; }
    public Long getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public ZonedDateTime getConfirmedAt() { return confirmedAt; }
}
