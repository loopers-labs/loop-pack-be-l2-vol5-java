package com.loopers.infrastructure.persistence.pay.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "payment_attempts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_attempt_order", columnNames = "order_id"),
        @UniqueConstraint(name = "uk_payment_attempt_transaction", columnNames = "transaction_key")
})
public class PaymentAttemptJpaEntity {
    @Id
    @Column(name = "attempt_key", nullable = false, length = 36)
    String attemptKey;
    @Column(name = "order_id", nullable = false)
    long orderId;
    @Column(name = "amount", nullable = false)
    long amount;
    @Column(name = "provider_order_id", nullable = false, length = 100)
    String providerOrderId;
    @Column(name = "dispatch_state", nullable = false, length = 16)
    String dispatchState;
    @Column(name = "outcome", nullable = false, length = 16)
    String outcome;
    @Column(name = "transaction_key", nullable = true, length = 64)
    String transactionKey;
    @Column(name = "rejection", nullable = false, length = 16)
    String rejection;
    @Column(name = "conflict_reason", nullable = false, length = 32)
    String conflict;
    @Column(name = "created_at", nullable = false)
    Instant createdAt;
    @Embedded
    PaymentScenarioFields scenario;
    @Version
    long version;

    protected PaymentAttemptJpaEntity() {}
}
