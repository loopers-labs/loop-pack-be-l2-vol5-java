package com.loopers.infrastructure.coupon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DB 없이 interrupt 를 무시하는 worker 로 종료 미확인 분기를 확인한다. 테스트가 만든 스레드는 끝에 풀어 종료를 확인한다. */
@DisplayName("worker 종료 확인 상태는 실제 종료 확인 때만 정리를 허용하고 미확인을 덮어쓰지 않는다.")
class WorkerTerminationTest {

    private final CountDownLatch release = new CountDownLatch(1);
    private final List<ExecutorService> started = new ArrayList<>();

    @AfterEach
    void releaseWorkers() throws InterruptedException {
        Thread.interrupted();
        release.countDown();
        for (ExecutorService executor : started) {
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("테스트 worker 종료").isTrue();
        }
    }

    @DisplayName("executor 를 시작하면 끝나기 전까지 정리를 허용하지 않고, 종료 확인 뒤에는 허용한다.")
    @Test
    void confirmedTerminationAllowsCleanup() {
        WorkerTermination workers = new WorkerTermination();
        ExecutorService executor = start(workers);

        assertThat(workers.safeToClean()).isFalse();
        release.countDown();
        workers.close(executor, Duration.ofSeconds(10));

        assertThat(workers.safeToClean()).isTrue();
    }

    @DisplayName("시간 안에 끝나지 않으면 shutdownNow 를 요청해도 미확인으로 남고, 이후 다른 종료 확인이 덮어쓰지 않는다.")
    @Test
    void timeoutStaysUnconfirmed() {
        WorkerTermination workers = new WorkerTermination();
        ExecutorService stuck = start(workers);

        assertThatThrownBy(() -> workers.close(stuck, Duration.ofMillis(50))).isInstanceOf(IllegalStateException.class);
        assertThat(stuck.isShutdown()).as("중단 요청은 보냄").isTrue();
        assertThat(workers.safeToClean()).isFalse();

        ExecutorService later = workers.start(1);
        started.add(later);
        workers.close(later, Duration.ofSeconds(10));
        assertThat(workers.safeToClean()).as("뒤 성공이 앞 미확인을 지우지 않음").isFalse();
    }

    @DisplayName("종료 대기 중 interrupt 는 미확인을 남기고 interrupt 상태를 복원한다.")
    @Test
    void interruptStaysUnconfirmed() {
        WorkerTermination workers = new WorkerTermination();
        ExecutorService executor = start(workers);

        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> workers.close(executor, Duration.ofSeconds(10)))
            .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(InterruptedException.class);

        assertThat(Thread.currentThread().isInterrupted()).as("interrupt 상태 복원").isTrue();
        assertThat(workers.safeToClean()).isFalse();
    }

    /** interrupt 를 무시하고 release 까지 살아 있는 worker 하나를 시작한다. 끝나지 않는 DB 작업의 대역이다. */
    private ExecutorService start(WorkerTermination workers) {
        ExecutorService executor = workers.start(1);
        started.add(executor);
        CountDownLatch running = new CountDownLatch(1);
        executor.submit(() -> {
            running.countDown();
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // 중단 요청을 무시한다.
                }
            }
        });
        try {
            assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        return executor;
    }
}
