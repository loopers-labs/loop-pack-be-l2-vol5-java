package com.loopers.application.ordering.support;

import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import java.util.concurrent.TimeUnit;

/** 한 자동 실행의 메모리 정보다. DB 소유권·회차를 대신하거나 재시작 뒤 재사용하지 않는다. */
public final class AutomaticPaymentExecution {
    private static final long BUDGET_NANOS = TimeUnit.SECONDS.toNanos(30);
    private final long startedNanos;
    private volatile PaymentRecoveryTask.Snapshot snapshot;
    private volatile PaymentRecoveryTask.Snapshot consuming;
    private volatile boolean budgetStopped;

    public AutomaticPaymentExecution(PaymentRecoveryTask.Snapshot snapshot, long startedNanos) {
        if (snapshot == null || snapshot.state() != PaymentRecoveryTask.State.RUNNING
                || snapshot.usedRounds() < 0 || snapshot.usedRounds() > 4) {
            throw new IllegalArgumentException("자동 실행의 RUNNING 정보가 필요합니다.");
        }
        PaymentAttempt.normalizeKey(snapshot.attemptKey());
        PaymentAttempt.normalizeKey(snapshot.executionId());
        this.snapshot = snapshot;
        this.startedNanos = startedNanos;
    }

    public PaymentRecoveryTask.Snapshot snapshot() { return snapshot; }

    public PaymentRecoveryTask.Snapshot beginConsumption() {
        if (consuming != null || snapshot.usedRounds() >= 4) { throw new IllegalStateException("조회 회차를 확보할 수 없습니다."); }
        consuming = new PaymentRecoveryTask.Snapshot(snapshot.attemptKey(), snapshot.state(), snapshot.executionId(),
            snapshot.usedRounds() + 1);
        return consuming;
    }

    public void consumptionCommitted() {
        if (consuming == null) { throw new IllegalStateException("확보 중인 조회 회차가 없습니다."); }
        snapshot = consuming;
        consuming = null;
    }

    /** commit 미확인에도 미리 보유한 비교값만 돌려준다. 읽은 새 소유자의 값을 가져다 쓰지 않는다. */
    public PaymentRecoveryTask.Snapshot snapshotFor(PaymentRecoveryTask.Snapshot actual) {
        var held = snapshot;
        var reserved = consuming;
        if (held.equals(actual)) { return held; }
        return reserved != null && reserved.equals(actual) ? reserved : null;
    }

    public boolean expired(long nowNanos) { return budgetStopped || nowNanos - startedNanos >= BUDGET_NANOS; }

    public long remainingMillis(long nowNanos) {
        return expired(nowNanos) ? 0 : Math.max(0, TimeUnit.NANOSECONDS.toMillis(BUDGET_NANOS - (nowNanos - startedNanos)));
    }

    public long retryDelayMillis(double randomUnit, long nowNanos) {
        if (expired(nowNanos)) { throw new AutomaticPaymentBudgetExceededException(); }
        if (randomUnit < 0 || randomUnit > 1 || snapshot.usedRounds() < 1 || snapshot.usedRounds() > 3) {
            throw new IllegalArgumentException("재조회 대기 계산 값이 올바르지 않습니다.");
        }
        long base = switch (snapshot.usedRounds()) { case 1 -> 1000L; case 2 -> 3000L; default -> 10000L; };
        long requested = Math.round(base * (0.8 + 0.4 * randomUnit));
        long remaining = remainingMillis(nowNanos);
        if (remaining < requested) { budgetStopped = true; }
        return Math.min(requested, remaining);
    }
}
