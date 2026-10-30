package com.loopers.payment.domain;

import com.loopers.common.domain.Money;
import com.loopers.common.domain.MoneyConverter;
import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Comment;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 결제 시도 (plan.md 3-2). 주문당 1건. 외부 결과를 확인해 종결로 옮기고, 종결은 덮어쓰지 않는다 (ADR-W5-04).
 * 카드 번호는 저장하지 않는다 (plan.md 8·9).
 */
@Entity
@Table(
    name = "payments",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_payments_order", columnNames = "order_id"),
        @UniqueConstraint(name = "uk_payments_attempt_key", columnNames = "attempt_key"),
        @UniqueConstraint(name = "uk_payments_provider_order", columnNames = "provider_order_id")
    },
    indexes = @Index(name = "idx_payments_status_next_check", columnList = "status, next_check_at")
)
public class Payment extends BaseEntity {

    /** ADR-W5-03: 보내지 않고 닫은 실패의 사유 */
    public static final String NOT_SENT = "NOT_SENT";

    /** P-14: 보낸 뒤 30분 넘게 결과를 모르는 결제를 닫은 실패의 사유 */
    public static final String EXPIRED = "EXPIRED";

    /**
     * plan.md 5-1: 다음 조회까지 기다릴 시간. 0번째는 PENDING·UNKNOWN이 된 직후, n번째는 n번 조회한 뒤. 끝 값을 계속 쓴다.
     */
    private static final List<Duration> CHECK_BACKOFF = List.of(
        Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(5)
    );

    @Column(name = "order_id", nullable = false)
    @Comment("주문 식별자. unique → 주문당 시도 1건 (plan.md 2)")
    private Long orderId;

    @Column(name = "user_id", nullable = false)
    @Comment("결제한 사용자 식별자")
    private Long userId;

    @Column(name = "attempt_key", nullable = false)
    @Comment("클라이언트 Idempotency-Key. unique (plan.md 2)")
    private String attemptKey;

    @Column(name = "provider", nullable = false)
    @Comment("PG 제공자 (예: LOCAL_PG)")
    private String provider;

    @Column(name = "provider_order_id", nullable = false)
    @Comment("PG에 보내는 주문 식별자 LP-ORD-%010d. unique (plan.md 2)")
    private String providerOrderId;

    @Convert(converter = MoneyConverter.class)
    @Column(name = "amount", nullable = false)
    @Comment("결제 금액. 저장된 주문 합계")
    private Money amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Comment("결제 상태 — REQUESTED·SENDING·PENDING·UNKNOWN·SUCCESS·FAILED (plan.md 3-2)")
    private PaymentStatus status;

