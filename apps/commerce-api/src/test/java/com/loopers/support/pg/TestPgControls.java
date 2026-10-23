package com.loopers.support.pg;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 테스트가 거는 실패 통제의 상태다 (docs/week5/pg-contract.md 8-2절).
 * 테스트 스레드가 통제를 걸고 서버의 처리 스레드가 꺼내 쓰므로, 동기화를 이 클래스에 모은다.
 */
final class TestPgControls {

    /** 응답을 붙잡는 최대 시간. {@link #releaseHeld()}를 부르지 않아도 이 시간이 지나면 풀린다. */
    static final Duration MAX_HOLD = Duration.ofSeconds(5);

    /** 형식 검사를 통과한 다음 생성 요청 한 번에만 적용하는 통제. */
    sealed interface CreateControl {
        /** 거래를 저장하지 않고 계약의 422로 거절한다. */
        record Reject() implements CreateControl {
        }

        /** 정해 준 상태 코드·본문으로 응답한다. createTransaction이면 거래를 저장한 뒤 응답한다. */
        record Respond(int status, String body, boolean createTransaction) implements CreateControl {
        }

        /** 거래를 저장한 뒤 응답을 붙잡는다. */
        record Hold() implements CreateControl {
        }

        /** 거래를 저장한 뒤 응답 없이 연결을 닫는다. */
        record Drop() implements CreateControl {
        }
    }

    private final AtomicReference<CreateControl> nextCreate = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> holdGate = new AtomicReference<>(new CountDownLatch(1));

    private final Object lookupLock = new Object();
    private TestPgLookupFailure persistentLookupFailure;
    private TestPgLookupFailure nextLookupFailure;
    private int remainingLookupFailures;

    /** 이미 걸린 통제가 있으면 겹쳐 걸지 않는다. 한 테스트 안에서 어느 통제가 적용됐는지 헷갈리지 않게 한다. */
    void setNextCreate(CreateControl control) {
        if (!nextCreate.compareAndSet(null, control)) {
            throw new IllegalStateException("다음 생성 요청에 이미 통제가 걸려 있다: " + nextCreate.get());
        }
    }

    /** 걸린 생성 통제를 꺼내고 지운다. 없으면 null이다. */
    CreateControl takeNextCreate() {
        return nextCreate.getAndSet(null);
    }

    void failNextLookups(int count, TestPgLookupFailure failure) {
        if (count <= 0) {
            throw new IllegalArgumentException("실패시킬 조회 수는 1 이상이어야 한다: " + count);
        }
        synchronized (lookupLock) {
            nextLookupFailure = failure;
            remainingLookupFailures = count;
        }
    }

    void failLookups(TestPgLookupFailure failure) {
        synchronized (lookupLock) {
            persistentLookupFailure = failure;
        }
    }

    void healLookups() {
        synchronized (lookupLock) {
            persistentLookupFailure = null;
            nextLookupFailure = null;
            remainingLookupFailures = 0;
        }
    }

    /** 이번 조회에 적용할 실패를 꺼낸다. 횟수가 정해진 실패는 하나 줄인다. 없으면 null이다. */
    TestPgLookupFailure takeLookupFailure() {
        synchronized (lookupLock) {
            if (persistentLookupFailure != null) {
                return persistentLookupFailure;
            }
            if (remainingLookupFailures > 0) {
                remainingLookupFailures--;
                return nextLookupFailure;
            }
            return null;
        }
    }

    /** {@link #releaseHeld()}가 불리거나 {@link #MAX_HOLD}가 지날 때까지 기다린다. */
    void awaitRelease() {
        CountDownLatch gate = holdGate.get();
        try {
            gate.await(MAX_HOLD.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 지금 붙잡힌 응답을 모두 푼다. 이후에 붙잡는 응답은 다시 붙잡힌다. */
    void releaseHeld() {
        holdGate.getAndSet(new CountDownLatch(1)).countDown();
    }

    /** 붙잡은 응답을 풀고 모든 통제를 지운다. */
    void reset() {
        releaseHeld();
        nextCreate.set(null);
        healLookups();
    }
}
