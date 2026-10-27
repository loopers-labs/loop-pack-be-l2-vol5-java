package com.loopers.utils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 여러 작업을 같은 순간에 시작시킨다. sleep 없이 래치 두 개로 순서를 고정한다.
 * ready: 모든 worker 가 스레드에 올라왔음을 메인이 확인 / start: 메인이 한 번에 출발 신호를 준다.
 * 반환 순서는 tasks 순서와 같다.
 */
public final class ConcurrentRunner {

    /**
     * @param value 성공 결과. 실패면 null.
     * @param error 예외 원인. 성공이면 null.
     */
    public record Run<T>(T value, Throwable error) {
        public boolean failed() {
            return error != null;
        }
    }

    private ConcurrentRunner() {}

    public static <T> List<Run<T>> runAll(List<Callable<T>> tasks, Duration timeout) throws InterruptedException {
        long seconds = timeout.toSeconds();
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(seconds, TimeUnit.SECONDS)) {
                        throw new TimeoutException("시작 신호를 기다리다 시간이 초과되었다.");
                    }
                    return task.call();
                }));
            }
            if (!ready.await(seconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException("worker 준비를 기다리다 시간이 초과되었다.");
            }
            start.countDown();

            List<Run<T>> runs = new ArrayList<>();
            for (Future<T> future : futures) {
                runs.add(collect(future, seconds));
            }
            return runs;
        } finally {
            executor.shutdownNow();
        }
    }

    private static <T> Run<T> collect(Future<T> future, long seconds) throws InterruptedException {
        try {
            return new Run<>(future.get(seconds, TimeUnit.SECONDS), null);
        } catch (ExecutionException e) {
            return new Run<>(null, e.getCause());
        } catch (TimeoutException e) {
            return new Run<>(null, e);
        }
    }
}