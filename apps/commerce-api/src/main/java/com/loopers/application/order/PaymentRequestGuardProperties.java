package com.loopers.application.order;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** application.yml pg.bulkhead.*·pg.circuit-breaker.*. 결제 요청 보호 설정 (PaymentRequestGuard). */
@ConfigurationProperties("pg")
public record PaymentRequestGuardProperties(Bulkhead bulkhead, CircuitBreaker circuitBreaker) {

    /** maxConcurrentCalls: PG 결제 요청을 동시에 기다릴 수 있는 워커 스레드 수. */
    public record Bulkhead(int maxConcurrentCalls) {
    }

    /** 최근 slidingWindowSize 건(minimumNumberOfCalls 건 이상)의 실패율·느린 호출 비율로 연다. */
    public record CircuitBreaker(int slidingWindowSize, int minimumNumberOfCalls, float failureRateThreshold,
                                 Duration slowCallDurationThreshold, float slowCallRateThreshold,
                                 Duration waitDurationInOpenState, int permittedNumberOfCallsInHalfOpenState) {
    }
}
