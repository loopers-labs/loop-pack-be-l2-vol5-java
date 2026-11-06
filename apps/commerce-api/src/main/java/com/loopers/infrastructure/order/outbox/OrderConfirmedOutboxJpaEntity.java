package com.loopers.infrastructure.order.outbox;

import com.loopers.application.order.event.OrderConfirmedMessageV1;
import com.loopers.domain.BaseEntity;
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
import java.time.Duration;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "order_confirmed_outbox",
    uniqueConstraints = @UniqueConstraint(name = "uk_order_confirmed_outbox_event_id", columnNames = "event_id"),
    indexes = @Index(name = "idx_order_confirmed_outbox_due", columnList = "status, next_attempt_at")
)
public class OrderConfirmedOutboxJpaEntity extends BaseEntity {

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
    @Column(name = "status", nullable = false, length = 16)
    private OrderConfirmedOutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "cycle_attempt_count", nullable = false)
    private int cycleAttemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private ZonedDateTime nextAttemptAt;

    @Column(name = "published_at")
    private ZonedDateTime publishedAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    private OrderConfirmedOutboxJpaEntity(OrderConfirmedMessageV1 message, String payload,
                                          ZonedDateTime nextAttemptAt) {
        this.eventId = message.eventId();
        this.orderId = message.orderId();
        this.eventType = message.eventType();
        this.schemaVersion = message.schemaVersion();
        this.messageKey = message.key();
        this.payload = payload;
        this.status = OrderConfirmedOutboxStatus.PENDING;
        this.attemptCount = 0;
        this.cycleAttemptCount = 0;
        this.nextAttemptAt = nextAttemptAt;
    }

    public static OrderConfirmedOutboxJpaEntity create(OrderConfirmedMessageV1 message, String payload,
                                                        ZonedDateTime nextAttemptAt) {
        return new OrderConfirmedOutboxJpaEntity(message, payload, nextAttemptAt);
    }

    public boolean isDue(ZonedDateTime now) {
        return status == OrderConfirmedOutboxStatus.PENDING && !nextAttemptAt.isAfter(now);
    }

    public void beginAttempt(ZonedDateTime now, Duration maxWait, int maxAttempts) {
        if (!isDue(now) || cycleAttemptCount >= maxAttempts) {
            throw new IllegalStateException("Outbox 행이 전송 시도를 시작할 수 있는 상태가 아닙니다.");
        }
        attemptCount++;
        cycleAttemptCount++;
        nextAttemptAt = now.plus(maxWait);
    }

    public void markPublished(ZonedDateTime publishedAt) {
        status = OrderConfirmedOutboxStatus.PUBLISHED;
        this.publishedAt = publishedAt;
        lastError = null;
        nextAttemptAt = publishedAt;
    }

    public boolean scheduleRetryOrBlock(ZonedDateTime failedAt, Duration retryInterval, int maxAttempts, String error) {
        lastError = error;
        if (cycleAttemptCount >= maxAttempts) {
            markBlocked(failedAt, error);
            return true;
        }
        status = OrderConfirmedOutboxStatus.PENDING;
        nextAttemptAt = failedAt.plus(retryInterval);
        return false;
    }

    public void markBlocked(ZonedDateTime blockedAt, String error) {
        status = OrderConfirmedOutboxStatus.BLOCKED;
        nextAttemptAt = blockedAt;
        lastError = error;
    }

    public void resumeBlocked(ZonedDateTime resumedAt) {
        if (status != OrderConfirmedOutboxStatus.BLOCKED) {
            throw new IllegalStateException("BLOCKED Outbox만 수동 재개할 수 있습니다.");
        }
        status = OrderConfirmedOutboxStatus.PENDING;
        cycleAttemptCount = 0;
        nextAttemptAt = resumedAt;
    }
}
