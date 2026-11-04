package com.loopers.infrastructure.persistence.ordering.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;

@Entity
@Getter
@Table(name = "order_confirmation_inbox", uniqueConstraints = {
    @UniqueConstraint(name = "uk_purchase_inbox_event_id", columnNames = "event_id"),
    @UniqueConstraint(name = "uk_purchase_inbox_order_id", columnNames = "order_id")
})
public class OrderConfirmationInboxJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", columnDefinition = "char(36)", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private long orderId;

    @Column(name = "event_type", length = 40, nullable = false, updatable = false)
    private String eventType;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private Instant confirmedAt;

    @Column(name = "normalized_payload", columnDefinition = "longtext", nullable = false, updatable = false)
    private String normalizedPayload;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    protected OrderConfirmationInboxJpaEntity() {}

    public OrderConfirmationInboxJpaEntity(String eventId, long orderId, String eventType, int schemaVersion,
                                           Instant confirmedAt, String normalizedPayload, Instant processedAt) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.confirmedAt = confirmedAt;
        this.normalizedPayload = normalizedPayload;
        this.processedAt = processedAt;
    }
}
