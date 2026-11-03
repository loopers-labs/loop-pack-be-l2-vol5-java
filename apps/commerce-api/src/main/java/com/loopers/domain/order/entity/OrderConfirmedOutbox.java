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

    @Column(name = "last_error", length = 1000)
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
}
