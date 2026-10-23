package com.loopers.infrastructure.payment;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "payments", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payments_order", columnNames = "order_id"),
        @UniqueConstraint(name = "uk_payments_attempt", columnNames = "payment_attempt_key"),
        @UniqueConstraint(name = "uk_payments_provider_order", columnNames = "provider_order_id"),
        @UniqueConstraint(name = "uk_payments_transaction", columnNames = "transaction_key")
})
public class PaymentJpaEntity extends BaseEntity {
    @Column(nullable = false, updatable = false)
    private long orderId;
    @Column(nullable = false, updatable = false, length = 36)
    private String paymentAttemptKey;
    @Column(nullable = false, updatable = false, length = 64)
    private String providerOrderId;
    @Column(nullable = false, updatable = false)
    private long amount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;
    private String transactionKey;
    @Column(nullable = false, updatable = false)
    private ZonedDateTime pgRequestStartedAt;

    protected PaymentJpaEntity() {
    }

    public PaymentJpaEntity(Payment payment) {
        orderId = payment.orderId();
        paymentAttemptKey = payment.paymentAttemptKey().toString();
        providerOrderId = payment.providerOrderId();
        amount = payment.amount();
        pgRequestStartedAt = payment.pgRequestStartedAt();
        update(payment);
    }

    public void update(Payment payment) {
        status = payment.status();
        transactionKey = payment.transactionKey();
    }

    public Payment toDomain() {
        return new Payment(orderId, UUID.fromString(paymentAttemptKey), providerOrderId,
                amount, status, transactionKey, pgRequestStartedAt);
    }
}
