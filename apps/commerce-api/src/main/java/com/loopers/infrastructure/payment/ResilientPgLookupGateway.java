package com.loopers.infrastructure.payment;

import com.loopers.application.payment.PaymentCreateCommand;
import com.loopers.application.payment.PaymentCreateObservation;
import com.loopers.application.payment.PaymentGateway;
import com.loopers.application.payment.PaymentLookupObservation;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

class ResilientPgLookupGateway implements PaymentGateway {
    private final PaymentGateway delegate;
    private final PgLookupPolicyProperties properties;
    private final ExecutorService executor;
    private final Retry retry;
    private final RateLimiter rateLimiter;
    private final TimeLimiter timeLimiter;

    ResilientPgLookupGateway(PaymentGateway delegate, PgLookupPolicyProperties properties, ExecutorService executor) {
        this.delegate = delegate;
        this.properties = properties;
        this.executor = executor;
        PgLookupPolicyProperties.RetrySettings retrySettings = properties.retry();
        this.retry = Retry.of("pgLookup", RetryConfig.<PaymentLookupObservation>custom()
            .maxAttempts(retrySettings.maxAttempts())
            .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                retrySettings.initialWait(), retrySettings.multiplier(), retrySettings.jitter()))
            .retryOnException(exception -> false)
            .retryOnResult(result -> result instanceof PaymentLookupObservation.Unavailable unavailable
                && unavailable.kind().retryable())
            .build());
        this.rateLimiter = RateLimiter.of("pgLookup", RateLimiterConfig.custom()
            .limitForPeriod(properties.rateLimiter().limitForPeriod())
            .limitRefreshPeriod(properties.rateLimiter().refreshPeriod())
            .timeoutDuration(Duration.ZERO).build());
        this.timeLimiter = TimeLimiter.of("pgLookup", TimeLimiterConfig.custom()
            .timeoutDuration(properties.timeLimiter().timeout()).cancelRunningFuture(true).build());
    }

    @Override
    public PaymentCreateObservation create(PaymentCreateCommand command) {
        return delegate.create(command);
    }

    @Override
    public PaymentLookupObservation lookup(String transactionKey) {
        return execute(() -> delegate.lookup(transactionKey));
    }

    @Override
    public PaymentLookupObservation lookupByOrder(String orderId) {
        return execute(() -> delegate.lookupByOrder(orderId));
    }

    private PaymentLookupObservation execute(Supplier<PaymentLookupObservation> http) {
        AtomicBoolean cancelled = new AtomicBoolean();
        Supplier<PaymentLookupObservation> attempt = () -> {
            checkCancellation(cancelled);
            if (properties.rateLimiter().enabled() && !rateLimiter.acquirePermission()) {
                throw new PgLookupPolicyException.LocalRejection(PaymentLookupObservation.FailureKind.RATE_LIMITED);
            }
            PaymentLookupObservation observation = http.get();
            checkCancellation(cancelled);
            if (observation instanceof PaymentLookupObservation.Unavailable unavailable
                && unavailable.kind() == PaymentLookupObservation.FailureKind.CANCELLED) {
                throw new PgLookupPolicyException.LocalRejection(PaymentLookupObservation.FailureKind.CANCELLED);
            }
            return observation;
        };
        Supplier<PaymentLookupObservation> work = properties.retry().enabled()
            ? Retry.decorateSupplier(retry, attempt) : attempt;
        if (!properties.timeLimiter().enabled()) {
            try {
                return work.get();
            } catch (CancellationException exception) {
                Thread.currentThread().interrupt();
                throw new PgLookupPolicyException.LocalRejection(PaymentLookupObservation.FailureKind.CANCELLED);
            }
        }
        Future<PaymentLookupObservation> future = executor.submit(work::get);
        try {
            return timeLimiter.executeFutureSupplier(() -> future);
        } catch (TimeoutException exception) {
            cancelled.set(true);
            future.cancel(true);
            throw new PgLookupPolicyException.DeadlineExceeded();
        } catch (InterruptedException exception) {
            cancelled.set(true);
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new PgLookupPolicyException.LocalRejection(PaymentLookupObservation.FailureKind.CANCELLED);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("PG lookup task failed", exception);
        }
    }

    private void checkCancellation(AtomicBoolean cancelled) {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new PgLookupPolicyException.LocalRejection(PaymentLookupObservation.FailureKind.CANCELLED);
        }
    }
}
