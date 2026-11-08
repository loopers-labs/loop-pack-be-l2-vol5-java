package com.loopers.infrastructure.order.outbox;

import com.loopers.application.time.ApplicationTime;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// 비교 실험 전용이다. BOUNDED_WAIT는 실제 제품 Relay에 위임하고 CALLBACK만 이 fixture에서 실행한다.
public class OrderConfirmedOutboxRelayExperiment implements ApplicationListener<ContextClosedEvent> {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmedOutboxRelayExperiment.class);

    private static final String BLOCKED_TRANSITIONS_METRIC = "outbox_blocked_transitions_total";

    private final OrderConfirmedOutboxRelay boundedRelay;
    private final OrderConfirmedOutboxStateService stateService;
    private final OrderConfirmedKafkaSender kafkaSender;
    private final OrderConfirmedOutboxFailureClassifier failureClassifier;
    private final ApplicationTime applicationTime;
    private final MeterRegistry meterRegistry;
    private final Executor callbackExecutor;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration retryInterval;
    private final Duration maximumWait;
    private final Duration futureTimeout;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    public OrderConfirmedOutboxRelayExperiment(
        OrderConfirmedOutboxRelay boundedRelay,
        OrderConfirmedOutboxStateService stateService,
        OrderConfirmedKafkaSender kafkaSender,
        OrderConfirmedOutboxFailureClassifier failureClassifier,
        ApplicationTime applicationTime,
        MeterRegistry meterRegistry,
        Executor callbackExecutor,
        int batchSize,
        int maxAttempts,
        Duration retryInterval,
        Duration maxBlockTime,
        Duration futureTimeout
    ) {
        this.boundedRelay = boundedRelay;
        this.stateService = stateService;
        this.kafkaSender = kafkaSender;
        this.failureClassifier = failureClassifier;
        this.applicationTime = applicationTime;
        this.meterRegistry = meterRegistry;
        this.callbackExecutor = callbackExecutor;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.retryInterval = retryInterval;
        this.maximumWait = maxBlockTime.plus(futureTimeout);
        this.futureTimeout = futureTimeout;
    }

    public boolean runDueBatch(AwaitMode mode) {
        return run(mode, null);
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        shuttingDown.set(true);
        boundedRelay.onApplicationEvent(event);
    }

    private boolean run(AwaitMode mode, List<String> requestedEventIds) {
        if (shuttingDown.get()) {
            return false;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("주문 확정 Outbox relay 실행 중이라 이번 호출을 건너뜁니다.");
            return false;
        }
        if (mode == AwaitMode.CALLBACK) {
            try {
                List<String> eventIds = eventIds(requestedEventIds);
                processBatchAsync(eventIds, 0).whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        log.error("주문 확정 Outbox callback relay 회차가 예기치 않게 끝났습니다.", unwrap(failure));
                    }
                    running.set(false);
                });
                return true;
            } catch (RuntimeException failure) {
                running.set(false);
                throw failure;
            }
        }
        try {
            return boundedRelay.runDueBatch();
        } finally {
            running.set(false);
        }
    }

    private List<String> eventIds(List<String> requestedEventIds) {
        return requestedEventIds == null
            ? stateService.findDueEventIds(applicationTime.now(), batchSize)
            : requestedEventIds;
    }

    private java.util.concurrent.CompletableFuture<Void> processBatchAsync(List<String> eventIds, int index) {
        if (shuttingDown.get() || index >= eventIds.size()) {
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
        if (retainPendingDuringShutdown(attempt)) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
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

    private boolean handlePublished(OrderConfirmedOutboxAttempt attempt,
                                    org.springframework.kafka.support.SendResult<Object, Object> result) {
        if (retainPendingDuringShutdown(attempt)) {
            return false;
        }
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
        if (retainPendingDuringShutdown(attempt)) {
            return false;
        }
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
                attempt, applicationTime.now(), safeError, failureClassifier.reason(failure));
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

    private boolean retainPendingDuringShutdown(OrderConfirmedOutboxAttempt attempt) {
        if (!shuttingDown.get()) {
            return false;
        }
        log.warn("종료 중인 주문 확정 Outbox 시도는 PENDING으로 보존합니다. eventId={}, orderId={}, attempt={}",
            attempt.eventId(), attempt.orderId(), attempt.attemptNumber());
        return true;
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
    public enum AwaitMode { BOUNDED_WAIT, CALLBACK }
}
