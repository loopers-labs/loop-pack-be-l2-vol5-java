package com.loopers.support.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

// 여러 요청을 시작 시점만 맞춰 동시에 실행하고, 각각의 성공 값 또는 예외를 모아 반환하는 테스트 전용 도구
public final class ConcurrentRequests {

    private ConcurrentRequests() {}

    public static <T> List<Outcome<T>> run(List<Callable<T>> tasks, long startTimeoutSeconds, long totalTimeoutSeconds)
            throws InterruptedException {
        int size = tasks.size();
        ExecutorService executor = Executors.newFixedThreadPool(size);
        CountDownLatch ready = new CountDownLatch(size);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(startTimeoutSeconds, TimeUnit.SECONDS)) {
                        return Outcome.<T>failure(new IllegalStateException("시작 신호 대기 타임아웃"));
                    }
                    try {
                        return Outcome.success(task.call());
                    } catch (Exception exception) {
                        return Outcome.<T>failure(exception);
                    }
                }));
            }

            if (!ready.await(startTimeoutSeconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException("워커가 시작 준비를 마치지 못했다");
            }
            start.countDown();

            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(totalTimeoutSeconds);
            List<Outcome<T>> results = new ArrayList<>();
            for (Future<Outcome<T>> future : futures) {
                long remaining = Math.max(0, deadline - System.currentTimeMillis());
                try {
                    results.add(future.get(remaining, TimeUnit.MILLISECONDS));
                } catch (Exception timeoutOrExecutionFailure) {
                    future.cancel(true);
                    results.add(Outcome.failure(timeoutOrExecutionFailure));
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    public record Outcome<T>(T value, Throwable error) {
        static <T> Outcome<T> success(T value) {
            return new Outcome<>(value, null);
        }

        static <T> Outcome<T> failure(Throwable error) {
            return new Outcome<>(null, error);
        }

        public boolean isSuccess() {
            return error == null;
        }
    }
}
