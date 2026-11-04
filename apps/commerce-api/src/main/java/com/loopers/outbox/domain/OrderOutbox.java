package com.loopers.outbox.domain;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Comment;

import java.time.ZonedDateTime;

/**
 * 주문 확정 이벤트 한 건의 Outbox 행 (docs/week7/plan.md D-0). 확정과 같은 TX에서 PENDING으로 만들고, 단일 relay가 보낸다.
 * <p>
 * 고정되는 것(eventId·orderId·key·eventType·schemaVersion·payload)은 생성 뒤 바뀌지 않는다. 재발행해도 같은 값을 보낸다.
 * 바뀌는 것은 전달 상태(status·attemptCount·nextAttemptAt·lastError·published_*)뿐이다.
 * topic은 저장하지 않는다 — relay가 설정 {@code kafka.topics.order-events}에서 읽는다 (D-0).
 * <p>
 * TODO(학습자, 핵심 로직 1): 상태 전이 메서드 — startSending · markPublished · recordFailure · resume.
 * 기대값은 plan.md "핵심 1" 표. 잘못된 상태에서 부르면 예외, 거절 뒤 기존 값 유지.
 */
@Entity
@Table(
    name = "order_outbox",
    uniqueConstraints = @UniqueConstraint(name = "uk_order_outbox_event_id", columnNames = "event_id"),
    indexes = {
        @Index(name = "idx_order_outbox_due", columnList = "status, next_attempt_at, id"),
        @Index(name = "idx_order_outbox_order", columnList = "order_id")
    }
)
public class OrderOutbox extends BaseEntity {

    @Column(name = "event_id", nullable = false, length = 36)
    @Comment("이벤트 신원(UUID). 최초 확정 TX에서 한 번 만들고, 재발행해도 같은 값 (ADR-W6-01)")
    private String eventId;

    @Column(name = "order_id", nullable = false)
    @Comment("확정된 주문 ID")
    private Long orderId;

    @Column(name = "message_key", nullable = false, length = 200)
    @Comment("partition을 고르는 key — 주문 ID 문자열. 같은 주문은 같은 partition")
    private String messageKey;

    @Column(name = "event_type", nullable = false, length = 100)
    @Comment("이벤트 종류 — OrderConfirmed")
    private String eventType;

    @Column(name = "schema_version", nullable = false)
    @Comment("payload 스키마 버전 — consumer가 해석 방법을 고른다")
    private int schemaVersion;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    @Comment("보낼 JSON 원문. 생성 뒤 바꾸지 않는다. MySQL JSON 타입은 키 순서·공백을 다시 만들어 원문이 보존되지 않아 TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Comment("PENDING → SENDING → PUBLISHED | (실패) PENDING · 3번째 실패 BLOCKED → (재개) PENDING")
    private OrderOutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    @Comment("연속 실패 횟수. 3이면 BLOCKED, 재개하면 0")
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    @Comment("PENDING: 다음 시도 가능 시각 · SENDING: 맡은 기한(lease). 지나면 relay가 고른다")
    private ZonedDateTime nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    @Comment("마지막 send 실패 원인 — 진단용. 재개해도 지우지 않는다")
    private String lastError;

    @Column(name = "published_at")
    @Comment("broker 확인(ack)을 기록한 시각")
    private ZonedDateTime publishedAt;

    @Column(name = "published_partition")
    @Comment("broker가 알려 준 partition — 재발행되면 마지막 값")
    private Integer publishedPartition;

    @Column(name = "published_offset")
    @Comment("broker가 알려 준 offset — 재발행되면 마지막 값")
    private Long publishedOffset;

    protected OrderOutbox() {}

    private OrderOutbox(String eventId, Long orderId, String eventType, int schemaVersion, String payload, ZonedDateTime now) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.messageKey = String.valueOf(orderId);
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.payload = payload;
        this.status = OrderOutboxStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = now;
    }

    /**
     * 확정 TX 안에서 만든다. 바로 보낼 차례다(next_attempt_at = now).
     */
    public static OrderOutbox pending(String eventId, Long orderId, String eventType, int schemaVersion, String payload, ZonedDateTime now) {
        return new OrderOutbox(eventId, orderId, eventType, schemaVersion, payload, now);
    }

    public String getEventId() {
        return eventId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getEventType() {
        return eventType;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public String getPayload() {
        return payload;
    }

    public OrderOutboxStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public ZonedDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public ZonedDateTime getPublishedAt() {
        return publishedAt;
    }

    public Integer getPublishedPartition() {
        return publishedPartition;
    }

    public Long getPublishedOffset() {
        return publishedOffset;
    }
}
