package com.loopers.concurrency;

import com.loopers.support.error.CoreException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 여러 worker를 같은 순간에 출발시키고 요청별 결과를 모으는 동시 실행 테스트용 틀이다.
 *
 * <ul>
 *   <li>worker마다 스레드를 따로 쓴다. worker 안에서 서비스를 호출하면 그 호출이 독립된 트랜잭션이 된다.</li>
 *   <li>이 틀은 worker의 <b>시작만</b> 맞춘다. 읽은 뒤에 서로를 기다리게 하는 장벽이나 sleep은 넣지 않는다.
 *       그런 장벽은 갱신 유실을 일부러 재현하는 대조군 테스트 안에만 둔다.</li>
 *   <li>대기와 결과 수집에는 모두 제한 시간을 두고, 끝나면(실패해도) worker를 중단하고 정리한다.</li>
 *   <li>결과는 성공·업무 거절·기술 오류로 나눈다. 기술 오류를 업무 거절로 숨기지 않기 위해
 *       {@link CoreException}만 업무 거절로 보고, 제한 시간 초과를 포함한 나머지는 기술 오류로 본다.</li>
 * </ul>
 */
public final class ConcurrentRunner {

    /** 동시 실행이라고 부를 수 있는 최소 worker 수. */
    public static final int MIN_WORKERS = 2;

    /** worker 스레드 이름의 접두어. 테스트에서 스레드가 정리됐는지 확인할 때 쓴다. */
    public static final String WORKER_NAME_PREFIX = "concurrent-worker-";

    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(5);

    /** 요청 하나의 결과 분류. */
    public enum Outcome {
        /** 정상 종료했다. */
        SUCCESS,
        /** {@link CoreException}으로 거절됐다. 재고 부족·잔액 부족 같은 업무 규칙 위반이다. */
        BUSINESS_REJECTED,
        /** 업무 규칙과 무관한 실패다. 제한 시간 초과, SQL 오류, 잠금 대기 초과, 교착 등이 여기에 속한다. */
        TECHNICAL_ERROR
    }

    /**
     * 요청 하나의 결과다.
     *
     * @param index   tasks에서의 순서
     * @param outcome 결과 분류
     * @param value   {@link Outcome#SUCCESS}일 때 worker가 돌려준 값, 아니면 null
     * @param error   실패했을 때의 예외, 성공이면 null. 업무 거절 종류는 {@link CoreException#getErrorType()}으로 본다
     */
    public record WorkerResult<T>(int index, Outcome outcome, T value, Throwable error) {
    }

    private ConcurrentRunner() {
    }

    /**
     * tasks를 각자 다른 스레드에서 같은 순간에 시작해 모든 결과를 돌려준다.
     * 시작 신호를 받은 시점부터 모든 worker가 끝나기까지 {@code timeout}을 넘기면, 끝나지 않은 worker는
     * {@link Outcome#TECHNICAL_ERROR}({@link TimeoutException})로 분류하고 중단한다.
     *
     * @return tasks와 같은 순서의 결과
     */
    public static <T> List<WorkerResult<T>> runTogether(List<? extends Callable<T>> tasks, Duration timeout) {
        if (tasks.size() < MIN_WORKERS) {
            throw new IllegalArgumentException(
                "동시 실행에는 worker가 최소 " + MIN_WORKERS + "개 필요하다. 전달된 수: " + tasks.size());
        }

        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size(), workerThreadFactory());
        List<WorkerResult<T>> results;
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    // 모든 worker가 준비될 때까지 여기서 기다렸다가 함께 출발한다.
                    if (!start.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                        throw new IllegalStateException("시작 신호를 제한 시간 안에 받지 못했다.");
                    }
                    return task.call();
                }));
            }

            // 스레드를 실제로 모두 확보했는지 확인한 뒤에 출발시킨다. 확보하지 못하면 동시 실행이 아니다.
            if (!ready.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("worker가 제한 시간 안에 모두 준비되지 않았다.");
            }
            start.countDown();
            results = collect(futures, timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("worker를 기다리는 중 인터럽트되었다.", e);
        } finally {
            // 시작 신호를 기다리는 worker가 남아 있으면 풀어 주고, 아직 도는 worker는 중단한다.
            start.countDown();
            executor.shutdownNow();
        }

        // 정상 경로에서는 worker가 실제로 모두 종료됐는지까지 확인해, 정리 누락이 조용히 지나가지 않게 한다.
        awaitTerminated(executor);
        return results;
    }

    /** results 중 outcome이 같은 요청의 수. */
    public static long count(List<? extends WorkerResult<?>> results, Outcome outcome) {
        return results.stream().filter(result -> result.outcome() == outcome).count();
    }

    private static <T> List<WorkerResult<T>> collect(List<Future<T>> futures, Duration timeout)
        throws InterruptedException {
        // worker마다 제한 시간을 따로 주면 최악의 경우 (worker 수 x timeout)만큼 기다리므로 전체 마감을 공유한다.
        long deadline = System.nanoTime() + timeout.toNanos();
        List<WorkerResult<T>> results = new ArrayList<>();
        for (int index = 0; index < futures.size(); index++) {
            long remaining = Math.max(0L, deadline - System.nanoTime());
            results.add(classify(index, futures.get(index), remaining));
        }
        return results;
    }

    private static <T> WorkerResult<T> classify(int index, Future<T> future, long remainingNanos)
        throws InterruptedException {
        try {
            return new WorkerResult<>(index, Outcome.SUCCESS, future.get(remainingNanos, TimeUnit.NANOSECONDS), null);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new WorkerResult<>(index, Outcome.TECHNICAL_ERROR, null, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            Outcome outcome = cause instanceof CoreException ? Outcome.BUSINESS_REJECTED : Outcome.TECHNICAL_ERROR;
            return new WorkerResult<>(index, outcome, null, cause);
        }
    }

    private static void awaitTerminated(ExecutorService executor) {
        try {
            if (!executor.awaitTermination(CLEANUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("worker가 정리 제한 시간 안에 종료되지 않았다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("worker 종료를 기다리는 중 인터럽트되었다.", e);
        }
    }

    private static ThreadFactory workerThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, WORKER_NAME_PREFIX + sequence.incrementAndGet());
            // 정리에 실패한 경우에도 테스트 JVM의 종료를 막지 않는다.
            thread.setDaemon(true);
            return thread;
        };
    }
}
