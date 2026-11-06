package com.loopers.application.outbox;

import com.loopers.domain.outbox.OutboxDeliveryAttempt;
import com.loopers.domain.outbox.OutboxReceipt;
import com.loopers.domain.outbox.OutboxSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** One runner per application instance. Deployment must enable only one instance, not a distributed lock. */
@Slf4j
public class OutboxRelay {
    private final OutboxDeliveryTransactions transactions;
    private final OutboxSender sender;
    private final Clock clock;
    private final OutboxRelaySettings settings;
    private final LongSupplier nanoTime;
    private final AtomicBoolean running = new AtomicBoolean();

    public OutboxRelay(OutboxDeliveryTransactions transactions, OutboxSender sender, Clock clock,
                       OutboxRelaySettings settings, LongSupplier nanoTime) {
        this.transactions = transactions;
        this.sender = sender;
        this.clock = clock;
        this.settings = settings;
        this.nanoTime = nanoTime;
    }

    public Result runOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox relay must run outside a caller transaction");
        }
        if (!running.compareAndSet(false, true)) {
            return new Result(0, 0, true);
        }
        int attempted = 0;
        int published = 0;
        try {
            var candidates = transactions.findDueIds(clock.instant(), settings.batchSize());
            for (Long id : candidates) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                // Commit the attempt before sending. Its provisional retry time survives a crash or a failed result TX.
                var prepared = transactions.beginAttempt(id, clock.instant(), settings.sendBudget());
                if (prepared.isEmpty()) {
                    continue;
                }
                var attempt = prepared.get();
                attempted++;
                log.info("outbox_attempt_started eventId={} key={} attempt={}",
                    attempt.eventId(), attempt.messageKey(), attempt.attemptCount());
                OutboxReceipt receipt;
                try {
                    receipt = sendAndAwait(attempt);
                } catch (InterruptedException exception) {
                    try {
                        defer(attempt, "INTERRUPTED");
                    } finally {
                        Thread.currentThread().interrupt();
                    }
                    break;
                } catch (TimeoutException exception) {
                    defer(attempt, "TIMEOUT");
                    break;
                } catch (ExecutionException | RuntimeException exception) {
                    defer(attempt, "SEND_FAILED");
                    break;
                }

                log.info("outbox_broker_ack eventId={} attempt={} topic={} partition={} offset={}",
                    attempt.eventId(), attempt.attemptCount(), receipt.topic(), receipt.partition(), receipt.offset());
                try {
                    if (!transactions.markPublished(attempt.id(), attempt.attemptCount(), clock.instant())) {
                        log.warn("outbox_result_not_applied eventId={} attempt={}", attempt.eventId(), attempt.attemptCount());
                        break;
                    }
                    published++;
                } catch (RuntimeException exception) {
                    // A commit exception can be ambiguous. Never blindly reset a possibly committed PUBLISHED row.
                    log.error("outbox_state_write_failed eventId={} attempt={} errorType={}",
                        attempt.eventId(), attempt.attemptCount(), exception.getClass().getSimpleName());
                    break;
                }
            }
        } catch (RuntimeException exception) {
            log.error("outbox_relay_round_failed errorType={}", exception.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
        return new Result(attempted, published, false);
    }

    private OutboxReceipt sendAndAwait(OutboxDeliveryAttempt attempt)
            throws InterruptedException, ExecutionException, TimeoutException {
        long started = nanoTime.getAsLong();
        var future = sender.send(attempt);
        long remaining = settings.sendBudget().toNanos() - (nanoTime.getAsLong() - started);
        if (remaining <= 0) {
            throw new TimeoutException("Outbox send budget elapsed");
        }
        var receipt = future.get(remaining, TimeUnit.NANOSECONDS);
        if (receipt == null || !attempt.topic().equals(receipt.topic())) {
            throw new IllegalStateException("Unexpected Outbox acknowledgement");
        }
        // No completion callback writes to DB. A late future can never mutate this or a later attempt's state.
        return receipt;
    }

    private void defer(OutboxDeliveryAttempt attempt, String errorCode) {
        log.warn("outbox_send_unconfirmed eventId={} attempt={} errorCode={}",
            attempt.eventId(), attempt.attemptCount(), errorCode);
        try {
            if (!transactions.markFailed(attempt.id(), attempt.attemptCount(), clock.instant(), errorCode)) {
                log.warn("outbox_retry_not_applied eventId={} attempt={}", attempt.eventId(), attempt.attemptCount());
            }
        } catch (RuntimeException exception) {
            // The attempt's already committed retry deadline is the restart fallback.
            log.error("outbox_retry_write_failed eventId={} attempt={} errorType={}",
                attempt.eventId(), attempt.attemptCount(), exception.getClass().getSimpleName());
        }
    }

    public record Result(int attempted, int published, boolean skipped) { }
}
