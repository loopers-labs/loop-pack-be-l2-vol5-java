package com.loopers.infrastructure.payment;

import com.loopers.domain.AuditEntity;
import com.loopers.domain.payment.PaymentDiscrepancy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
    name = "payment_discrepancy",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_payment_discrepancy_observation",
        columnNames = {"payment_id", "reason", "observed_transaction_key", "observed_status"}
    )
)
class PaymentDiscrepancyEntity extends AuditEntity {

    static final String NONE = "";

    @Column(name = "payment_id", nullable = false, updatable = false)
    private Long paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false, length = 30)
    private PaymentDiscrepancy.Reason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 20)
    private PaymentDiscrepancy.Source source;

    @Column(name = "observed_transaction_key", nullable = false, updatable = false, length = 64)
    private String observedTransactionKey;

    @Column(name = "observed_status", nullable = false, updatable = false, length = 20)
    private String observedStatus;

    @Column(name = "observed_amount", updatable = false)
    private Long observedAmount;

    @Column(name = "observed_at", nullable = false, updatable = false)
    private Instant observedAt;

    protected PaymentDiscrepancyEntity() {}

    static PaymentDiscrepancyEntity of(Long paymentId, PaymentDiscrepancy discrepancy, PaymentDiscrepancy.Source source,
                                       Instant observedAt) {
        PaymentDiscrepancyEntity entity = new PaymentDiscrepancyEntity();
        entity.paymentId = paymentId;
        entity.reason = discrepancy.reason();
        entity.source = source;
        entity.observedTransactionKey = keyOf(discrepancy);
        entity.observedStatus = statusOf(discrepancy);
        entity.observedAmount = discrepancy.observedAmount() != null ? discrepancy.observedAmount().amount() : null;
        entity.observedAt = observedAt;
        return entity;
    }

    static String keyOf(PaymentDiscrepancy discrepancy) {
        return discrepancy.observedKey() != null ? discrepancy.observedKey().value() : NONE;
    }

    static String statusOf(PaymentDiscrepancy discrepancy) {
        return discrepancy.observedStatus() != null ? discrepancy.observedStatus().name() : NONE;
    }
}
