package com.loopers.application.order;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * relay 회차·명시 실행·재개를 한 프로세스 안에서 하나씩만 실행한다(설계 W7.3-1). 분산 lock·claim 이 아니다.
 *
 * <p>진입은 기다리지 않는다. 실행 중이면 BUSY, 종료 요청 뒤면 STOPPING 을 돌려주며 작업을 실행하지 않는다.
 * 재진입도 허용하지 않는다. 작업 예외는 그대로 전파하고 해제는 finally 에서 한다. {@link #stop()} 은 새 진입을 막고 진행 thread 에
 * interrupt 를 전달한다. 앱 lifecycle 에서 언제 stop 을 부를지는 이 클래스의 책임이 아니다.
 */
public final class OrderOutboxExecutionGate {

    public enum Outcome {
        EXECUTED,
        BUSY,
        STOPPING
    }

    /** 실행 결과. EXECUTED 만 value 를 가질 수 있고, BUSY·STOPPING 은 작업을 실행하지 않았다는 뜻이다. */
    public record Result<T>(Outcome outcome, Optional<T> value) {

        public Result {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(value, "value");
            if (outcome != Outcome.EXECUTED && value.isPresent()) {
                throw new IllegalArgumentException("실행하지 않은 결과에는 값이 없습니다.");
            }
        }

        static <T> Result<T> notExecuted(Outcome outcome) {
            return new Result<>(outcome, Optional.empty());
        }
    }

    private final Object monitor = new Object();
    private Thread owner;
    private boolean stopping;

    /** 값을 돌려주는 작업. 작업이 null 을 돌려주면 프로그램 오류다. */
    public <T> Result<T> tryExecute(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        Outcome rejected = enter();
        if (rejected != null) {
            return Result.notExecuted(rejected);
        }
        try {
            return new Result<>(Outcome.EXECUTED, Optional.of(Objects.requireNonNull(action.get(), "action result")));
        } finally {
            release();
        }
    }

    /** 값이 없는 작업. EXECUTED 는 빈 value 다. */
    public Result<Void> tryRun(Runnable action) {
        Objects.requireNonNull(action, "action");
        Outcome rejected = enter();
        if (rejected != null) {
            return Result.notExecuted(rejected);
        }
        try {
            action.run();
            return new Result<>(Outcome.EXECUTED, Optional.empty());
        } finally {
            release();
        }
    }

    /** 종료 관측용. 상태를 바꾸지 않는다. */
    public boolean isStopping() {
        synchronized (monitor) {
            return stopping;
        }
    }

    /** 새 진입을 STOPPING 으로 막고 진행 중인 thread 에 interrupt 를 전달한다. 여러 번 불러도 안전하다. */
    public void stop() {
        synchronized (monitor) {
            stopping = true;
            if (owner != null) {
                owner.interrupt();
            }
        }
    }

    /** 진행 중인 작업의 finally 해제를 timeout 안에서 기다린다. 해제됐으면 true, 시간 안에 끝나지 않았으면 false 다. */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (monitor) {
            while (owner != null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }
                monitor.wait(Math.max(1L, remaining / 1_000_000L));
            }
            return true;
        }
    }

    private Outcome enter() {
        synchronized (monitor) {
            if (stopping) {
                return Outcome.STOPPING;
            }
            if (owner != null) {
                return Outcome.BUSY;
            }
            owner = Thread.currentThread();
            return null;
        }
    }

    private void release() {
        synchronized (monitor) {
            owner = null;
            monitor.notifyAll();
        }
    }
}
