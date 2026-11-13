package com.loopers.infrastructure.coupon;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 테스트가 시작한 worker executor 의 종료 확인 상태. 시작한 모든 executor 의 종료를 실제로 확인했을 때만 DB 정리를 허용한다.
 *
 * <p>executor 를 만들면 바로 미확인으로 센다. awaitTermination 이 true 를 돌려준 경우만 확인으로 치고,
 * 시간 초과·대기 중 interrupt 는 미확인을 영구히 남긴다. 이후 다른 executor 의 종료 확인이 앞선 미확인을 지우지 않는다.
 * shutdownNow 는 중단 요청일 뿐 worker 안의 DB 작업이 끝났다는 증거가 아니다.
 */
public final class WorkerTermination {

    private int open;
    private boolean unconfirmed;

    public synchronized ExecutorService start(int threads) {
        open++;
        return Executors.newFixedThreadPool(threads);
    }

    /** barrier 해제는 호출자가 먼저 한다. 종료를 확인하지 못하면 미확인을 남기고 예외를 던진다. */
    public void close(ExecutorService executor, Duration deadline) {
        executor.shutdown();
        boolean terminated;
        try {
            terminated = executor.awaitTermination(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            markUnconfirmed(executor);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("worker 종료 대기 중 interrupt: 종료 미확인", e);
        }
        if (!terminated) {
            markUnconfirmed(executor);
            throw new IllegalStateException("worker 종료 미확인: " + deadline + " 안에 끝나지 않음");
        }
        synchronized (this) {
            open--;
        }
    }

    public synchronized boolean safeToClean() {
        return open == 0 && !unconfirmed;
    }

    private void markUnconfirmed(ExecutorService executor) {
        synchronized (this) {
            unconfirmed = true;
        }
        executor.shutdownNow();
    }
}
