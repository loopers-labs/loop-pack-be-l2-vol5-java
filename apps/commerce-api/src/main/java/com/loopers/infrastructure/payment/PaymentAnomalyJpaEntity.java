package com.loopers.infrastructure.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "payment_anomalies", uniqueConstraints =
    @UniqueConstraint(name = "uk_payment_anomalies_order_kind", columnNames = {"order_id", "kind"}))
public class PaymentAnomalyJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, updatable = false)
    private long orderId;
    @Column(nullable = false, updatable = false, length = 50)
    private String kind;
    @Column(nullable = false, updatable = false)
    private Instant firstSeenAt;
    @Column(nullable = false)
    private Instant lastSeenAt;
    @Column(nullable = false)
    private long occurrences;
    @Column(nullable = false, length = 500)
    private String lastSummary;
    private Instant resolvedAt;

    protected PaymentAnomalyJpaEntity() {
    }

    PaymentAnomalyJpaEntity(long orderId, String kind, Instant now) {
        this.orderId = orderId;
        this.kind = kind;
        this.firstSeenAt = now;
    }

    void observe(String summary, Instant now) {
        occurrences++;
        lastSeenAt = now;
        lastSummary = summary;
        resolvedAt = null;
    }

    void resolve(Instant now) {
        resolvedAt = now;
    }
}
