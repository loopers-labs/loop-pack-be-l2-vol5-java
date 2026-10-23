package com.loopers.domain.payment;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.List;

/**
 * 주문 하나의 PG 결제 시도 (design.md 9-2·9-3·9-7절). 주문당 하나이고, 카드번호는 저장하지 않는다.
 *
 * <p>식별자를 셋으로 나눠 보존한다. {@code attemptKey}는 우리 쪽 요청 중복 제어, {@code providerOrderId}는 PG의 주문 단위 조회,
 * {@code transactionKey}는 PG 거래 한 건 조회에 쓴다. 주문당 하나(order_id)와 키의 유일성은 DB 유일 제약이 지킨다.
 *
 * <p>상태 전이는 관찰({@link #applyCreate}, {@link #applyLookup})로만 일어난다. 종결(SUCCESS·FAILED)한 시도는 어떤 관찰로도
 * 되돌리거나 덮어쓰지 않는다. 늦게 도착한 생성 응답·timeout도 마찬가지다.
 */
@Entity
@Table(
    name = "payment_attempt",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_attempt_order", columnNames = "order_id"),
        @UniqueConstraint(name = "uk_payment_attempt_key", columnNames = "attempt_key"),
        @UniqueConstraint(name = "uk_payment_attempt_transaction", columnNames = "transaction_key")
    }
)
public class PaymentAttemptModel extends BaseEntity {

