package com.loopers.domain.payment;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.common.EnumNameConverter;
import com.loopers.domain.common.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import org.hibernate.annotations.Check;

import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 주문당 하나인 PG 결제 시도(설계 3.6). 결제 결과와 마지막 확인 정보만 갖고 충돌 상세는 PaymentConflictModel 에 둔다.
 * 물리 FK 는 두지 않으며 주문과의 연결은 같은 Order 잠금 아래 결과 반영 TX 가 검사한다.
 */
@Entity
@Table(
    name = "payment",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_order", columnNames = "order_id"),
        @UniqueConstraint(name = "uk_payment_key", columnNames = "attempt_key"),
        @UniqueConstraint(name = "uk_payment_transaction", columnNames = {"provider", "provider_account", "transaction_key"})
    },
    indexes = @Index(name = "idx_payment_provider_order", columnList = "provider, provider_account, provider_order_id")
)
@Check(name = "ck_payment_amount", constraints = "amount > 0")
@Getter
public class PaymentModel extends BaseEntity {

    public static final String PROVIDER = "TEST_PG";

    private static final Pattern TRANSACTION_KEY = Pattern.compile("[A-Za-z0-9_-]{1,255}");
    private static final String PROVIDER_ORDER_PREFIX = "LP-ORD-";

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    // 키 열은 대소문자를 구분하는 ASCII binary collation 이다. 기본 생성으로는 나오지 않아 열 정의를 적는다.
    @Column(name = "attempt_key", nullable = false, updatable = false,
        columnDefinition = "char(36) character set ascii collate ascii_bin")
    private String attemptKey;

    @Column(name = "provider", nullable = false, updatable = false,
        columnDefinition = "varchar(16) character set ascii collate ascii_bin")
    private String provider;

    @Column(name = "provider_account", nullable = false, updatable = false,
        columnDefinition = "varchar(32) character set ascii collate ascii_bin")
    private String providerAccount;

    @Column(name = "provider_order_id", nullable = false, updatable = false,
        columnDefinition = "varchar(32) character set ascii collate ascii_bin")
    private String providerOrderId;

    @Column(name = "transaction_key", columnDefinition = "varchar(255) character set ascii collate ascii_bin")
    private String transactionKey;

    @Embedded
    @AttributeOverride(name = "won", column = @Column(name = "amount", nullable = false, updatable = false))
    private Money amount;

    @Convert(converter = StatusConverter.class)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentStatus status;

    @Convert(converter = FailureKindConverter.class)
    @Column(name = "failure_kind", length = 32)
    private PaymentFailureKind failureKind;

    /** TX A 의 발송 권한 소비 시각. 실제 HTTP 전달 완료의 증거가 아니다. */
    @Column(name = "dispatch_claimed_at", nullable = false, updatable = false)
    private ZonedDateTime dispatchClaimedAt;

    @Convert(converter = VerificationStatusConverter.class)
    @Column(name = "verification_status", nullable = false, length = 16)
    private VerificationStatus verificationStatus;

    /** 마지막으로 반영한 확인의 처리 시각. 표시용이며 덮어쓰기 순서 판정에 쓰지 않는다. */
    @Column(name = "verification_checked_at")
    private ZonedDateTime verificationCheckedAt;

    protected PaymentModel() {}

    private PaymentModel(Long orderId, String attemptKey, String providerAccount, Money amount,
                         ZonedDateTime dispatchClaimedAt) {
        this.orderId = orderId;
        this.attemptKey = attemptKey;
        this.provider = PROVIDER;
        this.providerAccount = providerAccount;
        this.providerOrderId = providerOrderIdOf(orderId);
        this.amount = amount;
        this.status = PaymentStatus.UNKNOWN;
        this.dispatchClaimedAt = dispatchClaimedAt;
        this.verificationStatus = VerificationStatus.NOT_CHECKED;
    }

