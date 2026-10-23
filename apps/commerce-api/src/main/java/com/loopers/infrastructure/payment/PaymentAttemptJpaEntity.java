package com.loopers.infrastructure.payment;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentConflictReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "payment_attempts",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_attempts_key", columnNames = "payment_attempt_key"),
        @UniqueConstraint(name = "uk_payment_attempts_order_id", columnNames = "order_id"),
        @UniqueConstraint(name = "uk_payment_attempts_provider_order_id", columnNames = "provider_order_id")
    },
    indexes = @Index(name = "idx_payment_attempts_status_checked", columnList = "status, last_checked_at")
)
public class PaymentAttemptJpaEntity extends BaseEntity {

    @Column(name = "payment_attempt_key", nullable = false, length = 36)
    private String paymentAttemptKey;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "provider_order_id", nullable = false, length = 64)
    private String providerOrderId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentAttemptStatus status;

    @Column(name = "transaction_key")
    private String transactionKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_reason")
    private PaymentConflictReason conflictReason;

    @Column(name = "conflict_detected_at")
    private ZonedDateTime conflictDetectedAt;

    @Column(name = "last_checked_at")
    private ZonedDateTime lastCheckedAt;

    private PaymentAttemptJpaEntity(String paymentAttemptKey, Long orderId, String providerOrderId, long amount,
                                    PaymentAttemptStatus status, String transactionKey,
                                    PaymentConflictReason conflictReason, ZonedDateTime conflictDetectedAt) {
        this.paymentAttemptKey = paymentAttemptKey;
        this.orderId = orderId;
        this.providerOrderId = providerOrderId;
        this.amount = amount;
        this.status = status;
        this.transactionKey = transactionKey;
        this.conflictReason = conflictReason;
        this.conflictDetectedAt = conflictDetectedAt;
    }

    public static PaymentAttemptJpaEntity create(String paymentAttemptKey, Long orderId, String providerOrderId,
                                                 long amount, PaymentAttemptStatus status, String transactionKey,
                                                 PaymentConflictReason conflictReason,
                                                 ZonedDateTime conflictDetectedAt) {
        return new PaymentAttemptJpaEntity(
            paymentAttemptKey, orderId, providerOrderId, amount, status, transactionKey,
            conflictReason, conflictDetectedAt
        );
    }
}
