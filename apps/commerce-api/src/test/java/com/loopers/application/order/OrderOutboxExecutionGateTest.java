package com.loopers.application.order;

import com.loopers.application.order.OrderOutboxExecutionGate.Outcome;
import com.loopers.application.order.OrderOutboxExecutionGate.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/** O10 중 gate 자체의 단일 실행·종료 동작(설계 W7.3-1). relay·DB 와의 연결은 OrderOutboxRelayIntegrationTest 가 본다. */
@DisplayName("실행 gate 는 기다리지 않고 BUSY·STOPPING 을 돌려주며, 예외·종료에도 finally 로 해제하고 진행 thread 에 interrupt 를 전달한다.")
class OrderOutboxExecutionGateTest {

    private final OrderOutboxExecutionGate gate = new OrderOutboxExecutionGate();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() throws InterruptedException {
        worker.shutdownNow();
        assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @DisplayName("값 작업은 EXECUTED 와 값을, 값 없는 작업은 빈 값을 돌려주고 null 결과는 프로그램 오류다.")
    @Test
    void executesAndReturnsValue() {
        assertAll(
            () -> assertThat(gate.tryExecute(() -> "done")).isEqualTo(new Result<>(Outcome.EXECUTED, Optional.of("done"))),
            () -> assertThat(gate.tryRun(() -> { })).isEqualTo(new Result<Void>(Outcome.EXECUTED, Optional.empty())),
            () -> assertThatThrownBy(() -> gate.tryExecute(() -> null)).isInstanceOf(NullPointerException.class),
            () -> assertThat(gate.tryExecute(() -> 1).outcome()).as("null 결과 뒤에도 해제됨").isEqualTo(Outcome.EXECUTED)
        );
    }

    @DisplayName("실행 중에는 다른 thread·같은 thread 재진입 모두 작업 없이 BUSY 이고, 끝나면 다시 실행된다.")
    @Test
    void busyWhileHeld() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        AtomicBoolean nestedBusy = new AtomicBoolean();
        Future<Result<Void>> holder = worker.submit(() -> gate.tryRun(() -> {
            nestedBusy.set(gate.tryRun(runs::incrementAndGet).outcome() == Outcome.BUSY);
            entered.countDown();
            await(release);
        }));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        Result<Integer> busy = gate.tryExecute(runs::incrementAndGet);
        release.countDown();

        assertAll(
            () -> assertThat(busy).isEqualTo(new Result<Integer>(Outcome.BUSY, Optional.empty())),
            () -> assertThat(nestedBusy).as("재진입 거절").isTrue(),
            () -> assertThat(holder.get(5, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.EXECUTED),
            () -> assertThat(runs).as("BUSY 작업은 실행되지 않음").hasValue(0),
            () -> assertThat(gate.tryExecute(runs::incrementAndGet).value()).contains(1)
        );
    }

    @DisplayName("작업 예외는 그대로 전파하고 gate 는 해제된다.")
    @Test
    void exceptionReleases() {
        IllegalStateException failure = new IllegalStateException("x");
        assertThatThrownBy(() -> gate.tryRun(() -> { throw failure; })).isSameAs(failure);
        assertThat(gate.tryExecute(() -> 1).outcome()).isEqualTo(Outcome.EXECUTED);
    }

    @DisplayName("stop 은 진행 thread 를 interrupt 하고 해제를 기다릴 수 있으며, 이후 진입은 실행 없이 STOPPING 이다.")
    @Test
    void stopInterruptsAndRejects() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        Future<Result<Void>> holder = worker.submit(() -> gate.tryRun(() -> {
            entered.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(30).toMillis());
            } catch (InterruptedException expected) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(gate.awaitIdle(Duration.ofMillis(50))).as("진행 중에는 시간 안에 끝나지 않음").isFalse();

        gate.stop();
        gate.stop();

        assertAll(
            () -> assertThat(gate.awaitIdle(Duration.ofSeconds(5))).isTrue(),
            () -> assertThat(holder.get(5, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.EXECUTED),
            () -> assertThat(interrupted).isTrue(),
            () -> assertThat(gate.isStopping()).isTrue(),
            () -> assertThat(gate.tryExecute(() -> 1)).isEqualTo(new Result<Integer>(Outcome.STOPPING, Optional.empty()))
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
