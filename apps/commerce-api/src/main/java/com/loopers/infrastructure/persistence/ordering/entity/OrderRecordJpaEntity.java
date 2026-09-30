package com.loopers.infrastructure.persistence.ordering.entity;

import com.loopers.domain.ordering.model.OrderRecordStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "order_records", uniqueConstraints = @UniqueConstraint(
    name = "uk_order_records_order_id", columnNames = "order_id"
))
// 주문 기록 JPA 엔티티
public class OrderRecordJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, foreignKey = @ForeignKey(name = "fk_order_records_order_id"))
    private OrderJpaEntity order;
    @Column(name = "user_id", nullable = false)
    private long userId;
    @Column(nullable = false)
    private long amount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderRecordStatus status;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrderRecordJpaEntity() {}

    OrderRecordJpaEntity(long userId, long amount, OrderRecordStatus status) {
        this.userId = userId;
        this.amount = amount;
        this.status = status;
    }

    void assignOrder(OrderJpaEntity order) {
        this.order = order;
    }

    public Long getId() {
        return id;
    }

    public OrderJpaEntity getOrder() {
        return order;
    }

    public long getUserId() {
        return userId;
    }

    public long getAmount() {
        return amount;
    }

    public OrderRecordStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}
