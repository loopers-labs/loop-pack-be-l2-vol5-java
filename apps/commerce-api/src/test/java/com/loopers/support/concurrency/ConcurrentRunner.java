package com.loopers.support.concurrency;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 테스트 전용 (plan.md 3장 공통 규칙): 작업마다 스레드 하나를 두고 시작만 맞춘 뒤, 요청별 결과를 빠짐없이 모은다.
 * 각 작업은 실제 Spring bean을 호출하므로 독립된 트랜잭션·커넥션으로 실행된다. 대기에는 모두 제한 시간을 둔다.
 */
public final class ConcurrentRunner {

    private static final Duration READY_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DONE_TIMEOUT = Duration.ofSeconds(30);

    private ConcurrentRunner() {}

    /**
     * 모든 작업이 준비된 뒤 한꺼번에 출발시키고, 끝날 때까지 기다려 작업 순서대로 결과를 돌려준다.
     */
    public static List<Outcome> run(List<Callable<?>> tasks) throws InterruptedException {
        int size = tasks.size();
        ExecutorService executor = Executors.newFixedThreadPool(size);
        CountDownLatch ready = new CountDownLatch(size);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(READY_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                        return Outcome.of(new TimeoutException("출발 신호를 받지 못함"));
                    }
                    try {
                        task.call();
                        return Outcome.success();
                    } catch (Throwable e) {
                        return Outcome.of(e);
                    }
                }));
            }
            if (!ready.await(READY_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("작업이 준비되지 않았습니다");
            }
            start.countDown();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                try {
                    outcomes.add(future.get(DONE_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                } catch (Exception e) {
                    // 제한 시간 초과도 결과로 남긴다 — 업무 거절로 세지 않는다
                    outcomes.add(Outcome.of(e));
                }
            }
            return outcomes;
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    public enum Kind { SUCCESS, BUSINESS_REJECTED, TECHNICAL_ERROR }

    /**
     * 요청 하나의 결과. 업무 거절은 CONFLICT(재고·잔액 부족 등)만이고, 잠금 혼잡(CONCURRENCY_CONFLICT)·시간 초과·그 밖의 예외는 기술 오류다.
     */
    public record Outcome(Kind kind, Throwable error) {

        static Outcome success() {
            return new Outcome(Kind.SUCCESS, null);
        }

        static Outcome of(Throwable error) {
            boolean business = error instanceof CoreException core && core.getErrorType() == ErrorType.CONFLICT;
            return new Outcome(business ? Kind.BUSINESS_REJECTED : Kind.TECHNICAL_ERROR, error);
        }

        public boolean rejectedWith(String messagePart) {
            return kind == Kind.BUSINESS_REJECTED && error.getMessage().contains(messagePart);
        }
    }

    public static long count(List<Outcome> outcomes, Kind kind) {
        return outcomes.stream().filter(outcome -> outcome.kind() == kind).count();
    }
}
