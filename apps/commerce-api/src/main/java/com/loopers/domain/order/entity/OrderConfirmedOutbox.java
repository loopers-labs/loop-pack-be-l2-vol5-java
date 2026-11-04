package com.loopers.domain.order.entity;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.order.enums.OrderOutboxStatus;
import com.loopers.domain.order.vo.OrderConfirmed;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 주문 확정과 같은 TX 에 남기는 "보낼 사실". relay 는 저장된 key · payload 를 그대로 보냄 (7주차 설계 3.1, 3.3).
 * orderId 도 유일하게 둬 확정 전제가 깨져도 두 번째 기록이 확정과 함께 rollback 되게 함 (D-100)
 */
@Getter
@Entity
@Table(
    name = "order_confirmed_outbox",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_order_confirmed_outbox_event_id", columnNames = "event_id"),
        @UniqueConstraint(name = "uk_order_confirmed_outbox_order_id", columnNames = "order_id")
    },
    indexes = @Index(name = "idx_order_confirmed_outbox_pending", columnList = "status, next_attempt_at, id")
)
public class OrderConfirmedOutbox extends BaseEntity {

    private static final int LAST_ERROR_MAX_LENGTH = 1000;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "message_key", nullable = false)
    private String messageKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderOutboxStatus status;

    /** 시도를 시작할 때 올려 broker 수신 횟수와 다를 수 있음 */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private ZonedDateTime nextAttemptAt;

    /** 예외 타입 · 메시지만 남기고 payload · 비밀값은 넣지 않음 (7주차 D-106) */
    @Column(name = "last_error", length = LAST_ERROR_MAX_LENGTH)
    private String lastError;

    @Column(name = "published_at")
    private ZonedDateTime publishedAt;

    protected OrderConfirmedOutbox() {}

    private OrderConfirmedOutbox(OrderConfirmed event, String payload, ZonedDateTime now) {
        this.eventId = event.eventId();
        this.orderId = event.orderId();
        this.schemaVersion = OrderConfirmed.SCHEMA_VERSION;
        this.messageKey = String.valueOf(event.orderId());
        this.payload = payload;
        this.status = OrderOutboxStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    /** 확정 commit 직후 relay 가 바로 고르도록 다음 시각을 기록 시각으로 둠 */
    public static OrderConfirmedOutbox pending(OrderConfirmed event, String payload, ZonedDateTime now) {
        return new OrderConfirmedOutbox(event, payload, now);
    }

    public boolean isDue(ZonedDateTime now) {
        return status == OrderOutboxStatus.PENDING && !nextAttemptAt.isAfter(now);
    }

    /** send 도중 멈추면 lease 가 지난 뒤 다시 골라지게 다음 시각을 미뤄 둠. 별도 "전송 중" 상태가 없음 (7주차 D-104) */
    public void startAttempt(ZonedDateTime now, Duration lease) {
        requirePending();
        this.attemptCount++;
        this.nextAttemptAt = now.plus(lease).truncatedTo(ChronoUnit.MICROS);
    }

    public void markPublished(ZonedDateTime now) {
        requirePending();
        this.status = OrderOutboxStatus.PUBLISHED;
        this.publishedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public void retryAt(ZonedDateTime nextAttemptAt, String error) {
        requirePending();
        this.nextAttemptAt = nextAttemptAt.truncatedTo(ChronoUnit.MICROS);
        this.lastError = truncate(error);
    }

    public void block(String error) {
        requirePending();
        this.status = OrderOutboxStatus.BLOCKED;
        this.lastError = truncate(error);
    }

    private void requirePending() {
        if (status != OrderOutboxStatus.PENDING) {
            throw new IllegalStateException("PENDING 이 아닌 Outbox 는 시도할 수 없음. eventId=" + eventId + " status=" + status);
        }
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= LAST_ERROR_MAX_LENGTH) {
            return error;
        }
        return error.substring(0, LAST_ERROR_MAX_LENGTH);
    }
}
