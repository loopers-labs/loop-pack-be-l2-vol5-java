package com.loopers.infrastructure.payment;

import com.loopers.application.payment.PaymentGatewayUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * PG 조회 경로의 circuit breaker(docs/week5/design.md D-029, recovery.md 4번). create에는 걸지 않는다 — 과제가 조회 한 경로에만 적용한다.
 *
 * - 실패로 세는 것: 물어보지 못한 조회(timeout·5xx·깨진 응답)뿐이다. 404·빈 목록은 정상 응답이라 세지 않는다 — 세면 정상 PG에서도 열린다.
 * - 표본 하나는 "재시도까지 끝난 조회 한 번"이다. circuit breaker가 retry를 감싼다(PgPaymentGateway).
 * - 빈으로 두는 이유: 상태를 한 곳에서 공유하고, 테스트가 테스트마다 상태를 초기화할 수 있게 한다.
 */
@Configuration
public class PgLookupCircuitBreakerConfig {

    @Bean
    public CircuitBreaker pgLookupCircuitBreaker(
        @Value("${pg.lookup.circuit-breaker.sliding-window-size:20}") int slidingWindowSize,
        @Value("${pg.lookup.circuit-breaker.minimum-number-of-calls:20}") int minimumNumberOfCalls,
        @Value("${pg.lookup.circuit-breaker.failure-rate-threshold:50}") float failureRateThreshold,
        @Value("${pg.lookup.circuit-breaker.wait-duration-in-open-state:30s}") Duration waitDurationInOpenState,
        @Value("${pg.lookup.circuit-breaker.permitted-calls-in-half-open-state:3}") int permittedCallsInHalfOpenState
    ) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(slidingWindowSize)
            .minimumNumberOfCalls(minimumNumberOfCalls)
            .failureRateThreshold(failureRateThreshold)
            .waitDurationInOpenState(waitDurationInOpenState)
            .permittedNumberOfCallsInHalfOpenState(permittedCallsInHalfOpenState)
            .recordExceptions(PaymentGatewayUnavailableException.class)
            .build();
        return CircuitBreaker.of("pg-lookup", config);
    }
}
