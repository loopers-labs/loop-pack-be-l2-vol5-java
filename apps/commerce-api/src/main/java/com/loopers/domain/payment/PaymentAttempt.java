package com.loopers.domain.payment;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * 주문 하나의 PG 결제 시도. 상태 전이 규칙을 스스로 지킨다 — 결과가 들어오는 경로(callback 워커·reconcile·
 * 스케줄러·관리자)가 늘어나도 규칙이 빠지지 않게 하기 위해서다(docs/week5/design.md D-001).
 *
 * - 생성 = 발송 시작 기록. 이 행이 commit된 뒤에만 create를 보낸다(INV-005).
 * - orderId는 생성자에서만 정해진다. callback 경로가 잠그지 않고 읽어도 안전한 근거다(INV-007).
 * - 미종결 → 종결 전이는 한 번뿐이고, 호출자는 전이가 일어났을 때만 주문·재고 효과를 낸다(INV-002).
 * - 종결 뒤 같은 결과는 무시, 다른 결과는 상태를 바꾸지 않고 CONFLICT로 알린다(INV-003).
 *
 * 동시에 들어오는 반영은 호출자가 행 락으로 직렬화한다(D-002). 이 클래스는 잠근 뒤 읽은 상태로 판단한다.
 */
// UK(order_id): 주문당 한 시도. 주문 락이 이미 줄을 세우지만, 주문 락을 거치지 않는 경로(관리자·배치)가 생겨도 DB가 지키는 마지막 방어선이다.
// UK(attempt_key): 같은 키가 서로 다른 주문에 동시에 들어오면 각자 다른 주문을 잠가 직렬화되지 않는다 — 이 제약이 실제 방어선이다.
// (docs/week5/design.md D-016) provider_order_id·transaction_key 인덱스는 callback 워커가 본문의 키로 시도를 찾는 경로다(D-021).
@Entity
@Table(
    name = "payment_attempt",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_attempt_order_id", columnNames = "order_id"),
        @UniqueConstraint(name = "uk_payment_attempt_attempt_key", columnNames = "attempt_key")
    },
    indexes = {
        @Index(name = "idx_payment_attempt_provider_order_id", columnList = "provider_order_id"),
        @Index(name = "idx_payment_attempt_transaction_key", columnList = "transaction_key"),
        // 회복 작업이 "다음 확인 시각이 지난 미종결 시도"를 찾는 경로다(D-041).
        @Index(name = "idx_payment_attempt_status_next_check_at", columnList = "status, next_check_at")
    }
)
public class PaymentAttempt extends BaseEntity {

    static final Duration FIRST_CHECK_DELAY = Duration.ofMinutes(1);
    static final Duration CHECK_INTERVAL = Duration.ofMinutes(3);
    static final Duration FOURTH_EMPTY_INTERVAL = Duration.ofMinutes(30);
    static final Duration LONG_EMPTY_INTERVAL = Duration.ofHours(1);
    public static final int REVIEW_THRESHOLD = 3;
    static final Duration WATCH_PERIOD = Duration.ofDays(3);
    static final Duration WATCH_INTERVAL = Duration.ofHours(6);

    private Long orderId;
    private String attemptKey;
    private String providerOrderId;
    private String transactionKey;

    @Enumerated(EnumType.STRING)
    private PaymentAttemptStatus status;

    private Long amount;
    private ZonedDateTime sendStartedAt;

    // 회복 작업의 확인 일정(docs/week5/design.md D-007, D-008, D-043, D-044, recovery.md 3번).
    private int emptyLookupCount;
    private ZonedDateTime nextCheckAt;
    private ZonedDateTime reviewRequestedAt;

    // 관리자 실패 처리(D-009)와 그 뒤의 감시(D-013).
    private String resolvedBy;
    private ZonedDateTime resolvedAt;
    private ZonedDateTime watchUntil;

    protected PaymentAttempt() {}