    @Column(name = "transaction_key")
    @Comment("PG 거래 키. PG가 거래를 만든 뒤에만 있음")
    private String transactionKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "pg_status")
    @Comment("마지막으로 관찰한 PG 거래 상태")
    private ExternalPaymentStatus pgStatus;

    @Column(name = "failure_reason")
    @Comment("실패 사유. PG 사유, NOT_SENT(보내지 않고 닫음, ADR-W5-03) 또는 EXPIRED(보낸 뒤 30분 미해결)")
    private String failureReason;

    @Column(name = "requested_at", nullable = false)
    @Comment("시도 저장 시각 (TX A)")
    private ZonedDateTime requestedAt;

    @Column(name = "sent_at")
    @Comment("발송 권한을 얻은 시각 (SENDING)")
    private ZonedDateTime sentAt;

    @Column(name = "completed_at")
    @Comment("종결 시각 (SUCCESS·FAILED)")
    private ZonedDateTime completedAt;

    @Column(name = "last_checked_at")
    @Comment("마지막 PG 조회 시각 (reconcile)")
    private ZonedDateTime lastCheckedAt;

    @Column(name = "check_count", nullable = false)
    @Comment("PG 조회 횟수")
    private int checkCount;

    @Column(name = "next_check_at")
    @Comment("다음 PG 조회 예정 시각. PENDING·UNKNOWN만 있고 종결이면 없음 (plan.md 5-1)")
    private ZonedDateTime nextCheckAt;

    @Column(name = "last_check_result")
    @Comment("마지막 조회 결과·이상 기록 (0건·복수·불일치·모순)")
    private String lastCheckResult;

    protected Payment() {}

    private Payment(Long orderId, Long userId, String attemptKey, String provider, Money amount, ZonedDateTime now) {
        this.orderId = orderId;
        this.userId = userId;
        this.attemptKey = attemptKey;
        this.provider = provider;
        this.providerOrderId = providerOrderIdOf(orderId);
        this.amount = amount;
        this.status = PaymentStatus.REQUESTED;
        this.requestedAt = now;
        this.checkCount = 0;
    }

    /**
     * P-1·P-2: 주문 하나의 결제 시도를 REQUESTED로 만든다. 금액이 0 이하거나 시도 키가 비면 거절한다.
     */
    public static Payment request(Long orderId, Long userId, String attemptKey, String provider, Money amount, ZonedDateTime now) {
        if (amount == null || amount.isZero()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 금액은 0보다 커야 합니다.");
        }
        if (attemptKey == null || attemptKey.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 시도 키가 필요합니다.");
        }
        return new Payment(orderId, userId, attemptKey, provider, amount, now);
    }

    /**
     * plan.md 2: PG 생성·조회에 같은 문자열을 쓴다. 42 → LP-ORD-0000000042.
     */
    private static String providerOrderIdOf(Long orderId) {
        return String.format("LP-ORD-%010d", orderId);
    }

    /**
     * P-3: REQUESTED만 발송 권한을 얻는다. 그 밖은 다른 실행이 이미 가졌거나 끝난 것이다.
     */
    public void markSending(ZonedDateTime now) {
        if (status != PaymentStatus.REQUESTED) {
            throw new CoreException(ErrorType.CONFLICT, "이미 발송 권한이 정해진 결제입니다.");
        }
        this.status = PaymentStatus.SENDING;
        this.sentAt = now;
    }

    /**
     * P-7, ADR-W5-03: 보내지 않은 게 확실한 결제(REQUESTED·호출 전 차단된 SENDING)를 효과 0건 실패로 닫는다.
     * 보냈을 수 있는 PENDING·UNKNOWN은 닫지 않는다 (CONFLICT).
     */
    public PaymentApplyOutcome closeNotSent(ZonedDateTime now) {
        if (isFinal()) {
            return PaymentApplyOutcome.ALREADY_APPLIED;
        }
        if (status != PaymentStatus.REQUESTED && status != PaymentStatus.SENDING) {
            return PaymentApplyOutcome.CONFLICT;
        }
        fail(NOT_SENT, now);
        return PaymentApplyOutcome.APPLIED;
    }

    /**
     * P-14: 보낸 뒤 오래 결과를 모르는 결제(PENDING·UNKNOWN)를 FAILED(EXPIRED)로 닫는다.
     * 보내기 전·중(REQUESTED·SENDING)은 정리 스케줄러가 닫으므로 CONFLICT, 종결은 덮어쓰지 않는다.
     * PG가 실제로는 승인했다면 뒤에 오는 SUCCESS는 CONFLICT로 남는다 (P-15, 수동 환불).
     */
    public PaymentApplyOutcome expireUnresolved(ZonedDateTime now) {
        if (isFinal()) {
            return PaymentApplyOutcome.ALREADY_APPLIED;
        }
        if (!isCheckable()) {
            return PaymentApplyOutcome.CONFLICT;
        }
        fail(EXPIRED, now);
        return PaymentApplyOutcome.APPLIED;
    }

    /**
     * P-4: 생성 수락(PENDING) 응답. UNKNOWN 뒤 늦게 온 응답도 받는다. 종결은 되돌리지 않는다.
     */
    public PaymentApplyOutcome recordAccepted(String transactionKey, ZonedDateTime now) {
        if (isFinal()) {
            return PaymentApplyOutcome.ALREADY_APPLIED;
        }
        if (status == PaymentStatus.PENDING) {
            return this.transactionKey.equals(transactionKey) ? PaymentApplyOutcome.ALREADY_APPLIED : PaymentApplyOutcome.MISMATCH;
        }
        if (status == PaymentStatus.REQUESTED) {
            return PaymentApplyOutcome.CONFLICT;
        }
        markPending(transactionKey, now);
        return PaymentApplyOutcome.NOT_FINAL;
    }

    /**
     * P-5: 보낸 뒤 결과를 모른다. SENDING만 UNKNOWN이 되고, PENDING·종결은 내리지 않는다.
     */
    public PaymentApplyOutcome recordUnknown(ZonedDateTime now) {
        if (status == PaymentStatus.SENDING) {
            this.status = PaymentStatus.UNKNOWN;
            this.nextCheckAt = now.plus(CHECK_BACKOFF.get(0));
            return PaymentApplyOutcome.NOT_FINAL;
        }
        if (status == PaymentStatus.REQUESTED) {
            return PaymentApplyOutcome.CONFLICT;
        }
        return PaymentApplyOutcome.ALREADY_APPLIED;
    }

    /**
     * P-6: 효과 0건 확정 거절(PG 업무 거절). 거래가 생긴 PENDING에는 맞지 않는다 (CONFLICT).
     */
    public PaymentApplyOutcome recordRejected(String reason, ZonedDateTime now) {
        if (isFinal()) {
            return PaymentApplyOutcome.ALREADY_APPLIED;
        }
        if (status != PaymentStatus.SENDING && status != PaymentStatus.UNKNOWN) {
            return PaymentApplyOutcome.CONFLICT;
        }
        fail(reason, now);
        return PaymentApplyOutcome.APPLIED;
    }

    /**
     * P-8~P-13: PG 권위 조회 결과를 반영한다. 식별·금액이 맞는지 먼저 보고(P-13), 종결은 덮어쓰지 않는다(P-11·P-12).
     * 조회마다 횟수·시각·결과를 남긴다.
     */
    public PaymentApplyOutcome applyObservation(PaymentObservation observation, ZonedDateTime now) {
        String mismatch = mismatchOf(observation);
        if (mismatch != null) {
            recordCheck("MISMATCH:" + mismatch, now);
            return PaymentApplyOutcome.MISMATCH;
        }
        ExternalPaymentStatus observed = observation.status();
        if (isFinal()) {
            if (observed == ExternalPaymentStatus.PENDING || observed.name().equals(status.name())) {
                recordCheck("OBSERVED:" + observed, now);
                return PaymentApplyOutcome.ALREADY_APPLIED;
            }
            recordCheck("CONFLICT:" + status + "<-" + observed, now);
            return PaymentApplyOutcome.CONFLICT;
        }
        if (status == PaymentStatus.REQUESTED) {
            recordCheck("CONFLICT:" + status + "<-" + observed, now);
            return PaymentApplyOutcome.CONFLICT;
        }
        this.transactionKey = observation.transactionKey();
        this.pgStatus = observed;
        PaymentApplyOutcome outcome = switch (observed) {
            case PENDING -> {
                this.status = PaymentStatus.PENDING;
                yield PaymentApplyOutcome.NOT_FINAL;
            }
            case SUCCESS -> {
                this.status = PaymentStatus.SUCCESS;
                this.completedAt = now;
                yield PaymentApplyOutcome.APPLIED;
            }
            case FAILED -> {
                fail(observation.reason(), now);
                yield PaymentApplyOutcome.APPLIED;
            }
        };
        // 상태를 바꾼 뒤에 기록해야 다음 조회 시각이 바뀐 상태(PENDING·종결)를 따른다
        recordCheck("OBSERVED:" + observed, now);
        return outcome;
    }

    /**
     * P-13: 조회 0건·복수처럼 판단할 수 없는 결과를 상태 변경 없이 남긴다.
     */
    public void recordCheck(String result, ZonedDateTime now) {
        this.checkCount++;
        this.lastCheckedAt = now;
        this.lastCheckResult = result;
        this.nextCheckAt = isCheckable() ? now.plus(backoffAfter(checkCount)) : null;
    }

    /**
     * plan.md 5-1: 결과를 모르는 결제만 PG 조회로 확인한다.
     */
    private boolean isCheckable() {
        return status == PaymentStatus.PENDING || status == PaymentStatus.UNKNOWN;
    }

    private static Duration backoffAfter(int checks) {
        return CHECK_BACKOFF.get(Math.min(checks, CHECK_BACKOFF.size() - 1));
    }

    public boolean isFinal() {
        return status.isFinal();
    }

    private String mismatchOf(PaymentObservation observation) {
        if (!providerOrderId.equals(observation.providerOrderId())) {
            return "providerOrderId";
        }
        if (!amount.equals(observation.amount())) {
            return "amount";
        }
        if (transactionKey != null && !transactionKey.equals(observation.transactionKey())) {
            return "transactionKey";
        }
        return null;
    }

    private void markPending(String transactionKey, ZonedDateTime now) {
        this.status = PaymentStatus.PENDING;
        this.transactionKey = transactionKey;
        this.pgStatus = ExternalPaymentStatus.PENDING;
        this.nextCheckAt = now.plus(CHECK_BACKOFF.get(0));
    }

    private void fail(String reason, ZonedDateTime now) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.completedAt = now;
        this.nextCheckAt = null;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getAttemptKey() {
        return attemptKey;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public Money getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getTransactionKey() {
        return transactionKey;
    }

    public ExternalPaymentStatus getPgStatus() {
        return pgStatus;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public ZonedDateTime getRequestedAt() {
        return requestedAt;
    }

    public ZonedDateTime getSentAt() {
        return sentAt;
    }

    public ZonedDateTime getCompletedAt() {
        return completedAt;
    }

    public ZonedDateTime getLastCheckedAt() {
        return lastCheckedAt;
    }

    public int getCheckCount() {
        return checkCount;
    }

    public ZonedDateTime getNextCheckAt() {
        return nextCheckAt;
    }

    public String getLastCheckResult() {
        return lastCheckResult;
    }
}
