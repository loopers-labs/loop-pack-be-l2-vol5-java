package com.loopers.infrastructure.pay.wallet;

import com.loopers.domain.pay.wallet.PointBillType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "point_bills")
// 포인트 기록 JPA 엔티티
public class PointBillJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private long userId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PointBillType type;
    @Column(nullable = false)
    private long amount;
    @Column(name = "order_id")
    private Long orderId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PointBillJpaEntity() {}

    PointBillJpaEntity(long userId, PointBillType type, long amount, Long orderId) {
        this.userId = userId;
        this.type = type;
        this.amount = amount;
        this.orderId = orderId;
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public PointBillType getType() {
        return type;
    }

    public long getAmount() {
        return amount;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}
