package com.loopers.domains.payment.out;

import com.loopers.domains.payment.app.port.PaymentGateway;
import com.loopers.domains.payment.app.port.PaymentLookupCircuitState;
import com.loopers.domains.payment.app.port.PaymentTransactionsLookup;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class PaymentCircuitBreakers implements PaymentLookupCircuitState {
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final Clock clock;
    private final Duration openDuration;
    private final Map<String, Instant> openedAt = new ConcurrentHashMap<>();

    public PaymentCircuitBreakers(Clock clock, PaymentLookupCircuitProperties properties) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(properties.slidingWindowSize())
            .minimumNumberOfCalls(properties.minimumNumberOfCalls())
            .failureRateThreshold(properties.failureRateThreshold())
            .waitDurationInOpenState(properties.openDuration())
            .permittedNumberOfCallsInHalfOpenState(properties.halfOpenCalls())
            // 자동 전환은 별도 실제 시간 스레드를 사용하므로 주입 Clock으로 호출 시 전환하게 한다.
            .automaticTransitionFromOpenToHalfOpenEnabled(false)
            .clock(clock)
            .recordResult(result -> result instanceof PaymentTransactionsLookup.Failed)
            .build();
        circuitBreakerRegistry = CircuitBreakerRegistry.of(config);
        // 자동 전환이 꺼져 있어 대기 시간이 지나도 다음 호출 전까지 상태가 OPEN으로 남는다. 열린 시각을 주입 Clock으로 따로 기록한다.
        circuitBreakerRegistry.getEventPublisher().onEntryAdded(added -> added.getAddedEntry().getEventPublisher()
            .onStateTransition(transition -> {
                if (transition.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                    openedAt.put(transition.getCircuitBreakerName(), clock.instant());
                }
            }));
        this.clock = clock;
        this.openDuration = properties.openDuration();
    }

    public PaymentGateway wrap(PaymentGateway gateway) {
        return new CircuitBreakingPaymentGateway(gateway, circuitBreakerRegistry.circuitBreaker(gateway.providerId()));
    }

    @Override
    public boolean blocksLookup(String providerId) {
        Optional<CircuitBreaker> found = circuitBreakerRegistry.find(providerId);
        if (found.isEmpty() || found.get().getState() != CircuitBreaker.State.OPEN) {
            return false;
        }
        Instant opened = openedAt.get(providerId);
        return opened != null && clock.instant().isBefore(opened.plus(openDuration));
    }
}
