package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponIssueAdmissionState;
import com.loopers.application.coupon.CouponIssueQueueProperties;
import com.loopers.application.coupon.CouponIssueQueueWorkResult;
import com.loopers.application.coupon.CouponIssueQueueWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponIssueQueueWorkerRunnerTest {

    @Mock private CouponIssueQueueWorker worker;

    private final CouponIssueAdmissionState admission = new CouponIssueAdmissionState();
    private CouponIssueQueueWorkerRunner runner;
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stopRunner() {
        release.countDown();
        if (runner != null) {
            runner.stop();
        }
    }

    @DisplayName("worker 설정이 꺼져 있으면 스레드를 시작하지 않는다.")
    @Test
    void doesNotStartWhenDisabled() {
        runner = runner(false, 3, Duration.ofMillis(50), Duration.ofSeconds(5));

        runner.start();

        verify(worker, after(300).never()).runOnce();
    }

    @DisplayName("설정한 수만큼만 동시에 실행하고 Java 안에 작업을 쌓지 않는다.")
    @Test
    void runsOnlyConfiguredNumberOfThreadsAtOnce() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        when(worker.runOnce()).thenAnswer(invocation -> {
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                release.await(5, TimeUnit.SECONDS);
            } finally {
                active.decrementAndGet();
            }
            return CouponIssueQueueWorkResult.IDLE;
        });
        runner = runner(true, 2, Duration.ofSeconds(10), Duration.ofSeconds(5));

        runner.start();

        verify(worker, timeout(1000).times(2)).runOnce();
        verify(worker, after(300).times(2)).runOnce();
        assertThat(maxActive.get()).isEqualTo(2);
    }

    @DisplayName("작업을 처리하면 기다리지 않고 바로 다음 작업을 확인한다.")
    @Test
    void checksNextImmediatelyAfterProcessing() {
        when(worker.runOnce()).thenReturn(CouponIssueQueueWorkResult.PROCESSED, CouponIssueQueueWorkResult.PROCESSED,
            CouponIssueQueueWorkResult.IDLE);
        runner = runner(true, 1, Duration.ofSeconds(10), Duration.ofSeconds(5));

        runner.start();

        verify(worker, timeout(1000).times(3)).runOnce();
        verify(worker, after(300).times(3)).runOnce();
    }

    @DisplayName("가져올 작업이 없으면 확인 주기만큼 기다린 뒤 다시 확인한다.")
    @Test
    void waitsPollIntervalWhenIdle() {
        List<Long> calledAt = new CopyOnWriteArrayList<>();
        when(worker.runOnce()).thenAnswer(invocation -> {
            calledAt.add(System.nanoTime());
            return CouponIssueQueueWorkResult.IDLE;
        });
        runner = runner(true, 1, Duration.ofMillis(300), Duration.ofSeconds(5));

        runner.start();

        verify(worker, timeout(1500).atLeast(2)).runOnce();
        long gapMillis = TimeUnit.NANOSECONDS.toMillis(calledAt.get(1) - calledAt.get(0));
        assertThat(gapMillis).isGreaterThanOrEqualTo(250L);
    }

    @DisplayName("처리 중 예외가 나도 스레드가 끝나지 않고 다음 주기에 다시 확인한다.")
    @Test
    void keepsLoopAliveAfterException() {
        when(worker.runOnce()).thenThrow(new IllegalStateException("예상하지 못한 오류"))
            .thenReturn(CouponIssueQueueWorkResult.IDLE);
        runner = runner(true, 1, Duration.ofMillis(50), Duration.ofSeconds(5));

        runner.start();

        verify(worker, timeout(1000).atLeast(2)).runOnce();
    }

    @DisplayName("처리 중 예상하지 못한 예외가 나면 작업 상태를 확인할 수 없으므로 신규 수용을 닫는다.")
    @Test
    void closesAdmissionAfterUnexpectedFailure() {
        admission.open();
        when(worker.runOnce()).thenThrow(new IllegalStateException("예상하지 못한 오류"))
            .thenReturn(CouponIssueQueueWorkResult.ADMISSION_CLOSED);
        runner = runner(true, 1, Duration.ofMillis(50), Duration.ofSeconds(5));

        runner.start();

        verify(worker, timeout(1000).atLeast(2)).runOnce();
        assertThat(admission.isOpen()).isFalse();
    }

    @DisplayName("종료하면 새 작업을 가져오지 않고, 실행 중인 처리가 끝날 때까지 기다린다.")
    @Test
    void stopsTakingNewWorkAndWaitsForRunningWork() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        when(worker.runOnce()).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return CouponIssueQueueWorkResult.PROCESSED;
        });
        runner = runner(true, 1, Duration.ofMillis(50), Duration.ofSeconds(5));
        runner.start();
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> stopping = CompletableFuture.runAsync(runner::stop);

        Thread.sleep(300);
        assertThat(stopping).isNotDone();
        release.countDown();
        stopping.get(1, TimeUnit.SECONDS);
        verify(worker, after(300).times(1)).runOnce();
    }

    @DisplayName("종료 대기 시간이 지나면 실행 중인 처리를 끊지 않고 종료 대기만 끝낸다.")
    @Test
    void stopsWaitingAfterShutdownTimeout() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        when(worker.runOnce()).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return CouponIssueQueueWorkResult.IDLE;
        });
        runner = runner(true, 1, Duration.ofMillis(50), Duration.ofMillis(200));
        runner.start();
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

        CompletableFuture.runAsync(runner::stop).get(2, TimeUnit.SECONDS);

        verify(worker, times(1)).runOnce();
    }

    @DisplayName("애플리케이션을 종료하면 Redis 연결 같은 자원이 멈추기 전에 실행 중인 처리를 마친다.")
    @Test
    void finishesRunningWorkBeforeConnectionResourcesStop() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean connectionRunningAtFinish = new AtomicBoolean();
        FakeRedisConnection connection = new FakeRedisConnection();
        when(worker.runOnce()).thenAnswer(invocation -> {
            started.countDown();
            // 종료가 시작된 뒤에도 DB 처리와 결과 기록이 이어지는 상황이다.
            Thread.sleep(300);
            connectionRunningAtFinish.set(connection.isRunning());
            return CouponIssueQueueWorkResult.PROCESSED;
        });
        runner = runner(true, 1, Duration.ofMillis(50), Duration.ofSeconds(5));
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(FakeRedisConnection.class, () -> connection);
        context.registerBean(CouponIssueQueueWorkerRunner.class, () -> runner);
        context.refresh();
        runner.start();
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

        context.close();

        assertThat(connectionRunningAtFinish).isTrue();
        assertThat(connection.isRunning()).isFalse();
    }

    private CouponIssueQueueWorkerRunner runner(boolean enabled, int concurrency, Duration pollInterval,
                                                Duration shutdownTimeout) {
        return new CouponIssueQueueWorkerRunner(worker, admission, new CouponIssueQueueProperties(20,
            new CouponIssueQueueProperties.Worker(enabled, concurrency, pollInterval, shutdownTimeout)));
    }

    /**
     * Lettuce 연결 팩토리처럼 phase 0에서 멈추는 연결 자원. 멈춘 뒤에는 Redis 명령을 보낼 수 없다.
     */
    static class FakeRedisConnection implements SmartLifecycle {

        private volatile boolean running;

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int getPhase() {
            return 0;
        }
    }
}
