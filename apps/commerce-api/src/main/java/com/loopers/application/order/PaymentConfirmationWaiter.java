package com.loopers.application.order;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** First-dispatch-only bounded observation; this never starts a payment or converts uncertainty to failure. */
public final class PaymentConfirmationWaiter {
    private static final int MAX_LOOKUP_ATTEMPTS = 12;
    private final PaymentConfirmationTiming timing;

    public PaymentConfirmationWaiter(PaymentConfirmationTiming timing) {
        this.timing = Objects.requireNonNull(timing);
    }

    public <T> T await(PaymentConfirmationDeadline deadline, Supplier<T> stored, Predicate<T> terminal,
                      Supplier<LookupAttempt<T>> lookup) {
        requireOutsideTransaction();
        int attempts = 0;
        int rounds = 0;
        while (attempts < MAX_LOOKUP_ATTEMPTS && deadline.permitsLookup()) {
            T current = stored.get();
            if (terminal.test(current) || Thread.currentThread().isInterrupted() || !deadline.permitsLookup()) {
                return current;
            }
            if (rounds > 0) {
                Duration delay = deadline.waitBeforeLookup(nextDelay(rounds));
                if (delay.isZero()) {
                    break;
                }
                requireOutsideTransaction();
                try {
                    timing.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                current = stored.get();
            }
            if (terminal.test(current) || Thread.currentThread().isInterrupted() || !deadline.permitsLookup()) {
                return current;
            }
            requireOutsideTransaction();
            LookupAttempt<T> observed = lookup.get();
            rounds++;
            if (observed.attempted()) {
                attempts++;
            }
            if (terminal.test(observed.result())) {
                break;
            }
        }
        // A concurrent callback may have committed even if the last HTTP result or time check was nonterminal.
        return stored.get();
    }

    private Duration nextDelay(int rounds) {
        double factor = timing.jitterFactor();
        if (!Double.isFinite(factor) || factor < 0.8 || factor > 1.2) {
            throw new IllegalStateException("Payment lookup jitter must remain between 0.8 and 1.2");
        }
        int seconds = rounds == 1 ? 5 : rounds == 2 ? 10 : 15;
        return Duration.ofNanos((long) (Duration.ofSeconds(seconds).toNanos() * factor));
    }

    private static void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
            || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Payment lookup waiting must run outside a database transaction");
        }
    }

    public record LookupAttempt<T>(T result, boolean attempted) {
        public LookupAttempt {
            Objects.requireNonNull(result);
        }
    }
}
