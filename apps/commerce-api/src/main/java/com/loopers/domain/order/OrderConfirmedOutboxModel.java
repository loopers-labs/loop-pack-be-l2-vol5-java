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
 *
 * <p>전달 상태 행동(설계 W7.3-3)은 부분 대입 전에 {@link #requireDeliveryState()}·허용 상태·시도 번호·시각/입력을 검사한다.
 * 저장 본문의 의미는 verifier 가 따로 검사하므로 여기서 payload 를 다시 파싱하지 않는다. 그래서 본문이 손상된 행도 시도·차단 정보를 남길 수 있다.
 * 이미 PUBLISHED 면 변경 없이 {@link Transition#ALREADY_PUBLISHED}, 진행 중인 시도 번호가 아니면 {@link Transition#STALE_ATTEMPT} 이다.
 * 해석할 수 없는 저장 상태·예상 밖 전이는 정상 결과로 바꾸지 않고 INTERNAL_ERROR 로 드러낸다.
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

    /** 상태 행동의 결과. APPLIED 외에는 필드를 바꾸지 않는다. */
    public enum Transition {
        APPLIED,
        ALREADY_PUBLISHED,
        ALREADY_PENDING,
        STALE_ATTEMPT
    }

    private static final String ATTEMPT_LIMIT_EXHAUSTED = "ATTEMPT_LIMIT_EXHAUSTED";

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

    /**
     * 저장된 전달 상태의 정합성(설계 W7.3-3). PK·상태·횟수 범위·운영 시각·상태별 발행 시각/오류 결합·삭제 시각 null 을 본다.
     * 재개된 PENDING(누적 > 0·회차 0·최근 lastError 유지)은 정상이다. 본문 의미는 검사하지 않는다.
     */
    public void requireDeliveryState() {
        Long id = getId();
        boolean valid = id != null && id > 0L
            && status != null
            && attemptCount >= 0L && cycleAttemptCount >= 0 && cycleAttemptCount <= attemptCount
            && OrderModel.isUtcMicros(nextAttemptAt)
            && getDeletedAt() == null
            && switch (status) {
                case PENDING -> publishedAt == null;
                case BLOCKED -> publishedAt == null && cycleAttemptCount >= 1;
                case PUBLISHED -> OrderModel.isUtcMicros(publishedAt) && lastError == null && cycleAttemptCount >= 1;
            };
        if (!valid) {
            throw integrityError("Outbox 행의 전달 상태가 저장 계약을 지키지 않습니다.");
        }
    }

    /** 저장된 사실의 raw 복사. 해석·검사하지 않는다. */
    public OrderOutboxStoredMessage storedMessage() {
        return new OrderOutboxStoredMessage(eventId, orderId, eventType, schemaVersion, messageKey, payload);
    }

    /** due 인 PENDING 의 시도 시작. 두 횟수를 더하고 다음 시각을 provisional 값으로 둔다. 불변 사실·lastError 는 그대로다. */
    public void beginAttempt(ZonedDateTime now, ZonedDateTime provisionalNextAt) {
        requireDeliveryState();
        requireTime(now);
        requireTime(provisionalNextAt);
        if (status != OrderOutboxStatus.PENDING || nextAttemptAt.isAfter(now) || provisionalNextAt.isBefore(now)) {
            throw integrityError("due 인 PENDING 행만 시도를 시작할 수 있습니다.");
        }
        long nextAttemptCount;
        int nextCycleAttemptCount;
        try {
            nextAttemptCount = Math.addExact(attemptCount, 1L);
            nextCycleAttemptCount = Math.addExact(cycleAttemptCount, 1);
        } catch (ArithmeticException overflow) {
            throw integrityError("Outbox 시도 횟수가 표현 범위를 넘습니다.");
        }
        this.attemptCount = nextAttemptCount;
        this.cycleAttemptCount = nextCycleAttemptCount;
        this.nextAttemptAt = provisionalNextAt;
    }

    /** broker 확인 뒤 발행 저장. 불변 사실·횟수는 그대로다. */
    public Transition markPublished(long attemptNo, ZonedDateTime now) {
        Transition guard = guardCurrentAttempt(attemptNo);
        if (guard != null) {
            return guard;
        }
        requireTime(now);
        this.status = OrderOutboxStatus.PUBLISHED;
        this.publishedAt = now;
        this.lastError = null;
        return Transition.APPLIED;
    }

    /**
     * 전송 실패 저장. 회차 횟수가 한도에 닿았으면 BLOCKED·ATTEMPT_LIMIT_EXHAUSTED 와 원인 code, 아니면 PENDING·nextAt·원인 code 다.
     */
    public Transition recordFailure(long attemptNo, String safeError, ZonedDateTime nextAt, int maxCycleAttempts) {
        Transition guard = guardCurrentAttempt(attemptNo);
        if (guard != null) {
            return guard;
        }
        requireSafeError(safeError);
        if (maxCycleAttempts < 1) {
            throw integrityError("회차 시도 한도는 1 이상이어야 합니다.");
        }
        if (cycleAttemptCount >= maxCycleAttempts) {
            this.status = OrderOutboxStatus.BLOCKED;
            this.lastError = ATTEMPT_LIMIT_EXHAUSTED + ":" + safeError;
        } else {
            requireTime(nextAt);
            this.nextAttemptAt = nextAt;
            this.lastError = safeError;
        }
        this.publishedAt = null;
        return Transition.APPLIED;
    }

    /** 전송 없이 차단한다. 본문 검증 오류와 시작 전 회차 예산 소진(현재 누적 번호로 호출)에 쓴다. */
    public Transition blockInvalid(long attemptNo, String safeError) {
        Transition guard = guardCurrentAttempt(attemptNo);
        if (guard != null) {
            return guard;
        }
        requireSafeError(safeError);
        this.status = OrderOutboxStatus.BLOCKED;
        this.lastError = safeError;
        this.publishedAt = null;
        return Transition.APPLIED;
    }

    /** BLOCKED 의 명시 재개. 회차만 0 으로 되돌리고 누적 횟수·불변 사실·최근 lastError 는 보존한다. */
    public Transition resume(ZonedDateTime now) {
        requireDeliveryState();
        if (status == OrderOutboxStatus.PUBLISHED) {
            return Transition.ALREADY_PUBLISHED;
        }
        if (status == OrderOutboxStatus.PENDING) {
            return Transition.ALREADY_PENDING;
        }
        requireTime(now);
        this.status = OrderOutboxStatus.PENDING;
        this.cycleAttemptCount = 0;
        this.nextAttemptAt = now;
        return Transition.APPLIED;
    }

    /**
     * 진행 중인 시도 번호인지 본다. 이미 PUBLISHED 면 ALREADY_PUBLISHED, 누적 번호가 다르거나 재개 뒤 시작 전(회차 0)이면 STALE_ATTEMPT.
     * 같은 번호인데 PENDING 이 아니면 예상 밖 전이다. 반환 null 은 변경해도 된다는 뜻이다.
     */
    private Transition guardCurrentAttempt(long attemptNo) {
        requireDeliveryState();
        if (status == OrderOutboxStatus.PUBLISHED) {
            return Transition.ALREADY_PUBLISHED;
        }
        if (attemptNo < 1L || attemptNo != attemptCount || cycleAttemptCount == 0) {
            return Transition.STALE_ATTEMPT;
        }
        if (status != OrderOutboxStatus.PENDING) {
            throw integrityError("진행 중인 시도의 Outbox 행이 PENDING 이 아닙니다.");
        }
        return null;
    }

    private static void requireTime(ZonedDateTime value) {
        if (!OrderModel.isUtcMicros(value)) {
            throw integrityError("Outbox 운영 시각이 UTC·마이크로초 값이 아닙니다.");
        }
    }

    private static void requireSafeError(String safeError) {
        if (safeError == null || safeError.isBlank()) {
            throw integrityError("Outbox 오류 code 가 비어 있습니다.");
        }
    }

    private static CoreException integrityError(String message) {
        return new CoreException(ErrorType.INTERNAL_ERROR, message);
    }

    public static class StatusConverter extends EnumNameConverter<OrderOutboxStatus> {
        public StatusConverter() {
            super(OrderOutboxStatus.class);
        }
    }
}
