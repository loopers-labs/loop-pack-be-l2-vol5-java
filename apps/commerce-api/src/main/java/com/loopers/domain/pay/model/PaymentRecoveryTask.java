package com.loopers.domain.pay.model;

import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.Getter;

@Getter
public final class PaymentRecoveryTask {
    public enum State { READY, RUNNING, MANUAL_REQUIRED, APPLIED }
    public enum Reason { NONE, RETRY_EXHAUSTED, LOOKUP_UNAVAILABLE, PENDING, IDENTITY_CONFLICT, APPLICATION_FAILED,
        CIRCUIT_BLOCKED, LOOKUP_CONTRACT_ERROR, LOOKUP_REQUEST_ERROR, TIME_BUDGET_EXHAUSTED,
        EXECUTOR_REJECTED, DISPATCH_UNCERTAIN, RECOVERY_STATE_ERROR, RECOVERY_INTERRUPTED }

    private final String attemptKey;
    private final State state;
    private final String executionId;
    private final int usedRounds;
    // 조회 시작 직전 시각이다. 수동 조회는 자동 회차를 소비하지 않는다.
    private final Instant lastCheckedAt;
    private final Reason lastReason;
    private final Instant updatedAt;

    private PaymentRecoveryTask(String key, State state, String executionId, int usedRounds,
                                Instant lastCheckedAt, Reason lastReason, Instant updatedAt) {
        this.attemptKey = PaymentAttempt.normalizeKey(key);
        this.state = Objects.requireNonNull(state);
        this.executionId = executionId == null ? null : PaymentAttempt.normalizeKey(executionId);
        if (usedRounds < 0 || usedRounds > 4 || (state == State.READY && (executionId != null || usedRounds != 0))
                || ((state == State.RUNNING || state == State.MANUAL_REQUIRED) && executionId == null)
                || (usedRounds > 0 && lastCheckedAt == null)) {
            throw new IllegalArgumentException("복구 작업의 실행·사용 횟수가 올바르지 않습니다.");
        }
        this.usedRounds = usedRounds;
        this.lastCheckedAt = lastCheckedAt;
        this.lastReason = Objects.requireNonNull(lastReason);
        this.updatedAt = Objects.requireNonNull(updatedAt).truncatedTo(ChronoUnit.MICROS);
    }

    public static PaymentRecoveryTask create(String key) {
        return new PaymentRecoveryTask(key, State.READY, null, 0, null, Reason.NONE, Instant.now());
    }

    public static PaymentRecoveryTask restore(String key, State state, String executionId, int usedRounds,
                                              Instant lastCheckedAt, Reason lastReason, Instant updatedAt) {
        return new PaymentRecoveryTask(key, state, executionId, usedRounds, lastCheckedAt, lastReason, updatedAt);
    }

    public PaymentRecoveryTask claim(String id, Instant now) {
        if (state != State.READY) {
            throw invalidProgress();
        }
        return new PaymentRecoveryTask(attemptKey, State.RUNNING, id, usedRounds, lastCheckedAt, lastReason, now);
    }

    public PaymentRecoveryTask handover(String nextId, Instant now) {
        if ((state != State.RUNNING && state != State.MANUAL_REQUIRED)
                || Objects.equals(executionId, PaymentAttempt.normalizeKey(nextId))) {
            throw invalidProgress();
        }
        return new PaymentRecoveryTask(attemptKey, State.RUNNING, nextId, usedRounds, lastCheckedAt, lastReason, now);
    }

    public PaymentRecoveryTask consumeRound(String id, Instant now) {
        ensureOwner(id);
        if (usedRounds >= 4) {
            throw invalidProgress();
        }
        return new PaymentRecoveryTask(attemptKey, state, executionId, usedRounds + 1,
                now.truncatedTo(ChronoUnit.MICROS), lastReason, now);
    }

    public PaymentRecoveryTask requireManual(String id, Reason reason, Instant now) {
        ensureOwner(id);
        if (reason == null || reason == Reason.NONE) {
            throw new IllegalArgumentException("수동 확인 사유가 필요합니다.");
        }
        return new PaymentRecoveryTask(attemptKey, State.MANUAL_REQUIRED, executionId, usedRounds, lastCheckedAt, reason, now);
    }

    // 명시적인 수동 조회는 자동 예산을 소비하지 않는다.
    public PaymentRecoveryTask startManualLookup(String id, Instant now) {
        ensureOwner(id);
        return new PaymentRecoveryTask(attemptKey, state, executionId, usedRounds,
            now.truncatedTo(ChronoUnit.MICROS), lastReason, now);
    }

    public PaymentRecoveryTask applied(Instant now) {
        return state == State.APPLIED ? this
                : new PaymentRecoveryTask(attemptKey, State.APPLIED, executionId, usedRounds, lastCheckedAt, lastReason, now);
    }

    public Snapshot snapshot() {
        return new Snapshot(attemptKey, state, executionId, usedRounds);
    }

    /** 업무 시도 직전 확보한 비교값. rollback 뒤 최신 소유자로 교체하지 않는다. */
    public record Snapshot(String attemptKey, State state, String executionId, int usedRounds) {}

    private void ensureOwner(String id) {
        if (state != State.RUNNING || !Objects.equals(executionId, PaymentAttempt.normalizeKey(id))) {
            throw invalidProgress();
        }
    }

    private DomainException invalidProgress() {
        return new DomainException(DomainErrorCode.PAYMENT_RECOVERY_NOT_ALLOWED);
    }
}
