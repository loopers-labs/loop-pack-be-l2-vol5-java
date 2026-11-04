package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.common.EnumNameConverter;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import org.hibernate.annotations.Check;

import java.time.ZonedDateTime;

/**
 * 주문 확정 사건 하나의 전달을 맡는 Outbox 행(설계 W7.2-4·W7.2-5). 범용 이벤트 플랫폼이 아니다.
 *
 * <p>사건 사실(eventId·orderId·eventType·schemaVersion·messageKey·payload)은 최초 insert 때 모두 갖고 이후 바꾸지 않는다.
 * {@code updatable=false} 와 변경 메서드 미제공으로 ORM 쓰기만 제한하며, SQL 직접 수정까지 막는 DB 불변성은 아니다.
 * 물리 FK·별도 UNIQUE(order_id)·문자열 값 CHECK 는 두지 않는다. 사건 ID 를 주문 ID 에서 유도하고 생성 경계가 검사한다.
 * 전달 상태 행동(시도 시작·발행 확인·실패·차단·재개)은 W7.3 단위에서 추가한다.
 */
@Entity
@Table(
    name = "order_confirmed_outbox",
    uniqueConstraints = @UniqueConstraint(name = "uk_oco_event", columnNames = "event_id"),
    indexes = @Index(name = "idx_oco_due", columnList = "status, next_attempt_at, id")
)
@Check(name = "ck_oco_order_id", constraints = "order_id > 0")
@Check(name = "ck_oco_attempt_count", constraints = "attempt_count >= 0")
@Check(name = "ck_oco_cycle_attempt_count", constraints = "cycle_attempt_count >= 0")
@Getter
public class OrderConfirmedOutboxModel extends BaseEntity {

    // 식별·전송 메타데이터 열은 대소문자를 구분하는 ASCII binary collation 이다. 기본 생성으로는 나오지 않아 열 정의를 적는다.
    @Column(name = "event_id", nullable = false, updatable = false,
        columnDefinition = "varchar(64) character set ascii collate ascii_bin")
    private String eventId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "event_type", nullable = false, updatable = false,
        columnDefinition = "varchar(32) character set ascii collate ascii_bin")
    private String eventType;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private Integer schemaVersion;

    @Column(name = "message_key", nullable = false, updatable = false,
        columnDefinition = "varchar(20) character set ascii collate ascii_bin")
    private String messageKey;

    /** encoder 가 만든 v1 JSON 원문. JSON 열이 아니며 열 자체가 JSON 유효성을 보장하지 않는다. */
    @Column(name = "payload", nullable = false, updatable = false,
        columnDefinition = "longtext character set utf8mb4 collate utf8mb4_bin")
    private String payload;

    @Convert(converter = StatusConverter.class)
    @Column(name = "status", nullable = false, length = 16)
    private OrderOutboxStatus status;

    /** 처리 시도 시작의 누적 수. broker 수신 횟수가 아니다. */
    @Column(name = "attempt_count", nullable = false)
    private long attemptCount;

    /** 수동 재개 이후 자동 시도 예산을 따로 센다. */
    @Column(name = "cycle_attempt_count", nullable = false)
    private int cycleAttemptCount;

    @Column(name = "next_attempt_at", nullable = false, columnDefinition = "DATETIME(6)")
    private ZonedDateTime nextAttemptAt;

    /** 안전한 오류 코드만 담는다. 원문 예외·payload·비밀은 저장하지 않는다. */
    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    /** broker 확인 뒤 상태 저장 시각. 사건의 confirmedAt 을 대신하지 않는다. */
    @Column(name = "published_at", columnDefinition = "DATETIME(6)")
    private ZonedDateTime publishedAt;

    protected OrderConfirmedOutboxModel() {}

    private OrderConfirmedOutboxModel(OrderOutboxMessage message, ZonedDateTime firstAttemptAt) {
        this.eventId = message.eventId();
        this.orderId = message.orderId();
        this.eventType = message.eventType();
        this.schemaVersion = message.schemaVersion();
        this.messageKey = message.messageKey();
        this.payload = message.payload();
        this.status = OrderOutboxStatus.PENDING;
        this.attemptCount = 0L;
        this.cycleAttemptCount = 0;
        this.nextAttemptAt = firstAttemptAt;
        this.lastError = null;
        this.publishedAt = null;
    }

    /**
     * 최초 기록용 PENDING 행을 만든다. 입력을 모두 검사한 뒤에만 필드를 구성하므로 부분 초기화된 행이 생기지 않는다.
     * firstAttemptAt 은 호출자가 Outbox 기록 Clock 으로 준비한 UTC·마이크로초 시각이며 사건의 confirmedAt 과 다르다.
     */
    public static OrderConfirmedOutboxModel pending(OrderOutboxMessage message, ZonedDateTime firstAttemptAt) {
        if (message == null) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "Outbox 메시지가 없습니다.");
        }
        if (!OrderModel.isUtcMicros(firstAttemptAt)) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "Outbox 최초 시도 시각이 UTC·마이크로초 값이 아닙니다.");
        }
        return new OrderConfirmedOutboxModel(message, firstAttemptAt);
    }

    public static class StatusConverter extends EnumNameConverter<OrderOutboxStatus> {
        public StatusConverter() {
            super(OrderOutboxStatus.class);
        }
    }
}
