package com.loopers.infrastructure.point;

import com.loopers.domain.AuditEntity;
import com.loopers.domain.common.Money;
import com.loopers.domain.point.PointTransaction;
import com.loopers.domain.point.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(
    name = "point_transaction",
    indexes = @Index(name = "idx_point_transaction_user_id", columnList = "user_id, id")
)
class PointTransactionEntity extends AuditEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 20)
    private TransactionType type;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "balance_after", nullable = false, updatable = false)
    private long balanceAfter;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected PointTransactionEntity() {}

    static PointTransactionEntity from(Long userId, PointTransaction transaction) {
        PointTransactionEntity entity = new PointTransactionEntity();
        entity.userId = userId;
        entity.type = transaction.type();
        entity.amount = transaction.amount().amount();
        entity.balanceAfter = transaction.balanceAfter().amount();
        entity.occurredAt = transaction.occurredAt();
        return entity;
    }

    PointTransaction toDomain() {
        return new PointTransaction(type, Money.of(amount), Money.of(balanceAfter), occurredAt);
    }
}
