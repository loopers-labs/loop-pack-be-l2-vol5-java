package com.loopers.infrastructure.order;

import com.loopers.application.order.OutboxDeliveryStore;
import com.loopers.application.order.OutboxRetryPolicy;
import com.loopers.application.order.OrderOutboxMessage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "order_outbox", indexes = {
        @Index(name = "idx_order_outbox_pending", columnList = "status,next_attempt_at,created_at")
})
public class OrderOutboxEntity {
    @Id
    @Column(name = "event_id", nullable = false, updatable = false, length = 160)
    private String eventId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private long orderId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 80)
    private String eventType;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Column(name = "message_key", nullable = false, updatable = false, length = 80)
    private String messageKey;

    @Column(name = "destination_topic", nullable = false, updatable = false, length = 249)
    private String destinationTopic;

    @Lob
    @Column(nullable = false, updatable = false, columnDefinition = "longtext")
    private String payload;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private int totalAttemptCount;

    @Column(name = "cycle_attempt_count", nullable = false)
    private int attemptCountInCycle;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OrderOutboxEntity() {
    }

    OrderOutboxEntity(OrderOutboxMessage event, Instant now) {
        this.eventId = event.eventId();
        this.orderId = event.orderId();
        this.eventType = event.eventType();
        this.schemaVersion = event.schemaVersion();
        this.messageKey = event.messageKey();
        this.destinationTopic = event.destinationTopic();
        this.payload = event.payload();
        this.status = "PENDING";
        this.createdAt = now;
        this.nextAttemptAt = now;
    }

    Optional<OutboxDeliveryStore.DeliveryEvent> beginAttempt(Instant now, Instant retryAt) {
        if (!"PENDING".equals(status) || nextAttemptAt == null || nextAttemptAt.isAfter(now)
                || attemptCountInCycle >= OutboxRetryPolicy.MAX_ATTEMPTS) {
            return Optional.empty();
        }
        totalAttemptCount++;
        attemptCountInCycle++;
        nextAttemptAt = retryAt;
        return Optional.of(new OutboxDeliveryStore.DeliveryEvent(
                eventId, destinationTopic, messageKey, payload, attemptCountInCycle));
    }

    void markPublished(Instant now) {
        if (!"PENDING".equals(status)) {
            throw new IllegalStateException("Outbox event is not pending");
        }
        status = "PUBLISHED";
        publishedAt = now;
        nextAttemptAt = null;
        lastError = null;
    }

    void recordFailure(Instant retryAt, String error) {
        if (!"PENDING".equals(status)) {
            throw new IllegalStateException("Outbox event is not pending");
        }
        nextAttemptAt = retryAt;
        lastError = error;
        if (attemptCountInCycle >= OutboxRetryPolicy.MAX_ATTEMPTS) {
            status = "BLOCKED";
        }
    }

    String payload() {
        return payload;
    }

    boolean matchesMetadata(OrderOutboxMessage event) {
        return eventId.equals(event.eventId()) && orderId == event.orderId()
                && eventType.equals(event.eventType()) && schemaVersion == event.schemaVersion()
                && messageKey.equals(event.messageKey()) && destinationTopic.equals(event.destinationTopic());
    }
}
