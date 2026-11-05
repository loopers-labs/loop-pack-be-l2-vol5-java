package com.loopers.infrastructure.order.outbox;

import com.loopers.application.time.ApplicationTime;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class OrderConfirmedOutboxRelay {

    private static final String BLOCKED_TRANSITIONS_METRIC = "outbox_blocked_transitions_total";

    private final OrderConfirmedOutboxStateService stateService;
    private final OrderConfirmedKafkaSender kafkaSender;
    private final OrderConfirmedOutboxFailureClassifier failureClassifier;
    private final ApplicationTime applicationTime;
    private final MeterRegistry meterRegistry;
    private final OrderConfirmedOutboxMetrics outboxMetrics;
    private final Executor callbackExecutor;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration retryInterval;
    private final Duration maximumWait;
    private final Duration futureTimeout;
    private final OrderConfirmedOutboxAwaitMode awaitMode;
    private final AtomicBoolean running = new AtomicBoolean();

    public OrderConfirmedOutboxRelay(
        OrderConfirmedOutboxStateService stateService,
        OrderConfirmedKafkaSender kafkaSender,
        OrderConfirmedOutboxFailureClassifier failureClassifier,
        ApplicationTime applicationTime,
        MeterRegistry meterRegistry,
        OrderConfirmedOutboxMetrics outboxMetrics,
        @Qualifier("orderConfirmedOutboxCallbackExecutor") Executor callbackExecutor,
        @Value("${order.confirmed-event.relay.batch-size}") int batchSize,
        @Value("${order.confirmed-event.relay.max-attempts}") int maxAttempts,
        @Value("${order.confirmed-event.relay.retry-interval}") Duration retryInterval,
        @Value("${order.confirmed-event.relay.max-block-time}") Duration maxBlockTime,
        @Value("${order.confirmed-event.relay.future-timeout}") Duration futureTimeout,
        @Value("${order.confirmed-event.relay.await-mode}") OrderConfirmedOutboxAwaitMode awaitMode
    ) {
        this.stateService = stateService;
        this.kafkaSender = kafkaSender;
        this.failureClassifier = failureClassifier;
        this.applicationTime = applicationTime;
        this.meterRegistry = meterRegistry;
        this.outboxMetrics = outboxMetrics;
        this.callbackExecutor = callbackExecutor;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.retryInterval = retryInterval;
        this.maximumWait = maxBlockTime.plus(futureTimeout);
        this.futureTimeout = futureTimeout;
        this.awaitMode = awaitMode;
    }

    public boolean runDueBatch() {
        return runDueBatch(awaitMode);
    }

    public boolean runDueBatch(OrderConfirmedOutboxAwaitMode mode) {
        return run(mode, null);
    }

    public boolean runEventOnce(String eventId) {
        return run(OrderConfirmedOutboxAwaitMode.BOUNDED_WAIT, List.of(eventId));
    }

    private boolean run(OrderConfirmedOutboxAwaitMode mode, List<String> requestedEventIds) {
        if (!running.compareAndSet(false, true)) {
            log.debug("주문 확정 Outbox relay 실행 중이라 이번 호출을 건너뜁니다.");
            return false;
        }
        refreshMetrics();
        if (mode == OrderConfirmedOutboxAwaitMode.CALLBACK) {
            try {
                List<String> eventIds = eventIds(requestedEventIds);
                processBatchAsync(eventIds, 0).whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        log.error("주문 확정 Outbox callback relay 회차가 예기치 않게 끝났습니다.", unwrap(failure));
                    }
                    refreshMetrics();
                    running.set(false);
                });
                return true;
            } catch (RuntimeException failure) {
                running.set(false);
                throw failure;
            }
        }
        try {
            for (String eventId : eventIds(requestedEventIds)) {
                if (!process(eventId)) {
                    return true;
                }
            }
            return true;
        } finally {
            refreshMetrics();
            running.set(false);
        }
    }

    private List<String> eventIds(List<String> requestedEventIds) {
        return requestedEventIds == null
            ? stateService.findDueEventIds(applicationTime.now(), batchSize)
            : requestedEventIds;
    }

    private void refreshMetrics() {
        try {
            outboxMetrics.refresh();
        } catch (RuntimeException failure) {
            log.warn("주문 확정 Outbox 집계 metric 갱신에 실패했습니다.", failure);
        }
    }

    private java.util.concurrent.CompletableFuture<Void> processBatchAsync(List<String> eventIds, int index) {
        if (index >= eventIds.size()) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        OrderConfirmedOutboxStateService.BeginAttemptResult start;
        try {
            start = stateService.beginAttempt(eventIds.get(index), applicationTime.now(), maximumWait, maxAttempts);
        } catch (RuntimeException failure) {
            log.error("주문 확정 Outbox 시도를 저장하지 못했습니다. eventId={}", eventIds.get(index), failure);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        if (start.kind() == OrderConfirmedOutboxStateService.BeginAttemptKind.SKIPPED) {
            return processBatchAsync(eventIds, index + 1);
        }
        if (start.kind() == OrderConfirmedOutboxStateService.BeginAttemptKind.BLOCKED) {
            recordBlocked(start.eventId(), start.orderId(), start.reason(), null);
            return processBatchAsync(eventIds, index + 1);
        }

        OrderConfirmedOutboxAttempt attempt = start.attempt();
        java.util.concurrent.CompletableFuture<org.springframework.kafka.support.SendResult<Object, Object>> future;
        try {
            future = kafkaSender.send(attempt.messageKey(), attempt.payload());
        } catch (RuntimeException failure) {
            boolean continueBatch = handleFailure(attempt, unwrap(failure));
            return continueBatch ? processBatchAsync(eventIds, index + 1)
                : java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        return future.orTimeout(futureTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .handleAsync((result, failure) -> failure == null
                ? handlePublished(attempt, result)
                : handleFailure(attempt, unwrap(failure), false), callbackExecutor)
            .thenCompose(continueBatch -> continueBatch
                ? processBatchAsync(eventIds, index + 1)
                : java.util.concurrent.CompletableFuture.completedFuture(null));
    }

    private boolean process(String eventId) {
        OrderConfirmedOutboxStateService.BeginAttemptResult start = stateService.beginAttempt(
            eventId, applicationTime.now(), maximumWait, maxAttempts);
        if (start.kind() == OrderConfirmedOutboxStateService.BeginAttemptKind.SKIPPED) {
            return true;
        }
        if (start.kind() == OrderConfirmedOutboxStateService.BeginAttemptKind.BLOCKED) {
            recordBlocked(start.eventId(), start.orderId(), start.reason(), null);
            return true;
        }

        OrderConfirmedOutboxAttempt attempt = start.attempt();
        java.util.concurrent.CompletableFuture<org.springframework.kafka.support.SendResult<Object, Object>> future;
        org.springframework.kafka.support.SendResult<Object, Object> result;
        try {
            future = kafkaSender.send(attempt.messageKey(), attempt.payload());
            result = future.get(futureTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("주문 확정 Outbox 결과 대기가 중단됐습니다. eventId={}, orderId={}",
                attempt.eventId(), attempt.orderId(), interrupted);
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException failure) {
            Throwable cause = unwrap(failure);
            return handleFailure(attempt, cause);
        }

        return handlePublished(attempt, result);
    }

    private boolean handlePublished(OrderConfirmedOutboxAttempt attempt,
                                    org.springframework.kafka.support.SendResult<Object, Object> result) {
        try {
            if (!stateService.markPublished(attempt, applicationTime.now())) {
                log.warn("오래된 주문 확정 Outbox 전송 결과를 적용하지 않았습니다. eventId={}, orderId={}, attempt={}",
                    attempt.eventId(), attempt.orderId(), attempt.attemptNumber());
                return false;
            }
            log.info("주문 확정 Outbox 발행을 broker가 확인했습니다. eventId={}, orderId={}, topic={}, partition={}, offset={}",
                attempt.eventId(), attempt.orderId(), result.getRecordMetadata().topic(),
                result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
            return true;
        } catch (RuntimeException stateFailure) {
            log.error("broker 확인 뒤 Outbox PUBLISHED 저장에 실패했습니다. eventId={}, orderId={}",
                attempt.eventId(), attempt.orderId(), stateFailure);
            return false;
        }
    }

    private boolean handleFailure(OrderConfirmedOutboxAttempt attempt, Throwable failure) {
        return handleFailure(attempt, failure, true);
    }

    private boolean handleFailure(OrderConfirmedOutboxAttempt attempt, Throwable failure, boolean restoreInterrupt) {
        OrderConfirmedOutboxFailureClassifier.FailureKind kind = failureClassifier.classify(failure);
        if (kind == OrderConfirmedOutboxFailureClassifier.FailureKind.INTERRUPTED) {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
            log.warn("주문 확정 Outbox 전송이 종료 신호로 중단됐습니다. eventId={}, orderId={}",
                attempt.eventId(), attempt.orderId(), failure);
            return false;
        }

        String safeError = failureClassifier.safeMessage(failure);
        try {
            if (kind == OrderConfirmedOutboxFailureClassifier.FailureKind.RETRYABLE) {
                OrderConfirmedOutboxStateService.FailureResult result = stateService.markRetryableFailure(
                    attempt, applicationTime.now(), retryInterval, maxAttempts, safeError);
                if (result.kind() == OrderConfirmedOutboxStateService.FailureKind.BLOCKED) {
                    recordBlocked(result.eventId(), result.orderId(), result.reason(), failure);
                } else {
                    log.warn("주문 확정 Outbox 발행을 재시도합니다. eventId={}, orderId={}, attempt={}, nextAttemptAt={}, error={}",
                        attempt.eventId(), attempt.orderId(), attempt.attemptNumber(),
                        applicationTime.now().plus(retryInterval), safeError);
                }
                return result.kind() == OrderConfirmedOutboxStateService.FailureKind.BLOCKED;
            }

            OrderConfirmedOutboxStateService.FailureResult result = stateService.markBlocked(
                attempt, applicationTime.now(), safeError, failure.getClass().getSimpleName());
            if (result.kind() == OrderConfirmedOutboxStateService.FailureKind.BLOCKED) {
                recordBlocked(result.eventId(), result.orderId(), result.reason(), failure);
            }
            return true;
        } catch (RuntimeException stateFailure) {
            log.error("주문 확정 Outbox 상태를 저장하지 못했습니다. eventId={}, orderId={}, originalError={}",
                attempt.eventId(), attempt.orderId(), safeError, stateFailure);
            return false;
        }
    }

    private void recordBlocked(String eventId, Long orderId, String reason, Throwable failure) {
        meterRegistry.counter(BLOCKED_TRANSITIONS_METRIC, "reason", reason).increment();
        if (failure == null) {
            log.error("주문 확정 Outbox가 BLOCKED 상태가 됐습니다. eventId={}, orderId={}, reason={}",
                eventId, orderId, reason);
        } else {
            log.error("주문 확정 Outbox가 BLOCKED 상태가 됐습니다. eventId={}, orderId={}, reason={}",
                eventId, orderId, reason, failure);
        }
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
