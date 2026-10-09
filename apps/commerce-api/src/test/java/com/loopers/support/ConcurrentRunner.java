package com.loopers.support;

import com.loopers.support.error.CoreException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 실제 서비스의 경쟁 테스트를 위한 실행기. 작업의 시작만 맞추며, 어느 순서로 겹칠지는 정하지 않음 (3주차 설계 6.1, 6.4).
 * 요청별 결과를 성공 · 업무 거절(오류 코드) · 재시도 한도 초과 · 기술 오류로 나눠 돌려주고, 예외를 버리지 않음
 */
public final class ConcurrentRunner {

    public static final String SUCCESS = "SUCCESS";
    /** 낙관적 잠금 충돌이 ~Retrier 의 재시도 한도를 넘음 */
    public static final String EXHAUSTED = "EXHAUSTED";
    public static final String TECHNICAL_PREFIX = "TECHNICAL:";

    private static final long WAIT_SECONDS = 30;

    private ConcurrentRunner() {}

    public static Callable<String> outcomeOf(Runnable request) {
        return () -> {
            try {
                request.run();
                return SUCCESS;
            } catch (CoreException e) {
                return e.getErrorCode().getCode();
            } catch (OptimisticLockingFailureException e) {
                return EXHAUSTED;
            } catch (RuntimeException e) {
                return TECHNICAL_PREFIX + e.getClass().getSimpleName();
            }
        };
    }

    /** 모든 작업의 시작만 맞춰 실행하고, 모두 끝날 때까지 기다린 뒤 결과를 작업 순서대로 돌려줌 */
    public static List<String> run(List<Callable<String>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<String> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("시작 신호를 받지 못함");
                    }
                    return task.call();
                }));
            }
            if (!ready.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("작업이 준비되지 않음");
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) {
                outcomes.add(future.get(WAIT_SECONDS, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }
}
