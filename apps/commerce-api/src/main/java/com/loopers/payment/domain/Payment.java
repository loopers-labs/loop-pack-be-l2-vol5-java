package com.loopers.payment.domain;

import com.loopers.common.domain.Money;
import com.loopers.common.domain.MoneyConverter;
import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Comment;

import java.time.ZonedDateTime;

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
    indexes = @Index(name = "idx_payments_status_checked", columnList = "status, last_checked_at")
)
public class Payment extends BaseEntity {

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
    @Comment("실패 사유. PG 사유 또는 NOT_SENT(보내지 않고 닫음, ADR-W5-03)")
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

    @Column(name = "last_check_result")
    @Comment("마지막 조회 결과·이상 기록 (0건·복수·불일치·모순)")
    private String lastCheckResult;

    protected Payment() {}

    public static Payment request(Long orderId, Long userId, String attemptKey, String provider, Money amount, ZonedDateTime now) {
        return new Payment();
    }

    public void markSending(ZonedDateTime now) {
    }

    public PaymentApplyOutcome closeNotSent(ZonedDateTime now) {
        return null;
    }

    public PaymentApplyOutcome recordAccepted(String transactionKey, ZonedDateTime now) {
        return null;
    }

    public PaymentApplyOutcome recordUnknown(ZonedDateTime now) {
        return null;
    }

    public PaymentApplyOutcome recordRejected(String reason, ZonedDateTime now) {
        return null;
    }

    public PaymentApplyOutcome applyObservation(PaymentObservation observation, ZonedDateTime now) {
        return null;
    }

    public void recordCheck(String result, ZonedDateTime now) {
    }

    public boolean isFinal() {
        return false;
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

    public String getLastCheckResult() {
        return lastCheckResult;
    }
}
