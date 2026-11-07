package com.loopers.infrastructure.order;

import com.loopers.domain.AuditEntity;
import com.loopers.domain.order.PendingOrderEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Arrays;

@Entity
@Table(
    name = "order_event_outbox",
    uniqueConstraints = @UniqueConstraint(name = "uk_outbox_event_id", columnNames = "event_id"),
    indexes = @Index(name = "idx_outbox_due", columnList = "status, next_attempt_at, id")
)
class OrderEventOutboxEntity extends AuditEntity {

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 50)
    private String eventType;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Column(name = "topic", nullable = false, updatable = false)
    private String topic;

    @Column(name = "record_key", nullable = false, updatable = false)
    private String recordKey;

    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderEventOutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", insertable = false, updatable = false,
        columnDefinition = "DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6))")
    private Instant nextAttemptAt;

    @Column(name = "retry_since", insertable = false, updatable = false,
        columnDefinition = "DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6))")
    private Instant retrySince;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "published_at", insertable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private Instant publishedAt;

    protected OrderEventOutboxEntity() {}

    static OrderEventOutboxEntity pending(OrderConfirmedMessage message, String topic, byte[] payload) {
        OrderEventOutboxEntity entity = new OrderEventOutboxEntity();
        entity.eventId = message.eventId();
        entity.orderId = message.orderId();
        entity.eventType = message.eventType();
        entity.schemaVersion = message.schemaVersion();
        entity.topic = topic;
        entity.recordKey = String.valueOf(message.orderId());
        entity.payload = payload;
        entity.status = OrderEventOutboxStatus.PENDING;
        entity.attemptCount = 0;
        return entity;
    }

    PendingOrderEvent toPending() {
        return new PendingOrderEvent(getId(), eventId, topic, recordKey, payload, attemptCount);
    }

    String eventId() {
        return eventId;
    }

    boolean sameRecordAs(OrderEventOutboxEntity other) {
        return eventType.equals(other.eventType) && schemaVersion == other.schemaVersion
            && recordKey.equals(other.recordKey) && Arrays.equals(payload, other.payload);
    }
}
