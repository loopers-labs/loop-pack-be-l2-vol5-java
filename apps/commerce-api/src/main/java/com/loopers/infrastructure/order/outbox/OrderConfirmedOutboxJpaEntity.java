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

    // 시도 시작·결과 저장은 저장소의 조건부 갱신이 맡고, 엔티티는 기한 판단과 수동 재시도만 표현한다.
    public boolean isDue(ZonedDateTime now) {
        return status == OrderConfirmedOutboxStatus.PENDING && !nextAttemptAt.isAfter(now);
    }

    public void resumeBlocked(ZonedDateTime resumedAt) {
        if (status != OrderConfirmedOutboxStatus.BLOCKED) {
            throw new IllegalStateException("BLOCKED Outbox만 수동 재시도할 수 있습니다.");
        }
        status = OrderConfirmedOutboxStatus.PENDING;
        cycleAttemptCount = 0;
        nextAttemptAt = resumedAt;
    }
}
