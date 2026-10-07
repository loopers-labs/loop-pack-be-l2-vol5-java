package com.loopers.fixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * LockProbe 의 확인 스레드 종료 판정. 종료를 실제로 확인한 경우에만 이후 DB 정리를 허용하고,
 * 시간 초과·대기 중단으로 확인하지 못하면 예외와 함께 종료 미확인 상태를 남기는지 확인한다.
 * DB 를 쓰지 않으며, 짧은 제한 시간은 이 판정 테스트에서만 사용한다.
 */
@DisplayName("LockProbe 는 확인 스레드 종료를 실제로 확인한 경우에만 정리를 허용한다.")
class LockProbeTest {

    private static final Duration SHORT = Duration.ofMillis(200);
    private static final long RELEASE_WAIT_SECONDS = 10;

    private final LockProbe lockProbe = new LockProbe(null, null);

    /** 인터럽트를 무시하고 release 될 때까지 끝나지 않는 작업. 종료 후 finished 를 내린다. */
    private static LockProbe.Result stuckUntilReleased(AtomicBoolean release, CountDownLatch finished) {
        try {
            while (!release.get()) {
                Thread.interrupted();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
            return LockProbe.Result.ACQUIRED;
        } finally {
            finished.countDown();
        }
    }

    @DisplayName("작업이 끝나면 결과를 돌려주고 종료를 확인한 상태로 남는다.")
    @Test
    void returnsResultAfterConfirmingTermination() {
        LockProbe.Result result = lockProbe.runConfirmingTermination(() -> LockProbe.Result.LOCKED, SHORT, SHORT);

        assertAll(
            () -> assertThat(result).isEqualTo(LockProbe.Result.LOCKED),
            () -> assertThat(lockProbe.allThreadsTerminated()).isTrue()
        );
    }

    @DisplayName("작업이 실패해도 스레드 종료를 확인했다면 예외만 전달하고 정리는 허용한다.")
    @Test
    void allowsCleanupWhenFailedThreadTerminated() {
        assertThatThrownBy(() -> lockProbe.runConfirmingTermination(() -> {
            throw new IllegalArgumentException("확인 SQL 실패");
        }, SHORT, SHORT))
            .isInstanceOf(IllegalStateException.class)
            .hasRootCauseInstanceOf(IllegalArgumentException.class);

        assertThat(lockProbe.allThreadsTerminated()).isTrue();
    }

    @DisplayName("결과 대기 시간 초과 뒤 shutdownNow 에도 스레드가 끝나지 않으면 종료 미확인으로 남기고 테스트를 실패시킨다.")
    @Test
    void marksUnterminatedWhenThreadIgnoresInterrupt() throws InterruptedException {
        AtomicBoolean release = new AtomicBoolean(false);
        CountDownLatch finished = new CountDownLatch(1);
        try {
            assertThatThrownBy(() -> lockProbe.runConfirmingTermination(
                () -> stuckUntilReleased(release, finished), SHORT, SHORT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("종료 미확인");

            assertAll(
                () -> assertThat(lockProbe.allThreadsTerminated()).isFalse(),
                () -> assertThat(finished.getCount()).as("판정 시점에 작업은 아직 실행 중").isEqualTo(1L)
            );
        } finally {
            release.set(true);
            assertThat(finished.await(RELEASE_WAIT_SECONDS, TimeUnit.SECONDS)).as("판정 후 작업 정리").isTrue();
        }
    }

    @DisplayName("종료 대기 중 호출 스레드가 인터럽트되면 종료 미확인으로 남기고 인터럽트 상태를 복원한다.")
    @Test
    void marksUnterminatedWhenWaitIsInterrupted() throws InterruptedException {
        AtomicBoolean release = new AtomicBoolean(false);
        CountDownLatch finished = new CountDownLatch(1);
        boolean interruptedAfter;
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> lockProbe.runConfirmingTermination(
                () -> stuckUntilReleased(release, finished), SHORT, SHORT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("종료 미확인");
        } finally {
            interruptedAfter = Thread.interrupted();
            release.set(true);
        }

        assertAll(
            () -> assertThat(interruptedAfter).as("인터럽트 상태 복원").isTrue(),
            () -> assertThat(lockProbe.allThreadsTerminated()).isFalse()
        );
        assertThat(finished.await(RELEASE_WAIT_SECONDS, TimeUnit.SECONDS)).as("판정 후 작업 정리").isTrue();
    }
}