    public PaymentAttempt(Long orderId, String attemptKey, String providerOrderId, long amount) {
        if (orderId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 시도의 주문 ID는 비어있을 수 없습니다.");
        }
        if (attemptKey == null || attemptKey.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 시도 키는 비어있을 수 없습니다.");
        }
        if (providerOrderId == null || providerOrderId.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "providerOrderId는 비어있을 수 없습니다.");
        }
        if (amount < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 금액은 음수일 수 없습니다.");
        }
        this.orderId = orderId;
        this.attemptKey = attemptKey;
        this.providerOrderId = providerOrderId;
        this.amount = amount;
        this.status = PaymentAttemptStatus.REQUESTED;
        this.sendStartedAt = ZonedDateTime.now();
        // 대상 기준 시간 — 정상 callback을 기다려 준 뒤에 회복 작업이 본다. 오래 남은 요청됨(D-018)도 이 기준으로 대상이 된다.
        this.nextCheckAt = sendStartedAt.plus(FIRST_CHECK_DELAY);
    }

    /**
     * 이미 저장된 시도에 같은 이름표(attemptKey)의 요청이 다시 왔는지 판단한다(docs/week5/design.md D-015).
     * 같은 주문·같은 키면 통과한다 — 호출자는 PG를 다시 부르지 않고 저장된 상태를 돌려준다(②).
     * 400과 409는 "요청자가 입력을 바꾸면 해결되는가"로 나눈다.
     */
    public void assertSameRequest(Long orderId, String attemptKey) {
        if (!this.orderId.equals(orderId)) {
            // ④ 새 키를 붙이면 해결된다.
            throw new CoreException(ErrorType.BAD_REQUEST, "이미 다른 주문에 사용한 결제 시도 키입니다.");
        }
        if (!this.attemptKey.equals(attemptKey)) {
            // ③ 어떤 입력으로도 이 주문의 새 결제를 시작할 수 없다(주문당 시도 하나).
            throw new CoreException(ErrorType.CONFLICT, "이미 결제를 시작한 주문입니다.");
        }
    }

    /**
     * create가 202 PENDING으로 수락됐다. 이미 종결됐다면(빠른 callback이 먼저 반영한 경우) 늦게 도착한
     * 생성 응답이 상태를 되돌리지 않는다(INV-003 반례 1).
     */
    public ApplyResult accept(String transactionKey) {
        if (transactionKey == null || transactionKey.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "transactionKey는 비어있을 수 없습니다.");
        }
        if (this.transactionKey != null && !this.transactionKey.equals(transactionKey)) {
            return ApplyResult.CONFLICT;
        }
        if (status.isTerminal() || status == PaymentAttemptStatus.PENDING) {
            return ApplyResult.NO_EFFECT;
        }
        this.transactionKey = transactionKey;
        this.status = PaymentAttemptStatus.PENDING;
        return ApplyResult.NO_EFFECT;
    }

    /**
     * create 응답을 받지 못했다(timeout·연결 실패·깨진 응답 등). 실패가 아니라 "모른다"이다(INV-004, D-025).
     * 발송 중(REQUESTED)일 때만 바뀌고, 이미 더 많이 알게 된 상태는 되돌리지 않는다.
     */
    public void markUnknown() {
        if (status == PaymentAttemptStatus.REQUESTED) {
            this.status = PaymentAttemptStatus.UNKNOWN;
        }
    }

    /**
     * PG가 거래를 만들기 전에 확정 거절했다(정확한 조건의 422만, docs/week5/pg-contract.md 2번).
     * 효과 0건이 보장된 유일한 생성 응답이라 바로 FAILED로 종결한다. 거래가 없으므로 transactionKey를 꾸며 넣지 않는다.
     */
    public ApplyResult rejectBeforeCreate() {
        if (status != PaymentAttemptStatus.REQUESTED) {
            return ApplyResult.NO_EFFECT;
        }
        this.status = PaymentAttemptStatus.FAILED;
        return ApplyResult.FAILED;
    }

    /**
     * PG 권위 조회로 확인한 거래를 반영한다.
     */
    public ApplyResult apply(PaymentObservation observation) {
        if (!matches(observation)) {
            return ApplyResult.CONFLICT;
        }
        if (status.isTerminal()) {
            return isSameOutcome(observation.status()) ? ApplyResult.NO_EFFECT : ApplyResult.CONFLICT;
        }
        this.transactionKey = observation.transactionKey();
        return switch (observation.status()) {
            case PENDING -> {
                this.status = PaymentAttemptStatus.PENDING;
                yield ApplyResult.NO_EFFECT;
            }
            case SUCCESS -> {
                this.status = PaymentAttemptStatus.SUCCESS;
                yield ApplyResult.SUCCEEDED;
            }
            case FAILED -> {
                this.status = PaymentAttemptStatus.FAILED;
                yield ApplyResult.FAILED;
            }
        };
    }

    /**
     * apply가 CONFLICT를 돌려준 관찰의 사유. 거래가 우리 기록(주문 식별자·금액·거래 키)과 어긋나면 MISMATCH,
     * 맞는 거래인데 이미 종결된 상태와 다른 결과면 DIFFERENT_OUTCOME_AFTER_TERMINAL이다(D-011).
     */
    public PaymentConflictReason conflictReasonFor(PaymentObservation observation) {
        return matches(observation) ? PaymentConflictReason.DIFFERENT_OUTCOME_AFTER_TERMINAL : PaymentConflictReason.MISMATCH;
    }

    private boolean matches(PaymentObservation observation) {
        return providerOrderId.equals(observation.providerOrderId())
            && amount == observation.amount()
            && (transactionKey == null || transactionKey.equals(observation.transactionKey()));
    }

    private boolean isSameOutcome(PaymentObservation.Status observed) {
        return (status == PaymentAttemptStatus.SUCCESS && observed == PaymentObservation.Status.SUCCESS)
            || (status == PaymentAttemptStatus.FAILED && observed == PaymentObservation.Status.FAILED);
    }

    /**
     * 관리자가 PG에 거래가 없음을 직접 확인하고 실패로 처리한다(D-009). 누가·언제 처리했는지 남기고, 사람의 판단이 틀렸을 때를 대비해
     * 3일 동안 6시간마다 계속 조회한다(감시, D-013). 늦게 나타난 SUCCESS는 상태를 바꾸지 않고 충돌로 기록된다(INV-003).
     * 거래 키를 모르는 미종결 시도만 허용한다 — 처리중은 PG가 거래를 만들었다고 이미 답했으므로 사람이 덮어쓰지 않는다(D-045).
     */
    public ApplyResult failByAdmin(String resolvedBy, ZonedDateTime now) {
        if (resolvedBy == null || resolvedBy.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "처리자는 비어있을 수 없습니다.");
        }
        if (status != PaymentAttemptStatus.REQUESTED && status != PaymentAttemptStatus.UNKNOWN) {
            throw new CoreException(ErrorType.CONFLICT,
                "거래 키를 모르는 미종결 결제만 관리자가 실패 처리할 수 있습니다. (현재: " + status + ")");
        }
        this.status = PaymentAttemptStatus.FAILED;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = now;
        this.watchUntil = now.plus(WATCH_PERIOD);
        this.nextCheckAt = now.plus(WATCH_INTERVAL);
        return ApplyResult.FAILED;
    }

    /** 관리자 실패 처리 뒤 감시 기간 안인가(D-013). 감시 중인 시도는 종결됐어도 회복 작업이 계속 조회한다. */
    public boolean isWatched(ZonedDateTime now) {
        return status == PaymentAttemptStatus.FAILED && watchUntil != null && !now.isAfter(watchUntil);
    }

    /** 감시 조회를 마쳤다 — 결과와 상관없이 6시간 뒤 다시 본다. 다른 결과는 반영 쪽이 충돌로 기록한다. */
    public void recordWatchCheck(ZonedDateTime now) {
        this.nextCheckAt = now.plus(WATCH_INTERVAL);
    }

    /**
     * 회복 작업의 조회가 0건이었다 — 거래가 없다는 증거는 아니지만(INV-004) 이어지면 덜 자주 묻는다(D-008).
     * 1~3번째는 3분, 4번째는 30분, 5번째부터 1시간 뒤에 다시 본다. 3번째에 관리자 확인을 요청한 시각을 한 번만 적는다(D-007, D-035).
     * 조회는 멈추지 않는다 — 관리자 목록에 올라도 계속 확인한다.
     */
    public void recordEmptyLookup(ZonedDateTime now) {
        this.emptyLookupCount++;
        if (emptyLookupCount == REVIEW_THRESHOLD && reviewRequestedAt == null) {
            this.reviewRequestedAt = now;
        }
        this.nextCheckAt = now.plus(emptyLookupInterval());
    }

    private Duration emptyLookupInterval() {
        if (emptyLookupCount <= REVIEW_THRESHOLD) {
            return CHECK_INTERVAL;
        }
        return emptyLookupCount == REVIEW_THRESHOLD + 1 ? FOURTH_EMPTY_INTERVAL : LONG_EMPTY_INTERVAL;
    }

    /** 회복 작업의 조회로 거래를 봤다 — 연속 0건이 끊긴다. 아직 미종결이면 다음 회차에 다시 본다. */
    public void recordFound(ZonedDateTime now) {
        this.emptyLookupCount = 0;
        this.nextCheckAt = now.plus(CHECK_INTERVAL);
    }

    /** 물어보지 못했다(timeout·5xx·회로 OPEN) — 0건이 아니므로 횟수를 바꾸지 않고 다음 회차에 다시 본다(D-044). */
    public void recordUnavailable(ZonedDateTime now) {
        this.nextCheckAt = now.plus(CHECK_INTERVAL);
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getAttemptKey() {
        return attemptKey;
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public String getTransactionKey() {
        return transactionKey;
    }

    public PaymentAttemptStatus getStatus() {
        return status;
    }

    public long getAmount() {
        return amount;
    }

    public int getEmptyLookupCount() {
        return emptyLookupCount;
    }

    public ZonedDateTime getNextCheckAt() {
        return nextCheckAt;
    }

    public ZonedDateTime getReviewRequestedAt() {
        return reviewRequestedAt;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public ZonedDateTime getResolvedAt() {
        return resolvedAt;
    }

    public ZonedDateTime getWatchUntil() {
        return watchUntil;
    }

    public ZonedDateTime getSendStartedAt() {
        return sendStartedAt;
    }
}
