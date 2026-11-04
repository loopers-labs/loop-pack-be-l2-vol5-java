package com.loopers.domain.outbox;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.ZonedDateTime;

/**
 * 브로커로 전달할 사실 한 건. 사건을 가리키는 값과 payload 는 기록한 뒤 바뀌지 않고, 전달 진행 상태만 바뀐다.
 */
@Getter
@Entity
@Table(
    name = "outbox",
    uniqueConstraints = @UniqueConstraint(name = "uk_outbox_event_id", columnNames = "event_id"),
    indexes = @Index(name = "idx_outbox_status_next_attempt_at", columnList = "status, next_attempt_at")
)
public class Outbox extends BaseEntity {

    private static final int MAX_LAST_ERROR_LENGTH = 500;

    @Column(name = "event_id", nullable = false, updatable = false, length = 100)
    private String eventId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 50)
    private String eventType;

    @Column(name = "schema_version", nullable = false, updatable = false, length = 10)
    private String schemaVersion;

    @Column(name = "message_key", nullable = false, updatable = false, length = 100)
    private String messageKey;

    @Column(name = "payload", nullable = false, updatable = false, length = 16_384)
    private byte[] payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private ZonedDateTime nextAttemptAt;

    @Column(name = "last_error", length = MAX_LAST_ERROR_LENGTH)
    private String lastError;

    @Column(name = "published_at")
    private ZonedDateTime publishedAt;

    // JPA 가 사용하는 기본 생성자. 새 Outbox 는 record() 로 만든다.
    protected Outbox() {}

    /** 기록한 즉시 전송 대상이 되는 PENDING Outbox 를 만든다. */
    public static Outbox record(
        String eventId, Long orderId, String eventType, String schemaVersion,
        String messageKey, byte[] payload, ZonedDateTime recordedAt
    ) {
        requireText(eventId, "eventId");
        requireText(eventType, "eventType");
        requireText(schemaVersion, "schemaVersion");
        requireText(messageKey, "messageKey");
        if (orderId == null) {
            throw new CoreException(ErrorType.INVALID_INPUT, "주문은 필수입니다.");
        }
        if (payload == null || payload.length == 0) {
            throw new CoreException(ErrorType.INVALID_INPUT, "payload 는 비어 있을 수 없습니다.");
        }
        if (recordedAt == null) {
            throw new CoreException(ErrorType.INVALID_INPUT, "기록 시각은 필수입니다.");
        }
        Outbox outbox = new Outbox();
        outbox.eventId = eventId;
        outbox.orderId = orderId;
        outbox.eventType = eventType;
        outbox.schemaVersion = schemaVersion;
        outbox.messageKey = messageKey;
        outbox.payload = payload.clone();
        outbox.status = OutboxStatus.PENDING;
        outbox.nextAttemptAt = recordedAt;
        return outbox;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new CoreException(ErrorType.INVALID_INPUT, name + " 는 필수입니다.");
        }
    }

    /** 전송 시도를 시작한다. broker 가 받은 횟수가 아니라 시작한 횟수다. */
    public void startAttempt() {
        attemptCount++;
    }

    /** broker 성공을 확인해 저장한다. 이미 발행된 건은 바꾸지 않는다. */
    public void markPublished(ZonedDateTime publishedAt) {
        if (status == OutboxStatus.PUBLISHED) {
            return;
        }
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = publishedAt;
    }

    /** 전송 실패를 기록하고 PENDING 으로 남겨 다음 시각에 다시 찾게 한다. 이미 발행된 건은 바꾸지 않는다. */
    public void scheduleRetry(ZonedDateTime nextAttemptAt, String error) {
        if (status == OutboxStatus.PUBLISHED) {
            return;
        }
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = error.length() > MAX_LAST_ERROR_LENGTH ? error.substring(0, MAX_LAST_ERROR_LENGTH) : error;
    }
}
