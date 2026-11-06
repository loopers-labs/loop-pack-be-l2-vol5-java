package com.loopers.domain.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** A saved immutable message and its separately tracked delivery state. No transport work occurs here. */
@Entity
@Table(name = "outbox_message",
    uniqueConstraints = @UniqueConstraint(name = "uq_outbox_event_id", columnNames = "event_id"),
    indexes = @Index(name = "idx_outbox_due", columnList = "status,next_attempt_at,id"))
public class OutboxMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false, length = 255)
    private String eventId;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false, length = 128)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Column(name = "topic", nullable = false, updatable = false, length = 249)
    private String topic;

    @Column(name = "message_key", nullable = false, updatable = false, length = 255)
    private String messageKey;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OutboxStatus status;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    @Column(name = "published_at", columnDefinition = "DATETIME(6)")
    private Instant publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    protected OutboxMessage() {
    }

    public static OutboxMessage pending(String eventId, String aggregateType, String aggregateId, String eventType,
                                        int schemaVersion, String topic, String messageKey, String payload, Instant createdAt) {
        OutboxMessage message = new OutboxMessage();
        message.eventId = requiredText(eventId, 255, "eventId");
        message.aggregateType = requiredText(aggregateType, 100, "aggregateType");
        message.aggregateId = requiredText(aggregateId, 128, "aggregateId");
        message.eventType = requiredText(eventType, 100, "eventType");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("Outbox schemaVersion must be positive");
        }
        message.schemaVersion = schemaVersion;
        message.topic = requiredText(topic, 249, "topic");
        message.messageKey = requiredText(messageKey, 255, "messageKey");
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("Outbox payload is required");
        }
        message.payload = payload;
        if (createdAt == null) {
            throw new IllegalArgumentException("Outbox createdAt is required");
        }
        message.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        message.status = OutboxStatus.PENDING;
        message.nextAttemptAt = message.createdAt;
        message.attemptCount = 0;
        return message;
    }

    private static String requiredText(String value, int maximumLength, String field) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maximumLength) {
            throw new IllegalArgumentException("Invalid Outbox " + field);
        }
        return value;
    }

    /** Eligibility is a retry boundary, not evidence that the broker has never received this event. */
    public boolean isDue(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("Outbox observation time is required");
        }
        return status == OutboxStatus.PENDING && !nextAttemptAt.isAfter(now);
    }

    /** Commit a start before transport; retain a recovery time even if the process stops after this write. */
    public int beginAttempt(Instant now, Instant retryNotBefore) {
        if (!isDue(now)) {
            throw new IllegalStateException("Outbox message is not eligible for another attempt");
        }
        if (retryNotBefore == null || !retryNotBefore.truncatedTo(ChronoUnit.MICROS)
            .isAfter(now.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("Outbox recovery time must follow the attempt start");
        }
        int nextCount = Math.addExact(attemptCount, 1);
        nextAttemptAt = retryNotBefore.truncatedTo(ChronoUnit.MICROS);
        attemptCount = nextCount;
        return attemptCount;
    }

    public boolean markPublished(int attempt, Instant at) {
        if (!isCurrentPendingAttempt(attempt)) {
            return false;
        }
        if (at == null) {
            throw new IllegalArgumentException("Outbox publication time is required");
        }
        publishedAt = at.truncatedTo(ChronoUnit.MICROS);
        status = OutboxStatus.PUBLISHED;
        lastError = null;
        return true;
    }

    public boolean markFailed(int attempt, Instant retryAt, String errorCode) {
        if (!isCurrentPendingAttempt(attempt)) {
            return false;
        }
        if (retryAt == null || errorCode == null || !errorCode.matches("[A-Z][A-Z0-9_]{0,99}")) {
            throw new IllegalArgumentException("Outbox failure requires a retry time and safe error code");
        }
        nextAttemptAt = retryAt.truncatedTo(ChronoUnit.MICROS);
        lastError = errorCode;
        return true;
    }

    private boolean isCurrentPendingAttempt(int attempt) {
        return status == OutboxStatus.PENDING && attempt > 0 && attemptCount == attempt;
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }
}
