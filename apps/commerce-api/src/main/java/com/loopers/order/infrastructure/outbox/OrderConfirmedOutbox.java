package com.loopers.order.infrastructure.outbox;

import com.loopers.order.application.outbox.PublishResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;

@Entity
@Getter
@Table(
    name = "order_confirmed_outbox",
    uniqueConstraints = @UniqueConstraint(name = "uk_order_confirmed_outbox_event_id", columnNames = "event_id")
)
public class OrderConfirmedOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 150)
    private String eventId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "message_key", nullable = false, length = 255)
    private String messageKey;

    @Column(name = "payload", nullable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderConfirmedOutboxStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 255)
    private String lastError;

    protected OrderConfirmedOutbox() {
    }

    private OrderConfirmedOutbox(
        String eventId,
        Long orderId,
        String eventType,
        int schemaVersion,
        String messageKey,
        String payload,
        Instant createdAt
    ) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.messageKey = messageKey;
        this.payload = payload;
        this.status = OrderConfirmedOutboxStatus.PENDING;
        this.createdAt = createdAt;
        this.nextAttemptAt = createdAt;
    }

    public static OrderConfirmedOutbox pending(
        String eventId,
        Long orderId,
        String eventType,
        int schemaVersion,
        String messageKey,
        String payload,
        Instant createdAt
    ) {
        return new OrderConfirmedOutbox(
            eventId,
            orderId,
            eventType,
            schemaVersion,
            messageKey,
            payload,
            createdAt
        );
    }

    @PrePersist
    void initializeTimestamps() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (nextAttemptAt == null) {
            nextAttemptAt = createdAt;
        }
    }

    void attemptStarted() {
        attemptCount++;
    }

    void recordResult(PublishResult result, Instant completedAt, Duration retryDelay, int maxAttempts) {
        lastError = result.errorType();
        if (result.status() == PublishResult.Status.SUCCESS) {
            status = OrderConfirmedOutboxStatus.PUBLISHED;
            publishedAt = completedAt;
            return;
        }

        publishedAt = null;
        if (attemptCount >= maxAttempts) {
            status = OrderConfirmedOutboxStatus.BLOCKED;
        } else {
            status = result.status() == PublishResult.Status.FAILED
                ? OrderConfirmedOutboxStatus.FAILED
                : OrderConfirmedOutboxStatus.PENDING;
            nextAttemptAt = completedAt.plus(retryDelay);
        }
    }

    void setStatus(OrderConfirmedOutboxStatus status) {
        this.status = status;
    }

    void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }
}
