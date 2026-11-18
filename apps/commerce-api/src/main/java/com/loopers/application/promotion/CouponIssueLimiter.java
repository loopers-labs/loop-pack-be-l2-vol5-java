package com.loopers.application.promotion;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * 쿠폰 발급 TX 하나를 세마포어 허가 안에서 비동기로 실행한다 (DR-63).
 * 허가는 호출 스레드를 잡지 않고 기다린다. waitTime 안에 얻지 못하면 재시도하지 않고 COUPON_ISSUE_BUSY.
 * 얻으면 TX 를 이 리미터의 스레드 풀에서 실행한다. 허가 결과는 Redis 클라이언트 스레드로 오는데, 거기서 JDBC 를 돌리지 않는다.
 * 허가는 TX 를 시작하기 전에 얻고, TX 가 끝나면 (커밋·롤백·예외 모두) 반납한다.
 * 풀의 큐가 넘치면 TX 를 실행하지 않고 허가를 바로 반납한 뒤 COUPON_ISSUE_BUSY.
 * 풀은 빈으로 두지 않는다 (Executor 빈이 생기면 Boot 의 applicationTaskExecutor 자동 구성이 빠진다).
 */
@Component
public class CouponIssueLimiter {
    private final CouponIssueSemaphore semaphore;
    private final ThreadPoolTaskExecutor executor;

    public CouponIssueLimiter(CouponIssueSemaphore semaphore, CouponIssueLimiterProperties properties) {
        this.semaphore = semaphore;
        this.executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.executor().threads());
        executor.setMaxPoolSize(properties.executor().threads());
        executor.setQueueCapacity(properties.executor().queueCapacity());
        executor.setThreadNamePrefix("coupon-issue-");
        executor.initialize();
    }

    /** future 는 task 결과로 끝나거나, COUPON_ISSUE_BUSY·task 예외로 끝난다. */
    public <T> CompletableFuture<T> run(Long promotionId, Supplier<T> task) {
        return semaphore.tryAcquire(promotionId).thenCompose(token -> token
            .map(t -> runWithPermit(promotionId, t, task))
            .orElseGet(() -> CompletableFuture.failedFuture(busy(promotionId))));
    }

    private <T> CompletableFuture<T> runWithPermit(Long promotionId, String token, Supplier<T> task) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return task.get();
                } finally {
                    semaphore.release(promotionId, token);
                }
            }, executor);
        } catch (RejectedExecutionException e) {
            semaphore.release(promotionId, token);
            return CompletableFuture.failedFuture(busy(promotionId));
        }
    }

    private static CoreException busy(Long promotionId) {
        return new CoreException(ErrorType.COUPON_ISSUE_BUSY, "[promotionId = " + promotionId + "] 발급 허가를 얻지 못했습니다.");
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
