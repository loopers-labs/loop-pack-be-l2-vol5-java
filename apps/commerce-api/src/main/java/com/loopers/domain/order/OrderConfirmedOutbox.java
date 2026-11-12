package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;

@Entity
@Table(
    name = "order_confirmed_outbox",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_outbox_event_id", columnNames = "event_id")
    },
    indexes = {
        @Index(name = "idx_outbox_status_next_attempt", columnList = "status, next_attempt_at, created_at")
    }
)
public class OrderConfirmedOutbox extends BaseEntity {

    @Column(name = "event_id", nullable = false, length = 255)
    private String eventId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "message_key", nullable = false, length = 255)
    private String messageKey;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "published_at")
    private ZonedDateTime publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private ZonedDateTime nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    protected OrderConfirmedOutbox() {
    }

    public static OrderConfirmedOutbox create(
        String eventId,
        Long orderId,
        String messageKey,
        String payload
    ) {
        OrderConfirmedOutbox outbox = new OrderConfirmedOutbox();
        outbox.eventId = eventId;
        outbox.orderId = orderId;
        outbox.eventType = "ORDER_CONFIRMED";
        outbox.schemaVersion = 1;
        outbox.messageKey = messageKey;
        outbox.payload = payload;
        outbox.status = OutboxStatus.PENDING;
        outbox.attemptCount = 0;
        outbox.nextAttemptAt = ZonedDateTime.now();
        return outbox;
    }

    public void markPublished(ZonedDateTime publishedAt) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = publishedAt;
    }

    public void recordFailure(String errorMessage, ZonedDateTime nextAttemptAt) {
        this.attemptCount++;
        this.lastError = errorMessage != null && errorMessage.length() > 500
            ? errorMessage.substring(0, 500) : errorMessage;
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getEventId() { return eventId; }
    public Long getOrderId() { return orderId; }
    public String getMessageKey() { return messageKey; }
    public String getPayload() { return payload; }
    public OutboxStatus getStatus() { return status; }
    public ZonedDateTime getPublishedAt() { return publishedAt; }
    public int getAttemptCount() { return attemptCount; }
    public ZonedDateTime getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
}
