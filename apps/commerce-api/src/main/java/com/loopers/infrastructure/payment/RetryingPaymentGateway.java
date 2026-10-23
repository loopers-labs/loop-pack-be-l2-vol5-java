package com.loopers.infrastructure.payment;

import com.loopers.application.payment.PaymentGateway;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

@Component
@Primary
public class RetryingPaymentGateway implements PaymentGateway {
    private final CircuitBreakingPaymentGateway delegate;
    private final RetryConfig retryConfig;
    private final long retryWaitNanos;
    private final long totalTimeoutNanos;

    public RetryingPaymentGateway(
            CircuitBreakingPaymentGateway delegate,
            @Value("${payment-provider.lookup-retry.max-attempts}") int maxAttempts,
            @Value("${payment-provider.lookup-retry.wait-duration}") Duration retryWait,
            @Value("${payment-provider.lookup-retry.total-timeout}") Duration totalTimeout) {
        if (retryWait.isNegative() || retryWait.toMillis() < 1 || totalTimeout.isNegative() || totalTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("PG 조회 재시도 간격과 총 대기 예산은 1ms 이상이어야 합니다.");
        }
        this.delegate = delegate;
        retryWaitNanos = retryWait.toNanos();
        totalTimeoutNanos = totalTimeout.toNanos();
        retryConfig = RetryConfig.custom().maxAttempts(maxAttempts).waitDuration(retryWait).build();
    }

    @Override
    public CreatedPayment create(String providerOrderId, long amount, String cardType, String cardNo) {
        return delegate.create(providerOrderId, amount, cardType, cardNo);
    }

    @Override
    public List<ObservedPayment> findByOrderId(String providerOrderId) {
        return executeLookup(budget -> delegate.findByOrderId(providerOrderId, budget));
    }

    @Override
    public ObservedPayment findByTransactionKey(String transactionKey) {
        return executeLookup(budget -> delegate.findByTransactionKey(transactionKey, budget));
    }

    private <T> T executeLookup(Function<Duration, T> lookup) {
        long startedAt = System.nanoTime();
        var config = RetryConfig.from(retryConfig)
                .retryOnException(error -> isRetryable(error)
                        && remainingNanos(startedAt) - retryWaitNanos >= TimeUnit.MILLISECONDS.toNanos(1))
                .build();
        var retry = Retry.of("pg-payment-lookup", config);
        return retry.executeSupplier(() -> lookup.apply(remainingTimeout(startedAt)));
    }

    private Duration remainingTimeout(long startedAt) {
        if (Thread.currentThread().isInterrupted()) {
            throw new UnconfirmedException(new InterruptedIOException("PG 조회가 중단됐습니다."));
        }
        long remaining = remainingNanos(startedAt);
        if (remaining < TimeUnit.MILLISECONDS.toNanos(1)) {
            throw new UnconfirmedException(new TimeoutException("PG 조회의 총 대기 예산을 소진했습니다."));
        }
        return Duration.ofNanos(remaining);
    }

    private long remainingNanos(long startedAt) {
        return totalTimeoutNanos - (System.nanoTime() - startedAt);
    }

    private static boolean isRetryable(Throwable error) {
        if (Thread.currentThread().isInterrupted() || !(error instanceof UnconfirmedException)) {
            return false;
        }
        Throwable cause = error.getCause();
        if (cause instanceof ResourceAccessException || cause instanceof CancellationException) {
            return true;
        }
        if (cause instanceof HttpServerErrorException responseFailure) {
            int status = responseFailure.getStatusCode().value();
            return status == 502 || status == 503 || status == 504;
        }
        return false;
    }
}
