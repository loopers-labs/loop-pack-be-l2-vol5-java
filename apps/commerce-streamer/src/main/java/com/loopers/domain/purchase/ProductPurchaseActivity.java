package com.loopers.domain.purchase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;

/** Immutable historical identifiers; no relationship to the API's current product/order entities. */
@Entity
@Table(name = "product_purchase_activity", uniqueConstraints = {
    @UniqueConstraint(name = "uk_purchase_activity_order_product", columnNames = {"order_id", "product_id"})
})
public class ProductPurchaseActivity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private long orderId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private long productId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "confirmed_at_second", nullable = false, updatable = false)
    private long confirmedAtSecond;

    @Column(name = "confirmed_at_nano", nullable = false, updatable = false)
    private int confirmedAtNano;

    protected ProductPurchaseActivity() {
    }

    public ProductPurchaseActivity(long orderId, Instant confirmedAt, OrderConfirmation.Item item) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("A purchase requires a positive order identity");
        }
        Objects.requireNonNull(confirmedAt, "confirmedAt");
        Objects.requireNonNull(item, "item");
        this.orderId = orderId;
        productId = item.productId();
        quantity = item.quantity();
        confirmedAtSecond = confirmedAt.getEpochSecond();
        confirmedAtNano = confirmedAt.getNano();
    }

    public long orderId() {
        return orderId;
    }

    public Instant confirmedAt() {
        return Instant.ofEpochSecond(confirmedAtSecond, confirmedAtNano);
    }

    public OrderConfirmation.Item item() {
        return new OrderConfirmation.Item(productId, quantity);
    }
}
