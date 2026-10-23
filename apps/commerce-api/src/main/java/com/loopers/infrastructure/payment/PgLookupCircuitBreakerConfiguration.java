package com.loopers.infrastructure.payment;

import com.loopers.application.payment.PaymentLookupObservation;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PgLookupCircuitBreakerProperties.class)
@ConditionalOnProperty(prefix = "payment.pg.lookup-circuit-breaker", name = "enabled", havingValue = "true")
public class PgLookupCircuitBreakerConfiguration {

    @Bean
    public CircuitBreaker pgLookupCircuitBreaker(PgLookupCircuitBreakerProperties properties) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(properties.slidingWindowSize())
            .minimumNumberOfCalls(properties.minimumNumberOfCalls())
            .failureRateThreshold(properties.failureRateThreshold())
            .waitDurationInOpenState(properties.waitDurationInOpenState())
            .permittedNumberOfCallsInHalfOpenState(properties.permittedNumberOfCallsInHalfOpenState())
            // 첫 실험은 HTTP timeout 실패율을 관찰한다. 500ms HTTP 예산보다 충분히 길게 둔다.
            .slowCallDurationThreshold(Duration.ofSeconds(60))
            .slowCallRateThreshold(100)
            .automaticTransitionFromOpenToHalfOpenEnabled(false)
            .ignoreExceptions(PgLookupPolicyException.LocalRejection.class)
            .recordResult(result -> result instanceof PaymentLookupObservation.Unavailable)
            .build();
        CircuitBreaker circuitBreaker = CircuitBreaker.of("pgLookup", config);
        circuitBreaker.getEventPublisher().onStateTransition(event ->
            log.info("PG 조회 회로 상태 전이. name={}, transition={}", event.getCircuitBreakerName(), event.getStateTransition()));
        return circuitBreaker;
    }
}
