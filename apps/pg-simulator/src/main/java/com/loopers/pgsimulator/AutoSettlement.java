package com.loopers.pgsimulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 수동 실행용 자동 처리(설계 2.10). 생성 뒤 delay 가 지나면 상태를 정하고 콜백을 1회 보낸다. 콜백이 실패해도 재전송하지 않고,
 * 회복은 커머스 reconcile 이 맡는다. 꺼져 있으면 아무것도 예약하지 않는다.
 */
final class AutoSettlement {

    private static final Logger log = LoggerFactory.getLogger(AutoSettlement.class);

    private final Duration delay;
    private final ScheduledExecutorService scheduler;
    private final TransactionStore store;
    private final CallbackSender callbacks;

    AutoSettlement(Settlement settlement, String threadName, TransactionStore store, CallbackSender callbacks) {
        this.delay = settlement.delay();
        this.scheduler = settlement.enabled()
            ? Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, threadName);
                thread.setDaemon(true);
                return thread;
            })
            : null;
        this.store = store;
        this.callbacks = callbacks;
    }

    void schedule(String transactionKey, CreateOutcome outcome) {
        if (scheduler == null || outcome == CreateOutcome.REJECT_BEFORE_CREATE) {
            return;
        }
        try {
            scheduler.schedule(() -> settle(transactionKey, outcome), delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            log.info("PG 종료 중이라 자동 처리를 예약하지 않음: transactionKey={}", transactionKey);
        }
    }

    private void settle(String transactionKey, CreateOutcome outcome) {
        try {
            switch (outcome) {
                case PENDING_THEN_SUCCESS -> store.setStatus(transactionKey, PaymentStatus.SUCCESS);
                case PENDING_THEN_FAILED -> store.setStatus(transactionKey, PaymentStatus.FAILED);
                default -> {
                    // IMMEDIATE_SUCCESS 는 이미 SUCCESS 다. 콜백만 보낸다.
                }
            }
            TransactionSnapshot transaction = store.findByKey(transactionKey).orElseThrow();
            log.info("PG 자동 처리: transactionKey={}, status={}", transactionKey, transaction.status());
            callbacks.send(transaction).thenAccept(result -> log.info(
                "PG 콜백 결과: transactionKey={}, httpStatus={}, failure={}",
                transactionKey, result.httpStatus(), result.failure()));
        } catch (RuntimeException e) {
            log.error("PG 자동 처리 실패: transactionKey={}", transactionKey, e);
        }
    }

    void shutdownNow() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    boolean awaitTermination(Duration timeout) throws InterruptedException {
        return scheduler == null || scheduler.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}
