package com.loopers.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ConcurrentRequests {
    private ConcurrentRequests() {
    }

    public record Result<T>(List<T> successes, List<Throwable> failures) {
    }

    public static <T> Result<T> run(List<Callable<T>> requests) throws Exception {
        var executor = Executors.newFixedThreadPool(requests.size());
        var ready = new CountDownLatch(requests.size());
        var start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> request : requests) {
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    // 모든 worker가 준비되면 출발한다. 서비스 내부의 읽기·쓰기 순서는 제어하지 않는다.
                                    if (!start.await(10, TimeUnit.SECONDS)) {
                                        throw new TimeoutException("동시 요청 시작 시간 초과");
                                    }
                                    // 호출하는 Spring bean이 worker마다 별도의 트랜잭션을 시작한다.
                                    return request.call();
                                }));
            }
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new TimeoutException("worker 준비 시간 초과");
            }
            start.countDown();
            List<T> successes = new ArrayList<>();
            List<Throwable> failures = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    successes.add(future.get(20, TimeUnit.SECONDS));
                } catch (ExecutionException exception) {
                    // 업무 오류와 기술 오류를 버리지 않고 테스트가 구분할 수 있게 원인을 보관한다.
                    failures.add(exception.getCause());
                }
            }
            return new Result<>(successes.stream().toList(), List.copyOf(failures));
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new TimeoutException("worker 종료 시간 초과");
            }
        }
    }
}
