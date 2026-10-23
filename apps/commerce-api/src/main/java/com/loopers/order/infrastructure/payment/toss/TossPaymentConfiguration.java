package com.loopers.order.infrastructure.payment.toss;

import com.loopers.order.domain.PaymentGateway;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;

@Configuration
public class TossPaymentConfiguration {

    @Bean(name = "paymentApprovalCircuitBreaker")
    public CircuitBreaker paymentApprovalCircuitBreaker(
        @Value("${payment.resilience.circuit-breaker.sliding-window-seconds:30}") int slidingWindowSeconds,
        @Value("${payment.resilience.circuit-breaker.minimum-number-of-calls:20}") int minimumNumberOfCalls,
        @Value("${payment.resilience.circuit-breaker.failure-rate-threshold:70}") float failureRateThreshold,
        @Value("${payment.resilience.circuit-breaker.wait-duration-in-open-state:30s}") Duration waitDuration,
        @Value("${payment.resilience.circuit-breaker.permitted-number-of-calls-in-half-open-state:2}")
        int halfOpenCalls
    ) {
        return CircuitBreaker.of("toss-payment-approval", circuitBreakerConfig(
            slidingWindowSeconds, minimumNumberOfCalls, failureRateThreshold, waitDuration, halfOpenCalls));
    }

    @Bean(name = "paymentLookupCircuitBreaker")
    public CircuitBreaker paymentLookupCircuitBreaker(
        @Value("${payment.resilience.circuit-breaker.sliding-window-seconds:30}") int slidingWindowSeconds,
        @Value("${payment.resilience.circuit-breaker.minimum-number-of-calls:20}") int minimumNumberOfCalls,
        @Value("${payment.resilience.circuit-breaker.failure-rate-threshold:70}") float failureRateThreshold,
        @Value("${payment.resilience.circuit-breaker.wait-duration-in-open-state:30s}") Duration waitDuration,
        @Value("${payment.resilience.circuit-breaker.permitted-number-of-calls-in-half-open-state:2}")
        int halfOpenCalls
    ) {
        return CircuitBreaker.of("toss-payment-lookup", circuitBreakerConfig(
            slidingWindowSeconds, minimumNumberOfCalls, failureRateThreshold, waitDuration, halfOpenCalls));
    }

    @Bean(name = "paymentCancellationCircuitBreaker")
    public CircuitBreaker paymentCancellationCircuitBreaker(
        @Value("${payment.resilience.circuit-breaker.sliding-window-seconds:30}") int slidingWindowSeconds,
        @Value("${payment.resilience.circuit-breaker.minimum-number-of-calls:20}") int minimumNumberOfCalls,
        @Value("${payment.resilience.circuit-breaker.failure-rate-threshold:70}") float failureRateThreshold,
        @Value("${payment.resilience.circuit-breaker.wait-duration-in-open-state:30s}") Duration waitDuration,
        @Value("${payment.resilience.circuit-breaker.permitted-number-of-calls-in-half-open-state:2}")
        int halfOpenCalls
    ) {
        return CircuitBreaker.of("toss-payment-cancellation", circuitBreakerConfig(
            slidingWindowSeconds, minimumNumberOfCalls, failureRateThreshold, waitDuration, halfOpenCalls));
    }

    @Bean(name = "paymentPgRequestBulkhead")
    public Bulkhead paymentPgRequestBulkhead(
        @Value("${payment.resilience.bulkhead.max-concurrent-calls:150}") int maxConcurrentCalls,
        @Value("${server.tomcat.threads.max:200}") int tomcatMaxThreads
    ) {
        if (maxConcurrentCalls < 1 || maxConcurrentCalls >= tomcatMaxThreads) {
            throw new IllegalArgumentException("PG request Bulkhead must be positive and lower than Tomcat max threads");
        }
        return Bulkhead.of("toss-pg-request", BulkheadConfig.custom()
            .maxConcurrentCalls(maxConcurrentCalls)
            .maxWaitDuration(Duration.ZERO)
            .build());
    }

    @Bean
    public PaymentGateway paymentGateway(
        @Value("${payment.toss.base-url:https://api.tosspayments.com}") URI baseUrl,
        @Value("${payment.toss.secret-key:}") String secretKey,
        @Value("${payment.toss.connect-timeout:2s}") Duration connectTimeout,
        @Value("${payment.toss.read-timeout:3s}") Duration readTimeout,
        @Qualifier("paymentApprovalCircuitBreaker") CircuitBreaker approvalCircuitBreaker,
        @Qualifier("paymentLookupCircuitBreaker") CircuitBreaker lookupCircuitBreaker,
        @Qualifier("paymentCancellationCircuitBreaker") CircuitBreaker cancellationCircuitBreaker,
        @Qualifier("paymentPgRequestBulkhead") Bulkhead pgRequestBulkhead
    ) {
        return new TossPaymentAdapter(TossHttpClientFactory.create(
            baseUrl, secretKey, connectTimeout, readTimeout), approvalCircuitBreaker, lookupCircuitBreaker,
            cancellationCircuitBreaker, pgRequestBulkhead);
    }

    private CircuitBreakerConfig circuitBreakerConfig(
        int slidingWindowSeconds,
        int minimumNumberOfCalls,
        float failureRateThreshold,
        Duration waitDuration,
        int halfOpenCalls
    ) {
        return CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
            .slidingWindowSize(slidingWindowSeconds)
            .minimumNumberOfCalls(minimumNumberOfCalls)
            .failureRateThreshold(failureRateThreshold)
            .waitDurationInOpenState(waitDuration)
            .permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
            .recordResult(result -> isTechnicalFailure(result))
            .build();
    }

    private boolean isTechnicalFailure(Object result) {
        if (result instanceof PaymentGateway.Unknown unknown) {
            return unknown.rawResponse() == null || unknown.rawResponse().isBlank();
        }
        return result instanceof PaymentGateway.CancellationUnknown;
    }
}
