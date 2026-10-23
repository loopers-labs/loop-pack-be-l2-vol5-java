package com.loopers.domain.payment;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.common.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

/**
 * 주문의 PG 결제 시도(Aggregate root). 주문당 한 건이다.
 *
 * <p>식별자의 역할이 달라 네 가지를 따로 보존한다. {@code orderId} 는 주문, {@code attemptKey} 는 고객이 보낸
 * 같은 시도의 구분, {@code providerOrderId} 는 PG 요청·조회에 쓰는 변환된 주문 식별자, {@code transactionKey} 는 PG 가
 * 발급한 거래 키이며 응답을 받기 전에는 없다. 멱등성은 키 문자열이 아니라 세 유일 제약과 상태 전이가 만든다.
 *
 * <p>카드 번호는 저장하지 않는다. 시도에는 금액과 식별 관계만 남긴다.
 */
@Getter
@Entity
@Table(
    name = "payment_attempts",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_attempts_order_id", columnNames = "order_id"),
        @UniqueConstraint(name = "uk_payment_attempts_attempt_key", columnNames = "attempt_key"),
        @UniqueConstraint(name = "uk_payment_attempts_provider_order_id", columnNames = "provider_order_id"),
        @UniqueConstraint(name = "uk_payment_attempts_transaction_key", columnNames = "transaction_key")
    }
)
public class PaymentAttempt extends BaseEntity {

    private static final int MAX_ATTEMPT_KEY_LENGTH = 64;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "attempt_key", nullable = false, length = MAX_ATTEMPT_KEY_LENGTH)
    private String attemptKey;

    @Column(name = "provider_order_id", nullable = false, length = 40)
    private String providerOrderId;

    // 응답을 받기 전에는 null 이다. MySQL 의 유일 제약은 null 을 여러 건 허용한다.
    @Column(name = "transaction_key", length = 64)
    private String transactionKey;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "amount", nullable = false))
    private Money amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentAttemptStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "anomaly", length = 30)
    private PaymentAnomaly anomaly;

    // JPA 가 사용하는 기본 생성자. 새 시도는 request() 로 만든다.
    protected PaymentAttempt() {}

    /** 발송을 시작한다고 기록하는 시도를 만든다. 금액은 요청이 아니라 저장된 주문의 합계다. */
    public static PaymentAttempt request(Long orderId, String attemptKey, Money amount) {
        if (orderId == null) {
            throw new CoreException(ErrorType.INVALID_INPUT, "주문은 필수입니다.");
        }
        if (attemptKey == null || attemptKey.isBlank() || attemptKey.length() > MAX_ATTEMPT_KEY_LENGTH) {
            throw new CoreException(
                ErrorType.INVALID_INPUT, "결제 시도 키는 1자 이상 " + MAX_ATTEMPT_KEY_LENGTH + "자 이하여야 합니다."
            );
        }
        if (amount == null) {
            throw new CoreException(ErrorType.INVALID_INPUT, "결제 금액은 필수입니다.");
        }
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.orderId = orderId;
        attempt.attemptKey = attemptKey;
        attempt.providerOrderId = providerOrderIdOf(orderId);
        attempt.amount = amount;
        attempt.status = PaymentAttemptStatus.REQUESTED;
        return attempt;
    }

    /** 주문 42 를 {@code LP-ORD-0000000042} 로 바꾼다. PG 의 orderId 는 6자 이상 문자열이어야 한다. */
    public static String providerOrderIdOf(long orderId) {
        return String.format("LP-ORD-%010d", orderId);
    }

    /** PG 가 거래를 만들었고 처리 중임을 확인했다. REQUESTED·UNKNOWN 에서만 가능하다. */
    public void markPending(String observedTransactionKey) {
        requireTransactionKey(observedTransactionKey);
        moveTo(PaymentAttemptStatus.PENDING, observedTransactionKey);
    }

    /** 요청은 보냈으나 거래 생성 여부를 확인하지 못했다. REQUESTED 에서만 가능하다. */
    public void markUnknown() {
        moveTo(PaymentAttemptStatus.UNKNOWN, null);
    }

    /** 검증한 SUCCESS 를 반영한다. 종결 상태에서는 불가하다. 이미 아는 거래 키와 다르면 거절한다. */
    public void markSucceeded(String observedTransactionKey) {
        requireTransactionKey(observedTransactionKey);
        moveTo(PaymentAttemptStatus.SUCCEEDED, observedTransactionKey);
    }

    /**
     * 확정 실패를 반영한다. PG 가 FAILED 를 알려 준 경우는 거래 키가 있고, 거래 생성 전 확정 거절은 거래가 없으므로
     * {@code null} 이다. 거래 키를 꾸며서 저장하지 않는다.
     */
    public void markFailed(String observedTransactionKey) {
        moveTo(PaymentAttemptStatus.FAILED, observedTransactionKey);
    }

    /** 이상 징후를 기록한다. 상태와 거래 키는 바꾸지 않는다. */
    public void recordAnomaly(PaymentAnomaly anomaly) {
        if (anomaly == null) {
            throw new CoreException(ErrorType.INVALID_INPUT, "이상 징후 종류는 필수입니다.");
        }
        this.anomaly = anomaly;
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    private void requireTransactionKey(String observedTransactionKey) {
        if (observedTransactionKey == null || observedTransactionKey.isBlank()) {
            throw new CoreException(ErrorType.INVALID_INPUT, "거래 키는 필수입니다.");
        }
    }

    // 거절되면 상태와 거래 키는 그대로다. 검증을 모두 마친 뒤에 값을 바꾼다.
    private void moveTo(PaymentAttemptStatus next, String observedTransactionKey) {
        if (!status.canMoveTo(next)) {
            throw new CoreException(
                ErrorType.CONFLICT, "결제 시도를 " + status + " 에서 " + next + " 로 바꿀 수 없습니다."
            );
        }
        if (observedTransactionKey != null) {
            if (observedTransactionKey.isBlank()) {
                throw new CoreException(ErrorType.INVALID_INPUT, "거래 키는 비어 있을 수 없습니다.");
            }
            if (transactionKey != null && !transactionKey.equals(observedTransactionKey)) {
                throw new CoreException(ErrorType.CONFLICT, "이 시도의 거래와 다른 거래 키입니다.");
            }
            this.transactionKey = observedTransactionKey;
        }
        this.status = next;
    }
}
