package com.loopers.infrastructure.persistence.mall.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "stock_adjustments")
public class StockAdjustmentJpaEntity {
    @Id
    @Column(name = "order_id")
    Long orderId;
    @Column(nullable = false, length = 16)
    String state;
    @Version
    long version;
    @Column(name = "created_at", nullable = false)
    Instant createdAt;
    @OneToMany(mappedBy = "adjustment", cascade = CascadeType.ALL)
    @OrderBy("productId ASC")
    List<StockAdjustmentItemJpaEntity> items = new ArrayList<>();

    protected StockAdjustmentJpaEntity() {}
}
