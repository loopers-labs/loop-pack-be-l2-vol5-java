package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;

/** 주문 확정과 같은 TX에 남기는 전달 대상. 사건 식별·key·payload는 생성 뒤 바뀌지 않는다. */
@Getter
@Entity
@Table(
    name = "order_outbox",
    uniqueConstraints = @UniqueConstraint(name = "uk_order_outbox_event", columnNames = "event_id"),
    indexes = @Index(name = "idx_order_outbox_due", columnList = "status, next_attempt_at, id")
)
public class OrderOutbox extends BaseEntity {
    private static final int MAX_ERROR_LENGTH = 255;
    private static final int MAX_DOUBLINGS = 20;

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;
    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;
    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;
    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;
    @Column(name = "message_key", nullable = false, updatable = false)
    private String messageKey;
    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderOutboxStatus status;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;
    @Column(name = "last_error")
    private String lastError;
    @Column(name = "published_at")
    private Instant publishedAt;

    protected OrderOutbox() {}

    private OrderOutbox(OrderConfirmed event, String eventType, int schemaVersion, String payload) {
        this.eventId = event.eventId();
        this.orderId = event.orderId();
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.messageKey = event.orderId().toString();
        this.payload = payload;
        this.status = OrderOutboxStatus.PENDING;
        this.nextAttemptAt = event.confirmedAt();
    }

    public static OrderOutbox pending(OrderConfirmed event, String eventType, int schemaVersion, String payload) {
        return new OrderOutbox(event, eventType, schemaVersion, payload);
    }

    public void startAttempt() {
        attemptCount++;
    }

    /** broker 성공을 확인한 뒤에만 호출한다. */
    public void markPublished(Instant publishedAt) {
        this.status = OrderOutboxStatus.PUBLISHED;
        this.publishedAt = publishedAt;
        this.lastError = null;
    }

    public void scheduleRetry(Instant now, String error, Duration firstDelay, Duration maxDelay) {
        Duration delay = firstDelay.multipliedBy(1L << Math.min(attemptCount - 1, MAX_DOUBLINGS));
        this.nextAttemptAt = now.plus(delay.compareTo(maxDelay) > 0 ? maxDelay : delay);
        this.lastError = error != null && error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
    }
}