    private static final int MAX_KEY_LENGTH = 100;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "attempt_key", nullable = false, updatable = false, length = MAX_KEY_LENGTH)
    private String attemptKey;

    @Column(name = "provider_order_id", nullable = false, updatable = false)
    private String providerOrderId;

    // PG가 거래를 만든 것을 안 뒤에만 채워진다. 거래가 없을 때 키를 꾸며 저장하지 않는다
    @Column(name = "transaction_key")
    private String transactionKey;

    @Column(name = "amount", nullable = false, updatable = false)
    private Long amount;

    @Column(name = "card_type", nullable = false, updatable = false)
    private String cardType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentAttemptStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "unknown_reason")
    private UnknownReason unknownReason;

    // 마지막으로 기록한 충돌. 상태를 바꾸지 않는 기록이라, 이후의 올바른 관찰이 종결을 막지는 않는다
    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_reason")
    private PaymentConflict conflict;

    protected PaymentAttemptModel() {
    }

    public PaymentAttemptModel(Long orderId, Long userId, String attemptKey, String cardType, Long amount) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "사용자는 비어있을 수 없습니다.");
        }
        if (attemptKey == null || attemptKey.isBlank() || attemptKey.length() > MAX_KEY_LENGTH) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 시도 키는 비어있을 수 없고 " + MAX_KEY_LENGTH + "자를 넘을 수 없습니다.");
        }
        if (cardType == null || cardType.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "카드 종류는 비어있을 수 없습니다.");
        }
        if (amount == null || amount <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 금액은 0보다 커야 합니다.");
        }
        this.orderId = orderId;
        this.userId = userId;
        this.attemptKey = attemptKey;
        this.providerOrderId = ProviderOrderId.from(orderId);
        this.amount = amount;
        this.cardType = cardType;
        this.status = PaymentAttemptStatus.REQUESTED;
    }

    /**
     * 같은 요청의 반복인지 본다. 카드번호는 저장하지 않아 비교할 수 없으므로 키와 카드 종류까지만 본다 (design.md 9-11절).
     * 주문·사용자는 호출자가 이 시도를 주문으로 찾았다는 사실로 이미 같다.
     */
    public boolean isSameRequest(String attemptKey, String cardType) {
        return this.attemptKey.equals(attemptKey) && this.cardType.equals(cardType);
    }

    /**
     * 거래 생성 요청의 관찰을 적용한다.
     * 접수는 거래 키를 저장하고 PENDING으로, 정확한 422(효과 0건)는 이 시도가 발송 직후(REQUESTED)일 때만 FAILED로,
     * 결과 미확인은 REQUESTED일 때만 UNKNOWN으로 바꾼다. 이미 거래를 안다면 뒤늦은 거절·미확인은 근거로 쓰지 않는다.
     */
    public PaymentEffect applyCreate(PgCreateResult result) {
        if (status.isTerminal()) {
            return PaymentEffect.NONE;
        }
        return switch (result) {
            case PgCreateResult.Accepted accepted -> {
                acceptTransactionKey(accepted.transactionKey());
                yield PaymentEffect.NONE;
            }
            case PgCreateResult.RejectedNoEffect rejected -> {
                if (status != PaymentAttemptStatus.REQUESTED) {
                    yield PaymentEffect.NONE;
                }
                status = PaymentAttemptStatus.FAILED;
                yield PaymentEffect.FAILED;
            }
            case PgCreateResult.Unknown unknown -> {
                if (status == PaymentAttemptStatus.REQUESTED) {
                    status = PaymentAttemptStatus.UNKNOWN;
                    unknownReason = unknown.reason();
                }
                yield PaymentEffect.NONE;
            }
        };
    }

    /**
     * 거래 조회의 관찰을 적용한다. 0건은 거래가 영원히 없다는 증거가 아니므로 아무것도 바꾸지 않는다.
     * 2건 이상이거나 주문·금액·거래 키가 이 시도와 다르면 상태를 바꾸지 않고 충돌만 기록한다. 첫 거래를 고르지 않는다.
     * 1건이 일치하면 PENDING은 처리 중으로, SUCCESS·FAILED는 종결로 바꾼다.
     */
    public PaymentEffect applyLookup(List<PgTransaction> transactions) {
        if (transactions.isEmpty()) {
            return PaymentEffect.NONE;
        }
        if (transactions.size() > 1) {
            conflict = PaymentConflict.MULTIPLE_TRANSACTIONS;
            return PaymentEffect.NONE;
        }
        PgTransaction found = transactions.get(0);
        PaymentConflict mismatch = mismatchOf(found);
        if (mismatch != null) {
            conflict = mismatch;
            return PaymentEffect.NONE;
        }
        if (status.isTerminal()) {
            recordOppositeFinalResult(found);
            return PaymentEffect.NONE;
        }
        transactionKey = found.transactionKey();
        return switch (found.status()) {
            case PENDING -> {
                markPending();
                yield PaymentEffect.NONE;
            }
            case SUCCESS -> {
                finish(PaymentAttemptStatus.SUCCESS);
                yield PaymentEffect.SUCCEEDED;
            }
            case FAILED -> {
                finish(PaymentAttemptStatus.FAILED);
                yield PaymentEffect.FAILED;
            }
        };
    }

    private void acceptTransactionKey(String key) {
        if (transactionKey != null && !transactionKey.equals(key)) {
            conflict = PaymentConflict.TRANSACTION_KEY_MISMATCH;
            return;
        }
        transactionKey = key;
        markPending();
    }

    private void markPending() {
        if (status == PaymentAttemptStatus.REQUESTED || status == PaymentAttemptStatus.UNKNOWN) {
            status = PaymentAttemptStatus.PENDING;
            unknownReason = null;
        }
    }

    private void finish(PaymentAttemptStatus terminal) {
        status = terminal;
        unknownReason = null;
    }

    private PaymentConflict mismatchOf(PgTransaction found) {
        if (!providerOrderId.equals(found.providerOrderId())) {
            return PaymentConflict.ORDER_MISMATCH;
        }
        if (found.amount() != amount) {
            return PaymentConflict.AMOUNT_MISMATCH;
        }
        if (transactionKey != null && !transactionKey.equals(found.transactionKey())) {
            return PaymentConflict.TRANSACTION_KEY_MISMATCH;
        }
        return null;
    }

    // 종결 뒤에 도착한 처리 중(PENDING) 관찰은 더 오래된 것일 수 있어 충돌이 아니다. 성공과 실패가 서로 반대일 때만 기록한다
    private void recordOppositeFinalResult(PgTransaction found) {
        boolean opposite = (status == PaymentAttemptStatus.SUCCESS && found.status() == PgTransactionStatus.FAILED)
            || (status == PaymentAttemptStatus.FAILED && found.status() == PgTransactionStatus.SUCCESS);
        if (opposite) {
            conflict = PaymentConflict.TERMINAL_RESULT_MISMATCH;
        }
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

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public String getTransactionKey() {
        return transactionKey;
    }

    public Long getAmount() {
        return amount;
    }

    public String getCardType() {
        return cardType;
    }

    public PaymentAttemptStatus getStatus() {
        return status;
    }

    public UnknownReason getUnknownReason() {
        return unknownReason;
    }

    public PaymentConflict getConflict() {
        return conflict;
    }
}
