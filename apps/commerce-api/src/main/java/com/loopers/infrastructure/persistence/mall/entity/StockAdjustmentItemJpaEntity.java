package com.loopers.infrastructure.persistence.mall.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "stock_adjustment_items", uniqueConstraints = @UniqueConstraint(
        name = "uk_stock_adjustment_product", columnNames = {"order_id", "product_id"}))
public class StockAdjustmentItemJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, foreignKey = @ForeignKey(name = "fk_stock_item_adjustment"))
    StockAdjustmentJpaEntity adjustment;
    @Column(name = "product_id", nullable = false)
    long productId;
    @Column(nullable = false)
    int quantity;

    protected StockAdjustmentItemJpaEntity() {}

    StockAdjustmentItemJpaEntity(StockAdjustmentJpaEntity adjustment, long productId, int quantity) {
        this.adjustment = adjustment;
        this.productId = productId;
        this.quantity = quantity;
    }
}
