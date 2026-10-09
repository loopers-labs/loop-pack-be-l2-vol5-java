package com.loopers.infrastructure.persistence.ordering.entity;

import com.loopers.domain.ordering.model.OrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
// 주문 JPA 엔티티
public class OrderJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private long userId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;
    @Column(name = "total_amount", nullable = false)
    private long totalAmount;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<OrderItemJpaEntity> items = new ArrayList<>();
    @OneToOne(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private OrderRecordJpaEntity record;

    protected OrderJpaEntity() {}

    OrderJpaEntity(long userId, OrderStatus status, long totalAmount) {
        this.userId = userId;
        this.status = status;
        this.totalAmount = totalAmount;
    }

    void addItem(OrderItemJpaEntity item) {
        items.add(item);
        item.assignOrder(this);
    }

    void assignRecord(OrderRecordJpaEntity record) {
        this.record = record;
        record.assignOrder(this);
    }

    void apply(OrderStatus status) {
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public long getTotalAmount() {
        return totalAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<OrderItemJpaEntity> getItems() {
        return items;
    }

    public OrderRecordJpaEntity getRecord() {
        return record;
    }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}
