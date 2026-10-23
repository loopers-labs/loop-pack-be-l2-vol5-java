package com.loopers.infrastructure.persistence.pay.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "payment_recovery_tasks")
public class PaymentRecoveryTaskJpaEntity {
    @Id
    @Column(name = "attempt_key", length = 36)
    String attemptKey;
    @Column(nullable = false, length = 24)
    String state;
    @Column(name = "execution_id", length = 36)
    String executionId;
    @Column(name = "used_rounds", nullable = false)
    int usedRounds;
    @Column(name = "last_checked_at")
    Instant lastCheckedAt;
    @Column(name = "last_reason", nullable = false, length = 32)
    String lastReason;
    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    protected PaymentRecoveryTaskJpaEntity() {}
}
