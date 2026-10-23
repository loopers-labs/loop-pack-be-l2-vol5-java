package com.loopers.infrastructure.payment;

import com.loopers.domain.AuditEntity;
import com.loopers.domain.common.Money;
import com.loopers.domain.payment.CardType;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentAttemptKey;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.payment.ProviderOrderId;
import com.loopers.domain.payment.TransactionKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
    name = "payment",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_order_id", columnNames = "order_id"),
        @UniqueConstraint(name = "uk_payment_attempt_key", columnNames = "attempt_key"),
        @UniqueConstraint(name = "uk_payment_provider_order_id", columnNames = "provider_order_id")
    },
    indexes = @Index(name = "idx_payment_status_requested_at", columnList = "status, requested_at")
)
class PaymentEntity extends AuditEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "attempt_key", nullable = false, updatable = false, length = 64)
    private String attemptKey;

    @Column(name = "provider_order_id", nullable = false, updatable = false, length = 32)
    private String providerOrderId;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "card_type", nullable = false, updatable = false, length = 20)
    private CardType cardType;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "transaction_key", length = 64)
    private String transactionKey;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    protected PaymentEntity() {}

    static PaymentEntity from(Payment payment) {
        PaymentEntity entity = new PaymentEntity();
        entity.orderId = payment.getOrderId();
        entity.attemptKey = payment.getAttemptKey().value();
        entity.providerOrderId = payment.getProviderOrderId().value();
        entity.amount = payment.getAmount().amount();
        entity.cardType = payment.getCardType();
        entity.requestedAt = payment.getRequestedAt();
        entity.apply(payment);
        return entity;
    }

    void apply(Payment payment) {
        this.status = payment.getStatus();
        this.transactionKey = payment.getTransactionKey() != null ? payment.getTransactionKey().value() : null;
        this.lastCheckedAt = payment.getLastCheckedAt();
    }

    Payment toDomain() {
        return Payment.restore(
            getId(), orderId, PaymentAttemptKey.of(attemptKey), new ProviderOrderId(providerOrderId),
            Money.of(amount), cardType, requestedAt, status,
            transactionKey != null ? new TransactionKey(transactionKey) : null, lastCheckedAt);
    }
}
