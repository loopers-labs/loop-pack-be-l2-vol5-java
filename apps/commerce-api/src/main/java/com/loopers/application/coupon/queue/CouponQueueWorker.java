package com.loopers.application.coupon.queue;

import com.loopers.application.coupon.CouponIssuanceTransactions;
import com.loopers.application.coupon.CouponIssuanceTransactions.Completion;
import com.loopers.domain.coupon.CouponAlreadyIssuedException;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One worker per JVM. Uncertain claims remain in processing for manual reconciliation. */
@Component
@ConditionalOnProperty(name = "coupon.queue.worker.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class CouponQueueWorker {
    private final CouponRequestQueue queue;
    private final CouponIssuanceTransactions issuance;
    private final CouponQueueWorkerState state;
    private final long pollDelayMillis;
    private final String workerId = UUID.randomUUID().toString();

    private ScheduledExecutorService executor;
    private CouponRequestQueue.Claim currentClaim;
    private boolean pendingCompletion;
    private boolean pendingSoldOut;
    private long retryDeadlineNanos;
    private int currentAttempt;

    public CouponQueueWorker(CouponRequestQueue queue, CouponIssuanceTransactions issuance, CouponQueueWorkerState state,
                             @Value("${coupon.queue.worker.poll-delay-ms:250}") long pollDelayMillis) {
        if (pollDelayMillis < 1) {
            throw new IllegalArgumentException("coupon queue poll delay must be positive");
        }
        this.queue = queue;
        this.issuance = issuance;
        this.state = state;
        this.pollDelayMillis = pollDelayMillis;
    }

    @PostConstruct
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "coupon-queue-worker");
            thread.setDaemon(true);
            return thread;
        });
        log.info("Coupon queue worker started. workerId={}, accepting={}", workerId, state.acceptingNewRequests());
        executor.schedule(this::scheduledTick, 0, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Coupon worker is still processing during shutdown; retain any claim for reconciliation.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void scheduledTick() {
        boolean completed = runOnce();
        if (state.processingAllowed() && !executor.isShutdown()) {
            try {
                executor.schedule(this::scheduledTick, completed ? 0 : nextDelayMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException shutdownRace) {
                log.debug("Coupon worker stopped while scheduling the next poll.", shutdownRace);
            }
        }
    }

    private long nextDelayMillis() {
        if (retryDeadlineNanos == 0) {
            return pollDelayMillis;
        }
        long remainingNanos = retryDeadlineNanos - System.nanoTime();
        return remainingNanos <= 0 ? 0 : Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    boolean runOnce() {
        if (!state.processingAllowed()) {
            return false;
        }
        if (pendingCompletion) {
            completeClaim();
            return currentClaim == null;
        }
        if (currentClaim == null) {
            try {
                currentClaim = queue.claim(workerId).orElse(null);
                if (currentClaim != null) {
                    log.info("Coupon queue request claimed. workerId={}, requestId={}, campaignId={}, userId={}",
                            workerId, currentClaim.requestId(), currentClaim.campaignId(), currentClaim.userId());
                    scheduleDelay(currentClaim.retryDelayMillis());
                }
            } catch (RuntimeException unavailable) {
                // The claim may have succeeded even when its reply was lost.
                state.pause();
                log.error("Coupon worker claim result is unknown; stop for reconciliation. workerId={}", workerId, unavailable);
                return false;
            }
        }
        if (currentClaim == null) {
            return false;
        }
        // A Redis failure on this JVM may have closed admission while this claim was in flight.
        if (!state.processingAllowed()) {
            return false;
        }

        if (retryDeadlineNanos != 0 && System.nanoTime() - retryDeadlineNanos < 0) {
            return false;
        }
        retryDeadlineNanos = 0;

        try {
            var reservation = queue.reserveAttempt(currentClaim);
            if (reservation.exhausted()) {
                // Exhaustion was recorded only after the last attempt's confirmed rollback.
                log.warn("Coupon DB retries exhausted. requestId={}", currentClaim.requestId());
                finishClaim(false);
                return currentClaim == null;
            }
            if (reservation.delayMillis() > 0) {
                scheduleDelay(reservation.delayMillis());
                return false;
            }
            currentAttempt = reservation.attemptCount();
        } catch (RuntimeException unavailable) {
            // The reservation may have succeeded even when its Redis response was lost.
            pauseForUnknownOutcome(unavailable);
            return false;
        }
        if (!state.processingAllowed()) {
            // Retain the reserved attempt for reconciliation if another thread closed admission.
            return false;
        }

        var completion = new Completion();
        try {
            issuance.issue(currentClaim.campaignId(), currentClaim.userId(), completion);
            // The coordinator returns only after the read or write transaction is complete.
            finishClaim(completion.soldOutDetected());
        } catch (CouponAlreadyIssuedException duplicate) {
            // The coordinator already tried to recover the existing coupon after rollback.
            pauseForUnknownOutcome(duplicate);
        } catch (CoreException businessFailure) {
            if (!completion.safeToRetry()) {
                pauseForUnknownOutcome(businessFailure);
                return false;
            }
            if (businessFailure.getErrorType() == ErrorType.COUPON_SOLD_OUT) {
                finishClaim(true);
            } else if (businessFailure.getErrorType() == ErrorType.COUPON_CAMPAIGN_INACTIVE
                    || businessFailure.getErrorType() == ErrorType.COUPON_CAMPAIGN_NOT_FOUND) {
                log.info("Coupon request ended without issuance. requestId={}, reason={}",
                        currentClaim.requestId(), businessFailure.getErrorType());
                finishClaim(false);
            } else {
                pauseForUnknownOutcome(businessFailure);
            }
        } catch (RuntimeException failure) {
            if (completion.writeRolledBack() && isRetryableMySqlConflict(failure)) {
                log.warn("Coupon issuance rolled back on MySQL lock contention. workerId={}, requestId={}, campaignId={}, userId={}, attempt={}",
                        workerId, currentClaim.requestId(), currentClaim.campaignId(), currentClaim.userId(), currentAttempt, failure);
                scheduleRetry(failure);
            } else {
                pauseForUnknownOutcome(failure);
            }
        }
        return currentClaim == null;
    }

    private void scheduleRetry(RuntimeException failure) {
        try {
            var schedule = queue.scheduleRetry(currentClaim);
            if (schedule.exhausted()) {
                log.warn("Coupon DB retries exhausted. requestId={}", currentClaim.requestId());
                finishClaim(false);
            } else {
                scheduleDelay(schedule.delayMillis());
                log.warn("Coupon transaction rolled back due to MySQL lock contention; retry after {} ms. requestId={}",
                        schedule.delayMillis(), currentClaim.requestId(), failure);
            }
        } catch (RuntimeException unavailable) {
            // The scheduling command may have succeeded even if its response was lost.
            pauseForUnknownOutcome(unavailable);
        }
    }

    private void scheduleDelay(long delayMillis) {
        retryDeadlineNanos = delayMillis <= 0 ? 0 : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
    }

    private static boolean isRetryableMySqlConflict(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<SQLException> seenSql = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                for (SQLException current = sql; current != null && seenSql.add(current); current = current.getNextException()) {
                    if (current.getErrorCode() == 1213 || current.getErrorCode() == 1205) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void finishClaim(boolean soldOut) {
        pendingCompletion = true;
        pendingSoldOut = soldOut;
        completeClaim();
    }

    private void completeClaim() {
        try {
            if (pendingSoldOut) {
                // The DB confirmed exhaustion. Close new admission before acknowledging this claim.
                queue.markSoldOut(currentClaim.campaignId());
            }
            queue.complete(currentClaim);
            currentClaim = null;
            pendingCompletion = false;
            pendingSoldOut = false;
            retryDeadlineNanos = 0;
            currentAttempt = 0;
        } catch (CouponQueueInconsistentStateException conflict) {
            state.pause();
            log.error("Coupon completion state is inconsistent; stop for reconciliation. workerId={}, requestId={}, campaignId={}, userId={}",
                    workerId, currentClaim.requestId(), currentClaim.campaignId(), currentClaim.userId(), conflict);
        } catch (RuntimeException unavailable) {
            // A lost reply may mean the claim was already removed; stop for reconciliation.
            state.pause();
            log.warn("Coupon completion could not be confirmed; stop for reconciliation. workerId={}, requestId={}, campaignId={}, userId={}",
                    workerId, currentClaim.requestId(), currentClaim.campaignId(), currentClaim.userId(), unavailable);
        }
    }

    private void pauseForUnknownOutcome(RuntimeException failure) {
        state.pause();
        log.error("Coupon transaction outcome is unknown; retain claim and stop for reconciliation. workerId={}, requestId={}, campaignId={}, userId={}",
                workerId, currentClaim.requestId(), currentClaim.campaignId(), currentClaim.userId(), failure);
    }
}
