package com.loopers.domain.pay.model;

import com.loopers.domain.shared.Money;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/** 결제 사실만 소유한다. 조회 횟수와 업무 반영 진행은 별도 복구 작업의 책임이다. */
@Getter
public final class PaymentAttempt {
    public enum DispatchState { READY, STARTED }
    public enum Outcome { UNKNOWN, PENDING, SUCCESS, FAILED }
    public enum Rejection { NONE, NO_EFFECT }
    public enum Conflict { NONE, IDENTITY_MISMATCH, EXISTENCE_CONFLICT, TERMINAL_CONFLICT }

    private final String attemptKey;
    private final long orderId;
    private final long amount;
    private final String providerOrderId;
    private final PaymentScenario scenario;
    private final DispatchState dispatchState;
    private final Outcome outcome;
    private final String transactionKey;
    private final Rejection rejection;
    private final Conflict conflict;
    private final long version;
    private final Instant createdAt;

    private PaymentAttempt(String attemptKey, long orderId, long amount, String providerOrderId, PaymentScenario scenario,
                           DispatchState dispatchState, Outcome outcome, String transactionKey,
                           Rejection rejection, Conflict conflict, long version, Instant createdAt) {
        this.attemptKey = normalizeKey(attemptKey);
        if (orderId <= 0 || version < 0 || providerOrderId == null
                || providerOrderId.length() < 6 || providerOrderId.length() > 100) {
            throw new IllegalArgumentException("결제 시도 식별 정보가 올바르지 않습니다.");
        }
        this.orderId = orderId;
        this.amount = Money.positive(amount).getValue();
        this.providerOrderId = providerOrderId;
        this.scenario = Objects.requireNonNull(scenario);
        this.dispatchState = Objects.requireNonNull(dispatchState);
        this.outcome = Objects.requireNonNull(outcome);
        this.rejection = Objects.requireNonNull(rejection);
        this.conflict = Objects.requireNonNull(conflict);
        this.createdAt = Objects.requireNonNull(createdAt);
        boolean hasKey = transactionKey != null && !transactionKey.isBlank() && transactionKey.length() <= 64;
        if ((transactionKey != null && !hasKey) || (outcome == Outcome.UNKNOWN && transactionKey != null)
                || (rejection == Rejection.NO_EFFECT && (outcome != Outcome.FAILED || transactionKey != null))
                || (outcome != Outcome.UNKNOWN && rejection == Rejection.NONE && !hasKey)) {
            throw new IllegalArgumentException("결제 결과와 거래·거절 근거가 일치하지 않습니다.");
        }
        this.transactionKey = transactionKey;
        this.version = version;
    }

    public static PaymentAttempt create(long orderId, String key, long amount, PaymentScenario scenario) {
        return new PaymentAttempt(key, orderId, amount, String.format(Locale.ROOT, "LP-ORD-%010d", orderId), scenario,
                DispatchState.READY, Outcome.UNKNOWN, null, Rejection.NONE, Conflict.NONE, 0,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    public static PaymentAttempt restore(String key, long orderId, long amount, String providerOrderId, PaymentScenario scenario,
                                         DispatchState dispatchState, Outcome outcome, String transactionKey,
                                         Rejection rejection, Conflict conflict, long version, Instant createdAt) {
        return new PaymentAttempt(key, orderId, amount, providerOrderId, scenario, dispatchState, outcome,
                transactionKey, rejection, conflict, version, createdAt);
    }

    public static String normalizeKey(String key) {
        if (key == null || !key.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("결제 시도 키는 공백 없는 표준 UUID여야 합니다.");
        }
        return UUID.fromString(key).toString();
    }

    public boolean isSameRequest(long requestedOrderId, String key, long requestedAmount, PaymentScenario requestedScenario) {
        return orderId == requestedOrderId && attemptKey.equals(normalizeKey(key))
                && amount == requestedAmount && scenario.equals(requestedScenario);
    }

    public PaymentAttempt startDispatch() {
        if (dispatchState != DispatchState.READY || outcome != Outcome.UNKNOWN || conflict != Conflict.NONE) {
            throw new DomainException(DomainErrorCode.PAYMENT_DISPATCH_NOT_ALLOWED);
        }
        return copy(DispatchState.STARTED, outcome, transactionKey, rejection, conflict);
    }

    /** 검증된 거래 관찰만 전달한다. timeout/빈 조회를 UNKNOWN으로 덮어쓰는 메서드는 두지 않는다. */
    public PaymentAttempt observe(String observedOrderId, long observedAmount, String key, Outcome observed) {
        if (observed == null || observed == Outcome.UNKNOWN || key == null || key.isBlank() || key.length() > 64) {
            throw new IllegalArgumentException("검증한 거래 결과가 필요합니다.");
        }
        if (!providerOrderId.equals(observedOrderId) || amount != observedAmount
                || (transactionKey != null && !transactionKey.equals(key))) {
            return noteConflict(Conflict.IDENTITY_MISMATCH);
        }
        if (rejection == Rejection.NO_EFFECT) {
            return noteConflict(Conflict.EXISTENCE_CONFLICT);
        }
        if (outcome == Outcome.SUCCESS || outcome == Outcome.FAILED) {
            return observed == Outcome.PENDING || observed == outcome ? this : noteConflict(Conflict.TERMINAL_CONFLICT);
        }
        return copy(dispatchState, observed, key, rejection, conflict);
    }

    /** 정확한 생성 전 거절의 식별·금액 검증을 마친 뒤 호출한다. */
    public PaymentAttempt rejectBeforeCreate(String observedOrderId, long observedAmount) {
        if (!providerOrderId.equals(observedOrderId) || amount != observedAmount) {
            return noteConflict(Conflict.IDENTITY_MISMATCH);
        }
        if (transactionKey != null) {
            return noteConflict(Conflict.EXISTENCE_CONFLICT);
        }
        if (rejection == Rejection.NO_EFFECT) {
            return this;
        }
        return copy(dispatchState, Outcome.FAILED, null, Rejection.NO_EFFECT, conflict);
    }

    public PaymentAttempt noteConflict(Conflict reason) {
        if (reason == null || reason == Conflict.NONE) {
            throw new IllegalArgumentException("충돌 사유가 필요합니다.");
        }
        return conflict != Conflict.NONE ? this : copy(dispatchState, outcome, transactionKey, rejection, reason);
    }

    private PaymentAttempt copy(DispatchState dispatch, Outcome result, String key, Rejection rejected, Conflict conflicting) {
        return new PaymentAttempt(attemptKey, orderId, amount, providerOrderId, scenario, dispatch, result, key,
                rejected, conflicting, version, createdAt);
    }
}
