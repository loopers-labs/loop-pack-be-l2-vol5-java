package com.loopers.concurrency;

import com.loopers.concurrency.ConcurrentRunner.Outcome;
import com.loopers.concurrency.ConcurrentRunner.WorkerResult;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 동시 실행 틀 자체가 약속대로 동작하는지 확인한다. DB나 스프링 없이 실행한다.
 * 이 틀로 만든 경쟁 테스트의 결과(성공·거절·기술 오류 집계)를 믿으려면 틀의 분류와 정리가 먼저 믿을 만해야 한다.
 */
class ConcurrentRunnerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @DisplayName("모든 worker가 성공하면, 모두 SUCCESS로 분류되고 값이 worker 순서대로 반환된다.")
    @Test
    void classifiesAllAsSuccess_andKeepsOrder() {
        // arrange
        List<Callable<Integer>> tasks = List.of(() -> 10, () -> 20, () -> 30);

        // act
        List<WorkerResult<Integer>> results = ConcurrentRunner.runTogether(tasks, TIMEOUT);

        // assert
        assertThat(results).extracting(WorkerResult::outcome).containsOnly(Outcome.SUCCESS);
        assertThat(results).extracting(WorkerResult::value).containsExactly(10, 20, 30);
    }

    @DisplayName("worker들은 서로를 기다릴 수 있을 만큼 동시에 실행된다.")
    @Test
    void runsWorkersConcurrently() {
        // arrange: 두 worker가 모두 도착해야 래치가 열린다. 순서대로 하나씩 실행되면 상대가 시작하지 못해 열리지 않는다.
        CountDownLatch bothRunning = new CountDownLatch(2);
        Callable<Boolean> task = () -> {
            bothRunning.countDown();
            return bothRunning.await(2, TimeUnit.SECONDS);
        };

        // act
        List<WorkerResult<Boolean>> results = ConcurrentRunner.runTogether(List.of(task, task), TIMEOUT);

        // assert
        assertThat(results).extracting(WorkerResult::value).containsExactly(true, true);
    }

    @DisplayName("CoreException은 업무 거절로, 그 밖의 예외는 기술 오류로 분류한다.")
    @Test
    void classifiesBusinessRejectionAndTechnicalError() {
        // arrange
        Callable<String> succeeds = () -> "ok";
        Callable<String> rejected = () -> {
            throw new CoreException(ErrorType.BAD_REQUEST, "재고 부족");
        };
        Callable<String> broken = () -> {
            throw new IllegalStateException("SQL 오류");
        };

        // act
        List<WorkerResult<String>> results = ConcurrentRunner.runTogether(List.of(succeeds, rejected, broken), TIMEOUT);

        // assert
        assertThat(results).extracting(WorkerResult::outcome)
            .containsExactly(Outcome.SUCCESS, Outcome.BUSINESS_REJECTED, Outcome.TECHNICAL_ERROR);
        assertThat(((CoreException) results.get(1).error()).getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        assertThat(results.get(2).error()).isInstanceOf(IllegalStateException.class);
        assertThat(ConcurrentRunner.count(results, Outcome.BUSINESS_REJECTED)).isEqualTo(1);
    }

    @DisplayName("제한 시간 안에 끝나지 않는 worker는 기술 오류로 분류되고, 끝난 뒤 worker 스레드가 남지 않는다.")
    @Test
    void classifiesTimeoutAsTechnicalError_andCleansUpWorkers() throws InterruptedException {
        // arrange: 풀리지 않는 래치를 기다리는 worker를 하나 둔다.
        CountDownLatch neverReleased = new CountDownLatch(1);
        Callable<String> finishesQuickly = () -> "ok";
        Callable<String> stuck = () -> {
            neverReleased.await();
            return "never";
        };

        // act
        List<WorkerResult<String>> results =
            ConcurrentRunner.runTogether(List.of(finishesQuickly, stuck), Duration.ofMillis(300));

        // assert
        assertThat(results.get(0).outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(results.get(1).outcome()).isEqualTo(Outcome.TECHNICAL_ERROR);
        assertThat(results.get(1).error()).isInstanceOf(TimeoutException.class);
        assertThat(waitUntilNoAliveWorker()).as("정리되지 않고 남은 worker 스레드가 없어야 한다").isTrue();
    }

    @DisplayName("worker가 2개 미만이면, 동시 실행이 아니므로 거절한다.")
    @Test
    void rejectsLessThanTwoWorkers() {
        assertThatThrownBy(() -> ConcurrentRunner.runTogether(List.<Callable<Integer>>of(() -> 1), TIMEOUT))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * executor가 종료 상태가 되어도 마지막 스레드가 완전히 사라지기까지 아주 짧은 틈이 있어,
     * 곧바로 한 번만 검사하면 간헐적으로 실패한다. 짧은 시간 동안 확인을 반복한다.
     */
    private boolean waitUntilNoAliveWorker() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            boolean anyAlive = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.getName().startsWith(ConcurrentRunner.WORKER_NAME_PREFIX) && thread.isAlive());
            if (!anyAlive) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
