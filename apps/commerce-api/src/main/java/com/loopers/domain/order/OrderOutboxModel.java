package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 주문 확정 트랜잭션이 함께 남기는 "보낼 사실" 한 건 (W7 design.md 2절). 재시작한 relay가 같은 record를 다시 만들 수 있을 만큼의 정보를 담는다.
 *
 * <ul>
 *   <li>식별·key·계약·payload는 확정 트랜잭션에서 정해지고 이후 바뀌지 않는다({@code updatable = false}). relay는 현재 상품·주문을 다시 읽어 payload를 만들지 않는다.</li>
 *   <li>상태는 {@link OrderOutboxStatus#PENDING}에서 시작한다. {@code PUBLISHED}는 relay가 broker 확인을 저장했다는 뜻이고, 소비 완료가 아니다.</li>
 *   <li>{@code attemptCount}는 relay의 시도 수다. broker가 실제로 받은 횟수가 아니다.</li>
 * </ul>
 *
 * <p>주문의 원본은 여전히 주문·결제 테이블이다. 이 행은 전달할 사건만 보존한다(event sourcing이 아니다).
 */
@Entity
@Table(
    name = "order_outbox",
    uniqueConstraints = @UniqueConstraint(name = "uk_order_outbox_event", columnNames = "event_id"),
    indexes = @Index(name = "idx_order_outbox_relay", columnList = "status, next_attempt_at, id")
)
public class OrderOutboxModel extends BaseEntity {

    static final int MAX_ERROR_LENGTH = 500;

    @Column(name = "event_id", nullable = false, updatable = false, length = 100)
    private String eventId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 50)
    private String eventType;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Column(name = "message_key", nullable = false, updatable = false, length = 100)
    private String messageKey;

    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderOutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OrderOutboxModel() {
    }

    private OrderOutboxModel(OrderConfirmedEvent event, String payload, Instant recordedAt) {
        this.eventId = event.eventId();
        this.orderId = event.orderId();
        this.eventType = event.eventType();
        this.schemaVersion = event.schemaVersion();
        this.messageKey = event.key();
        this.payload = payload;
        this.status = OrderOutboxStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = recordedAt;
    }

    /**
     * 확정 트랜잭션 안에서 저장된 주문으로 만든 이벤트와 그 직렬화 값으로 PENDING 행을 만든다. {@code recordedAt}부터 relay 대상이다.
     * 이 값들은 호출자의 실수가 아니면 비어 있을 수 없으므로, 비어 있으면 요청 오류가 아니라 프로그래밍 오류로 던진다.
     */
    public static OrderOutboxModel pending(OrderConfirmedEvent event, String payload, Instant recordedAt) {
        if (event == null) {
            throw new IllegalArgumentException("Outbox에 남길 이벤트가 없습니다.");
        }
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("Outbox payload가 비어 있습니다. eventId=" + event.eventId());
        }
        if (recordedAt == null) {
            throw new IllegalArgumentException("Outbox 기록 시각이 없습니다. eventId=" + event.eventId());
        }
        return new OrderOutboxModel(event, payload, recordedAt);
    }

    /**
     * relay가 이 행을 보내기 직전에 시도를 기록한다. 시도를 시작한 수라서 전송 전에 멈춘 시도도 센다.
     * 이미 PUBLISHED면 아무것도 바꾸지 않고 false다.
     */
    public boolean startAttempt() {
        if (status != OrderOutboxStatus.PENDING) {
            return false;
        }
        attemptCount++;
        return true;
    }

    /**
     * broker 확인(producer future 성공)을 받은 뒤에만 부른다. 소비 완료가 아니라 "broker가 받았음을 저장했다"는 뜻이다.
     * 이미 PUBLISHED면 처음 기록한 시각을 지키고 false다.
     */
    public boolean markPublished(Instant publishedAt) {
        if (status != OrderOutboxStatus.PENDING) {
            return false;
        }
        status = OrderOutboxStatus.PUBLISHED;
        this.publishedAt = publishedAt;
        return true;
    }

    /**
     * 보내지 못했거나 broker 확인을 받지 못한 시도를 기록한다. 행은 PENDING으로 남아 {@code nextAttemptAt}부터 다시 relay 대상이 된다.
     * 이 행의 마지막 시도가 아니거나(늦게 도착한 이전 시도의 결과) 이미 PUBLISHED면 아무것도 바꾸지 않고 false다.
     */
    public boolean recordFailure(int attempt, String error, Instant nextAttemptAt) {
        if (status != OrderOutboxStatus.PENDING || attempt != attemptCount) {
            return false;
        }
        this.lastError = truncate(error);
        this.nextAttemptAt = nextAttemptAt;
        return true;
    }

    /**
     * 이 행이 주어진 확정 사실과 같은 사건을 담고 있는지 본다 (누락 복구에서 기존 행을 확인할 때, W7 design.md 4-4절).
     * 식별 정보와 저장된 payload가 모두 같아야 한다. 상태·시도 수는 보지 않는다(이미 보냈어도 같은 사건이다).
     */
    public boolean holdsSameFact(OrderConfirmedEvent event, String payload) {
        return eventId.equals(event.eventId())
            && orderId.equals(event.orderId())
            && eventType.equals(event.eventType())
            && schemaVersion == event.schemaVersion()
            && messageKey.equals(event.key())
            && this.payload.equals(payload);
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }

    public String getEventId() {
        return eventId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getEventType() {
        return eventType;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public String getMessageKey() {
        return messageKey;
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

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
