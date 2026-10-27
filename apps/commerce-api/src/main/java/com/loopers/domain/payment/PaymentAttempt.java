package com.loopers.domain.payment;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.ZonedDateTime;

/**
 * 주문당 하나인 PG 결제 시도.
 * 세 식별자를 역할별로 나눠 저장한다: attemptKey(고객 멱등 키), providerOrderId(PG 에 보내는 주문 식별자),
 * transactionKey(PG 가 발급한 거래 키). 카드 번호는 저장하지 않는다.
 * 상태 전이는 Repository 의 조건부 UPDATE 가 맡는다 (설계 8-1).
 */
@Getter
@Entity
@Table(
    name = "payment_attempts",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_attempts_order", columnNames = "order_id"),
        @UniqueConstraint(name = "uk_payment_attempts_user_attempt_key", columnNames = {"user_id", "attempt_key"})
    }
)
public class PaymentAttempt extends BaseEntity {

    /**
     * REQUESTED·PENDING·UNKNOWN 은 미종결, SUCCESS·FAILED 는 종결이다.
     */
    public enum Status {
        REQUESTED, PENDING, UNKNOWN, SUCCESS, FAILED;

        public boolean isTerminal() {
            return this == SUCCESS || this == FAILED;
        }
    }

    /**
     * REJECTED_BEFORE_CREATE: 거래 생성 전 확정 거절. PG_FAILED: 조회로 확인된 FAILED.
     */
    public enum FailureReason {
        REJECTED_BEFORE_CREATE, PG_FAILED
    }

    /**
     * 조회 결과가 "정상 1건 일치"가 아닐 때의 마지막 충돌 사유 (설계 7-2). 상태는 바꾸지 않는다.
     * NO_TRANSACTION: callback 입구의 조회 0건. MULTIPLE_TRANSACTIONS: 2건 이상.
     * AMOUNT_MISMATCH·ORDER_MISMATCH: 금액·주문 불일치. TRANSACTION_KEY_MISSING: 알고 있는 transactionKey 가 목록에 없음.
     */
    public enum ConflictReason {
        NO_TRANSACTION, MULTIPLE_TRANSACTIONS, AMOUNT_MISMATCH, ORDER_MISMATCH, TRANSACTION_KEY_MISSING
    }

    private static final String PROVIDER_ORDER_ID_FORMAT = "LP-ORD-%010d";

    private Long orderId;

    private Long userId;

    private String attemptKey;

    private String providerOrderId;

    private String transactionKey;

    private long amount;

    private String cardType;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Enumerated(EnumType.STRING)
    private FailureReason failureReason;

    /**
     * 스케줄러 조회 추적 (설계 5-3 결정 ⑫). 스케줄러가 실제로 PG 에 물어본 횟수만 센다. 사용자 reconcile 은 세지 않는다.
     */
    private int lookupCount;

    private ZonedDateTime nextLookupAt;

    private boolean manualCheckRequired;

    /**
     * 충돌 기록 (설계 7-2). 상태 컬럼과 별개라 종결 여부와 무관하게 남는다.
     */
    private boolean conflicted;

    @Enumerated(EnumType.STRING)
    private ConflictReason conflictReason;

    private ZonedDateTime conflictedAt;

    protected PaymentAttempt() {}

    public PaymentAttempt(Long orderId, Long userId, String attemptKey, String providerOrderId, long amount, String cardType) {
        this.orderId = orderId;
        this.userId = userId;
        this.attemptKey = attemptKey;
        this.providerOrderId = providerOrderId;
        this.amount = amount;
        this.cardType = cardType;
        this.status = Status.REQUESTED;
    }

    /**
     * 내부 주문 42 → "LP-ORD-0000000042". 생성과 조회에 같은 값을 쓰며, 시도 행에 컬럼으로 저장한다.
     */
    public static String providerOrderIdOf(Long orderId) {
        return String.format(PROVIDER_ORDER_ID_FORMAT, orderId);
    }

    public boolean hasAttemptKey(String attemptKey) {
        return this.attemptKey.equals(attemptKey);
    }

    /**
     * "같은 요청" 비교 기준은 금액 + cardType 이다. 카드 번호는 비교하지도 저장하지도 않는다 (설계 4-4).
     * 주문 ID 는 이 시도가 그 주문의 시도라는 사실로 이미 같다.
     */
    public boolean isSameRequest(long amount, String cardType) {
        return this.amount == amount && this.cardType.equals(cardType);
    }
}