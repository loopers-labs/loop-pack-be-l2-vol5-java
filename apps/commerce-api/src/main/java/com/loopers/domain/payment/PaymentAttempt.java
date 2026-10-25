package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public class PaymentAttempt {

    private static final String PROVIDER_ORDER_ID_FORMAT = "LP-ORD-%010d-%s";

    private static final Pattern ATTEMPT_KEY_PATTERN =
        Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final Pattern PROVIDER_ORDER_SUFFIX_PATTERN = Pattern.compile("^[0-9a-f]{8}$");

    private final Long id;

    private final String paymentAttemptKey;

    private final Long orderId;

    private final String providerOrderId;

    private String transactionKey;

    private final long amount;

    private PaymentAttemptStatus status;

    private PaymentConflictReason conflictReason;

    private ZonedDateTime conflictDetectedAt;

    private PaymentAttempt(Long id, String paymentAttemptKey, Long orderId, String providerOrderId,
                           String transactionKey, long amount, PaymentAttemptStatus status,
                           PaymentConflictReason conflictReason, ZonedDateTime conflictDetectedAt) {
        this.id = id;
        this.paymentAttemptKey = paymentAttemptKey;
        this.orderId = orderId;
        this.providerOrderId = providerOrderId;
        this.transactionKey = transactionKey;
        this.amount = amount;
        this.status = status;
        this.conflictReason = conflictReason;
        this.conflictDetectedAt = conflictDetectedAt;
    }

    // providerOrderSuffix는 application이 주입한 생성기가 만든 무작위 값이다. 주문 ID가 재사용돼도 PG의 이전 거래와 섞이지 않게 한다.
    public static PaymentAttempt create(String paymentAttemptKey, Long orderId, long amount, String providerOrderSuffix) {
        String normalizedKey = normalizeAttemptKey(paymentAttemptKey);
        if (orderId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 ID는 필수입니다.");
        }
        if (amount <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 금액은 양수여야 합니다.");
        }
        if (providerOrderSuffix == null || !PROVIDER_ORDER_SUFFIX_PATTERN.matcher(providerOrderSuffix).matches()) {
            throw new IllegalArgumentException("PG 주문 식별자의 무작위 값은 소문자 16진수 8자리여야 합니다.");
        }
        return new PaymentAttempt(
            null, normalizedKey, orderId, PROVIDER_ORDER_ID_FORMAT.formatted(orderId, providerOrderSuffix),
            null, amount, PaymentAttemptStatus.REQUESTED, null, null
        );
    }

    public static PaymentAttempt reconstitute(Long id, String paymentAttemptKey, Long orderId, String providerOrderId,
                                              String transactionKey, long amount, PaymentAttemptStatus status,
                                              PaymentConflictReason conflictReason, ZonedDateTime conflictDetectedAt) {
        return new PaymentAttempt(
            id, paymentAttemptKey, orderId, providerOrderId, transactionKey, amount, status,
            conflictReason, conflictDetectedAt
        );
    }

    public static String normalizeAttemptKey(String paymentAttemptKey) {
        if (paymentAttemptKey == null || paymentAttemptKey.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 시도 키는 필수입니다.");
        }
        if (!ATTEMPT_KEY_PATTERN.matcher(paymentAttemptKey).matches()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 시도 키는 UUID 형식이어야 합니다.");
        }
        return paymentAttemptKey.toLowerCase(Locale.ROOT);
    }

    public Long getId() { return id; }
    public String getPaymentAttemptKey() { return paymentAttemptKey; }
    public Long getOrderId() { return orderId; }
    public String getProviderOrderId() { return providerOrderId; }
    public String getTransactionKey() { return transactionKey; }
    public long getAmount() { return amount; }
    public PaymentAttemptStatus getStatus() { return status; }
    public PaymentConflictReason getConflictReason() { return conflictReason; }
    public ZonedDateTime getConflictDetectedAt() { return conflictDetectedAt; }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    // 전이는 결과 반영의 DB 조건과 같은 규칙을 따르고, 상태가 바뀐 경우에만 true를 반환한다.
    // 종결된 시도, 같은 키의 중복, 기록한 키와 다른 키는 예외 없이 무시한다.
    public boolean accept(String transactionKey) {
        requireTransactionKey(transactionKey);
        if (!isAcceptable() || !canRecord(transactionKey)) {
            return false;
        }
        this.transactionKey = transactionKey;
        status = PaymentAttemptStatus.PENDING;
        return true;
    }

    public boolean markUnknown() {
        if (status != PaymentAttemptStatus.REQUESTED || transactionKey != null) {
            return false;
        }
        status = PaymentAttemptStatus.UNKNOWN;
        return true;
    }

    public boolean succeed(String transactionKey) {
        return terminateWith(PaymentAttemptStatus.SUCCEEDED, transactionKey);
    }

    public boolean fail(String transactionKey) {
        return terminateWith(PaymentAttemptStatus.FAILED, transactionKey);
    }

    // 생성 전 확정 거절은 거래가 없으므로 거래 키를 확인하지 않은 시도에만 적용한다.
    public boolean rejectBeforeCreate() {
        if (!isAcceptable() || transactionKey != null) {
            return false;
        }
        status = PaymentAttemptStatus.FAILED;
        return true;
    }

    // 충돌은 판정을 보류하는 기록이라 시도 상태와 거래 키를 바꾸지 않으며, 처음 기록한 사유를 유지한다.
    public boolean recordConflict(PaymentConflictReason reason, ZonedDateTime detectedAt) {
        if (reason == null || detectedAt == null) {
            throw new IllegalArgumentException("충돌 사유와 시각은 필수입니다.");
        }
        if (conflictReason != null) {
            return false;
        }
        conflictReason = reason;
        conflictDetectedAt = detectedAt;
        return true;
    }

    // 조회한 거래가 이 시도의 것인지 확인한다. 다른 주문·금액이거나 기록한 키와 다르면 판정하지 않고 충돌로 남긴다.
    public Optional<PaymentConflictReason> verifyTransaction(String providerOrderId, long amount, String transactionKey) {
        if (!this.providerOrderId.equals(providerOrderId) || this.amount != amount || !canRecord(transactionKey)) {
            return Optional.of(PaymentConflictReason.TRANSACTION_MISMATCH);
        }
        return Optional.empty();
    }

    // 검증을 통과한 거래의 PG 상태(PENDING·SUCCEEDED·FAILED)를 같은 전이 규칙으로 반영한다.
    public boolean observe(String transactionKey, PaymentAttemptStatus observedStatus) {
        return switch (observedStatus) {
            case PENDING -> accept(transactionKey);
            case SUCCEEDED -> succeed(transactionKey);
            case FAILED -> fail(transactionKey);
            case REQUESTED, UNKNOWN -> throw new IllegalArgumentException("PG 거래 상태가 아닙니다: " + observedStatus);
        };
    }

    // 아래 판정은 조건부 갱신이 0행인 관찰을 새로 읽은 시도 기준으로 설명한다.
    // 중복·늦은 관찰은 빈 값, 모순은 충돌 사유이며, 전이할 수 있었는데 갱신되지 않았다면 정합성 오류다.
    public Optional<PaymentConflictReason> explainUnchangedTransaction(String transactionKey,
                                                                       PaymentAttemptStatus observedStatus) {
        if (isRejectedBeforeCreate()) {
            return Optional.of(PaymentConflictReason.RESULT_CONTRADICTION);
        }
        if (this.transactionKey != null && !this.transactionKey.equals(transactionKey)) {
            return Optional.of(PaymentConflictReason.TRANSACTION_MISMATCH);
        }
        if (this.transactionKey != null && observedStatus == PaymentAttemptStatus.PENDING) {
            return Optional.empty();
        }
        if (isTerminal()) {
            return status == observedStatus ? Optional.empty() : Optional.of(PaymentConflictReason.RESULT_CONTRADICTION);
        }
        throw unexplained();
    }

    public Optional<PaymentConflictReason> explainUnchangedRejection() {
        if (isRejectedBeforeCreate()) {
            return Optional.empty();
        }
        if (transactionKey != null) {
            return Optional.of(PaymentConflictReason.RESULT_CONTRADICTION);
        }
        throw unexplained();
    }

    public Optional<PaymentConflictReason> explainUnchangedUnknown() {
        if (status == PaymentAttemptStatus.REQUESTED && transactionKey == null) {
            throw unexplained();
        }
        return Optional.empty();
    }

    public boolean matches(Long orderId, long amount) {
        return Objects.equals(this.orderId, orderId) && this.amount == amount;
    }

    private boolean terminateWith(PaymentAttemptStatus terminalStatus, String transactionKey) {
        requireTransactionKey(transactionKey);
        if (isTerminal() || !canRecord(transactionKey)) {
            return false;
        }
        this.transactionKey = transactionKey;
        status = terminalStatus;
        return true;
    }

    private boolean isRejectedBeforeCreate() {
        return status == PaymentAttemptStatus.FAILED && transactionKey == null;
    }

    private CoreException unexplained() {
        return new CoreException(ErrorType.INTERNAL_ERROR, "결제 시도 상태로 설명되지 않는 결과 반영입니다.");
    }

    private boolean isAcceptable() {
        return status == PaymentAttemptStatus.REQUESTED || status == PaymentAttemptStatus.UNKNOWN;
    }

    private boolean canRecord(String transactionKey) {
        return this.transactionKey == null || this.transactionKey.equals(transactionKey);
    }

    private static void requireTransactionKey(String transactionKey) {
        if (transactionKey == null || transactionKey.isBlank()) {
            throw new IllegalArgumentException("PG 거래 키는 필수입니다.");
        }
    }
}
