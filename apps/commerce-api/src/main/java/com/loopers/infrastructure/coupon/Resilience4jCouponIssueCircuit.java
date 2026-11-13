package com.loopers.infrastructure.coupon;

import com.loopers.config.coupon.CouponAdmissionProperties;
import com.loopers.domain.coupon.CouponIssueCircuit;
import com.loopers.support.error.CoreException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Resilience4j core 로 조립한 DB 발급 서킷 (8주차 설계 §7-4). HttpPaymentGateway 와 같이 스타터·애노테이션 없이 코드로 만든다.
 * 허가와 결과 기록을 나누어 두는 이유는 "허가는 세마포어 전에, DB 호출은 세마포어 뒤에" 일어나기 때문이다 (CouponIssueCircuit).
 */
@Component
public class Resilience4jCouponIssueCircuit implements CouponIssueCircuit {

    static final String NAME = "coupon-issue-db";

    private final CircuitBreaker circuitBreaker;
    private final Clock clock;
    private volatile Instant lastLeftOpenAt;

    public Resilience4jCouponIssueCircuit(CouponAdmissionProperties properties, Clock clock) {
        this.clock = clock;
        CouponAdmissionProperties.DbCircuit settings = properties.dbCircuit();
        this.circuitBreaker = CircuitBreaker.of(NAME, CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(settings.slidingWindowSize())
            .minimumNumberOfCalls(settings.minimumCalls())
            .failureRateThreshold(settings.failureRateThreshold())
            .waitDurationInOpenState(settings.waitDurationInOpen())
            .permittedNumberOfCallsInHalfOpenState(settings.permittedCallsInHalfOpen())
            // 열림 대기가 지나면 호출 없이도 HALF_OPEN 으로 바뀐다. 승격 스케줄러는 state() 만 보고 건너뛰므로(§7-4 3번),
            // 이 전이가 호출에 의존하면 "승격 중단 → claim 없음 → 전이 없음" 으로 영구히 멈춘다
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .recordException(cause -> cause instanceof DataAccessException)
            .ignoreExceptions(CoreException.class)
            .clock(clock)
            .build());
        // OPEN 에서 벗어난 시각을 기억한다. 승격 만료 유예(admittedCutoffMillis)의 기준이다
        this.circuitBreaker.getEventPublisher().onStateTransition(event -> {
            if (event.getStateTransition().getFromState() == CircuitBreaker.State.OPEN) {
                lastLeftOpenAt = this.clock.instant();
            }
        });
    }

    @Override
    public Optional<Instant> lastLeftOpenAt() {
        return Optional.ofNullable(lastLeftOpenAt);
    }

    @Override
    public State state() {
        return switch (circuitBreaker.getState()) {
            case OPEN, FORCED_OPEN -> State.OPEN;
            case HALF_OPEN -> State.HALF_OPEN;
            case CLOSED, DISABLED, METRICS_ONLY -> State.CLOSED;
        };
    }

    @Override
    public boolean tryAcquire() {
        return circuitBreaker.tryAcquirePermission();
    }

    @Override
    public void release() {
        circuitBreaker.releasePermission();
    }

    @Override
    public void recordSuccess() {
        circuitBreaker.onSuccess(0, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordFailure(Throwable cause) {
        circuitBreaker.onError(0, TimeUnit.NANOSECONDS, cause);
    }
}