    /** TX A 에서 발송 권한을 소비하며 만든다. 금액은 저장 주문 총액의 양수 값이다. */
    public static PaymentModel start(Long orderId, String attemptKey, String providerAccount, Money amount,
                                     ZonedDateTime dispatchClaimedAt) {
        if (orderId == null || orderId <= 0) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "결제 주문 ID 는 양수여야 합니다.");
        }
        if (providerAccount == null || providerAccount.isBlank()) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "PG 제공자 계정 필수");
        }
        if (amount == null || amount.toWon() <= 0) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "결제 금액은 양수여야 합니다.");
        }
        if (dispatchClaimedAt == null) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "발송 권한 소비 시각 필수");
        }
        return new PaymentModel(orderId, normalizeAttemptKey(attemptKey), providerAccount, amount, dispatchClaimedAt);
    }

    /** UUID 만 값으로 파싱해 소문자 표준 형식으로 바꾼다. 다른 형식은 입력 오류다. */
    public static String normalizeAttemptKey(String attemptKey) {
        if (attemptKey == null) {
            throw new CoreException(ErrorType.INVALID_REQUEST, "paymentAttemptKey 필수");
        }
        try {
            String normalized = UUID.fromString(attemptKey).toString();
            if (!normalized.equalsIgnoreCase(attemptKey)) {
                throw new IllegalArgumentException("표준 UUID 형식이 아님");
            }
            return normalized;
        } catch (IllegalArgumentException e) {
            throw new CoreException(ErrorType.INVALID_REQUEST, "paymentAttemptKey 는 UUID 여야 합니다.");
        }
    }

    /** 내부 양수 주문 ID 를 최소 10자리로 채워 붙인다. 자릿수를 넘어도 자르지 않는다. 예: 42 → LP-ORD-0000000042. */
    public static String providerOrderIdOf(long orderId) {
        if (orderId <= 0) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "외부 주문 ID 는 양수 주문에만 만든다.");
        }
        return PROVIDER_ORDER_PREFIX + String.format("%010d", orderId);
    }

    /**
     * 관찰을 판정해 결제·확인 정보만 바꾸고 판정을 돌려준다(설계 3.6 판정 순서, 4.3 applyObservation).
     * 업무 충돌 코드는 복수 거래 → 거래 식별 → 금액 순서이고, 종결 비교는 식별·금액을 통과한 같은 거래에만 한다.
     * 주문 전이·재고 반환·충돌 요약 저장은 호출한 결과 반영 TX 가 판정에 따라 한다.
     */
    public ObservationDecision recordObservation(PaymentCheckToken token, PaymentObservation observation,
                                                 ZonedDateTime checkedAt) {
        requireMatching(token, observation);
        return switch (observation) {
            case PaymentObservation.Unavailable unavailable -> recordUnavailable(unavailable, checkedAt);
            case PaymentObservation.LocalNotDispatched notDispatched -> recordNotDispatched(notDispatched);
            case PaymentObservation.RejectedNoEffect rejected -> recordRejected(rejected, checkedAt);
            case PaymentObservation.Conflict conflict -> recordAdapterConflict(conflict, checkedAt);
            case PaymentObservation.TransactionObserved transaction -> recordTransaction(token, transaction, checkedAt);
        };
    }

    /**
     * 같은 외부 거래가 다른 결제에 이미 결합돼 결과 반영 TX 가 unique 위반으로 rollback 된 뒤, 새 기록 TX 에서 부른다(설계 4.3).
     * 업무 상태·저장 키·재고는 그대로 두고 확인 정보만 이 관찰의 CONFLICT 로 바꾼다.
     */
    public ObservationDecision recordTransactionAlreadyBound(PaymentCheckToken token,
                                                            PaymentObservation.TransactionObserved transaction,
                                                            ZonedDateTime checkedAt) {
        requireMatching(token, transaction);
        return conflict(PaymentConflictCode.TRANSACTION_ALREADY_BOUND, ConflictEvidence.of(transaction), checkedAt);
    }

    /** 미결합이면 검증한 키를 결합하고 같은 키면 그대로 둔다. 다른 키는 recordObservation 이 먼저 식별 충돌로 걸러야 한다. */
    public void bindTransaction(String key) {
        if (key == null || !TRANSACTION_KEY.matcher(key).matches()) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "거래 키 형식 오류");
        }
        if (transactionKey == null) {
            this.transactionKey = key;
            return;
        }
        if (!transactionKey.equals(key)) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "결합한 거래 키를 바꿀 수 없습니다.");
        }
    }

    private ObservationDecision recordUnavailable(PaymentObservation.Unavailable unavailable, ZonedDateTime checkedAt) {
        // 종결 뒤의 늦은 생성 확인 불가는 거래를 확인할 수 없는 관찰이라 확인 정보도 바꾸지 않는다.
        if (status.isTerminal() && unavailable.source() == ObservationSource.CREATE) {
            return ObservationDecision.of(ObservationDecision.Effect.FINAL_UNCHANGED);
        }
        verify(VerificationStatus.UNAVAILABLE, checkedAt);
        return ObservationDecision.of(ObservationDecision.Effect.UNAVAILABLE);
    }

    /** PG 확인 결과가 아니므로 확인 정보는 그대로 둔다. */
    private ObservationDecision recordNotDispatched(PaymentObservation.LocalNotDispatched notDispatched) {
        PaymentFailureKind kind = PaymentFailureKind.of(notDispatched.reason());
        if (status == PaymentStatus.FAILED && failureKind == kind && transactionKey == null) {
            return ObservationDecision.of(ObservationDecision.Effect.FINAL_UNCHANGED);
        }
        if (transactionKey != null || status.isTerminal()) {
            // 로컬 미발송은 PG 확인이 아니므로 충돌이어도 확인 정보는 바꾸지 않는다.
            return ObservationDecision.conflict(PaymentConflictCode.TERMINAL_MISMATCH, ConflictEvidence.none());
        }
        fail(kind);
        return ObservationDecision.of(ObservationDecision.Effect.RELEASE);
    }

    private ObservationDecision recordRejected(PaymentObservation.RejectedNoEffect rejected, ZonedDateTime checkedAt) {
        ConflictEvidence evidence = new ConflictEvidence(rejected.providerOrderId(), null, rejected.amount(), null, null);
        if (!providerOrderId.equals(rejected.providerOrderId())) {
            return conflict(PaymentConflictCode.IDENTITY_MISMATCH, evidence, checkedAt);
        }
        if (rejected.amount() != amount.toWon()) {
            return conflict(PaymentConflictCode.AMOUNT_MISMATCH, evidence, checkedAt);
        }
        if (status == PaymentStatus.FAILED && failureKind == PaymentFailureKind.REJECTED_NO_EFFECT && transactionKey == null) {
            verify(VerificationStatus.VERIFIED, checkedAt);
            return ObservationDecision.of(ObservationDecision.Effect.FINAL_UNCHANGED);
        }
        // 효과 0건 거절과 결합 거래·다른 종결은 함께 참일 수 없다.
        if (transactionKey != null || status.isTerminal()) {
            return conflict(PaymentConflictCode.TERMINAL_MISMATCH, evidence, checkedAt);
        }
        fail(PaymentFailureKind.REJECTED_NO_EFFECT);
        verify(VerificationStatus.VERIFIED, checkedAt);
        return ObservationDecision.of(ObservationDecision.Effect.RELEASE);
    }

    /**
     * adapter 는 MULTIPLE_TRANSACTIONS·IDENTITY_MISMATCH·AMOUNT_MISMATCH 만 만든다. 저장 키·외부 주문 ID 와 비교해 앞 단계인
     * 식별 불일치가 보이면 그 코드로 올리고, 뒤 단계 코드로 내리지는 않는다. 복수 거래는 가장 앞 단계라 그대로다.
     */
    private ObservationDecision recordAdapterConflict(PaymentObservation.Conflict conflict, ZonedDateTime checkedAt) {
        ConflictEvidence evidence = conflict.evidence();
        PaymentConflictCode code = conflict.code();
        if (code == PaymentConflictCode.AMOUNT_MISMATCH && identityMismatch(evidence)) {
            code = PaymentConflictCode.IDENTITY_MISMATCH;
        }
        return conflict(code, evidence, checkedAt);
    }

    private ObservationDecision recordTransaction(PaymentCheckToken token, PaymentObservation.TransactionObserved transaction,
                                                  ZonedDateTime checkedAt) {
        ConflictEvidence evidence = ConflictEvidence.of(transaction);
        boolean otherTransaction = transactionKey != null && !transactionKey.equals(transaction.transactionKey());
        if (!providerOrderId.equals(transaction.providerOrderId()) || otherTransaction) {
            return conflict(PaymentConflictCode.IDENTITY_MISMATCH, evidence, checkedAt);
        }
        if (transaction.amount() != amount.toWon()) {
            return conflict(PaymentConflictCode.AMOUNT_MISMATCH, evidence, checkedAt);
        }
        // 효과 0건·로컬 미발송으로 종결했는데 같은 주문의 유효 거래가 보이면 모순이다.
        if (failureKind != null && failureKind.hasNoExternalEffect()) {
            return conflict(PaymentConflictCode.TERMINAL_MISMATCH, evidence, checkedAt);
        }
        if (status.isTerminal()) {
            return recordAfterTerminal(token, transaction, evidence, checkedAt);
        }
        bindTransaction(transaction.transactionKey());
        verify(VerificationStatus.VERIFIED, checkedAt);
        return switch (transaction.status()) {
            case SUCCESS -> {
                this.status = PaymentStatus.SUCCESS;
                yield ObservationDecision.of(ObservationDecision.Effect.CONFIRM);
            }
            case FAILED -> {
                fail(PaymentFailureKind.PG_FAILED);
                yield ObservationDecision.of(ObservationDecision.Effect.RELEASE);
            }
            case PENDING -> {
                this.status = PaymentStatus.PENDING;
                yield ObservationDecision.of(ObservationDecision.Effect.PENDING);
            }
        };
    }

    private ObservationDecision recordAfterTerminal(PaymentCheckToken token, PaymentObservation.TransactionObserved transaction,
                                                    ConflictEvidence evidence, ZonedDateTime checkedAt) {
        if (transaction.status() == PgTransactionStatus.PENDING) {
            // 생성의 늦은 PENDING 과 종결 전에 준비한 조회의 PENDING 은 늦은 관찰이라 무시한다.
            if (transaction.source() == ObservationSource.CREATE || !token.terminalAtStart()) {
                return ObservationDecision.of(ObservationDecision.Effect.FINAL_UNCHANGED);
            }
            return conflict(PaymentConflictCode.TERMINAL_REGRESSION, evidence, checkedAt);
        }
        boolean sameTerminal = (transaction.status() == PgTransactionStatus.SUCCESS && status == PaymentStatus.SUCCESS)
            || (transaction.status() == PgTransactionStatus.FAILED && status == PaymentStatus.FAILED);
        if (sameTerminal) {
            verify(VerificationStatus.VERIFIED, checkedAt);
            return ObservationDecision.of(ObservationDecision.Effect.FINAL_UNCHANGED);
        }
        return conflict(PaymentConflictCode.TERMINAL_MISMATCH, evidence, checkedAt);
    }

    private boolean identityMismatch(ConflictEvidence evidence) {
        boolean otherOrder = evidence.observedOrderId() != null && !providerOrderId.equals(evidence.observedOrderId());
        boolean otherKey = transactionKey != null && evidence.observedTransactionKey() != null
            && !transactionKey.equals(evidence.observedTransactionKey());
        return otherOrder || otherKey;
    }

    private ObservationDecision conflict(PaymentConflictCode code, ConflictEvidence evidence, ZonedDateTime checkedAt) {
        verify(VerificationStatus.CONFLICT, checkedAt);
        return ObservationDecision.conflict(code, evidence);
    }

    private void fail(PaymentFailureKind kind) {
        this.status = PaymentStatus.FAILED;
        this.failureKind = kind;
    }

    private void verify(VerificationStatus verification, ZonedDateTime checkedAt) {
        if (checkedAt == null) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "확인 시각 필수");
        }
        this.verificationStatus = verification;
        this.verificationCheckedAt = checkedAt;
    }

    public static class StatusConverter extends EnumNameConverter<PaymentStatus> {
        public StatusConverter() {
            super(PaymentStatus.class);
        }
    }

    public static class FailureKindConverter extends EnumNameConverter<PaymentFailureKind> {
        public FailureKindConverter() {
            super(PaymentFailureKind.class);
        }
    }

    public static class VerificationStatusConverter extends EnumNameConverter<VerificationStatus> {
        public VerificationStatusConverter() {
            super(VerificationStatus.class);
        }
    }

    private void requireMatching(PaymentCheckToken token, PaymentObservation observation) {
        if (token == null || observation == null) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "확인 토큰·관찰 필수");
        }
        if (!getId().equals(token.paymentId()) || !orderId.equals(token.orderId())) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "확인 토큰이 이 결제와 맞지 않습니다.");
        }
        if (token.source() != observation.source()) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "확인 토큰과 관찰의 출처가 다릅니다.");
        }
    }
}
