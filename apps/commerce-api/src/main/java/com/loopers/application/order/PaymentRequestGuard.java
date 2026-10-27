package com.loopers.application.order;

import com.loopers.application.order.PaymentGatewayResult.Outcome;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 결제 요청 보호. confirmOrder 의 "백업 거래 조회 예약 + PG 결제 요청" 을 Bulkhead(CircuitBreaker(결제 요청)) 로 감싼다.
 * 세마포어 permit 이 없거나(기다리지 않는다) 서킷이 열려 있으면 결제 요청을 실행하지 않고 NOT_SENT 를 돌려준다. 거래 조회도 예약되지 않는다.
 * 서킷 실패는 결과로 센다: ERROR·NOT_SENT·UNKNOWN. PG 의 거절(FAILED)·PENDING·SUCCESS 는 실패가 아니다. 세마포어 거절은 서킷 밖이라 세지 않는다.
 * permit 은 결제 요청이 끝날 때(최대 pg.read-timeout)까지 잡는다.
 */
@Slf4j
@Component
public class PaymentRequestGuard {
    private static final String NAME = "pg-payment";
    private static final Set<Outcome> FAILURES = EnumSet.of(Outcome.ERROR, Outcome.NOT_SENT, Outcome.UNKNOWN);

    private final Bulkhead bulkhead;
    private final CircuitBreaker circuitBreaker;

    public PaymentRequestGuard(PaymentRequestGuardProperties properties) {
        // 고객이 기다리는 한계(10초) 중 9초를 결제 요청이 쓰므로 permit 을 기다리지 않는다.
        this.bulkhead = Bulkhead.of(NAME, BulkheadConfig.custom()
            .maxConcurrentCalls(properties.bulkhead().maxConcurrentCalls())
            .maxWaitDuration(Duration.ZERO)
            .build());
        PaymentRequestGuardProperties.CircuitBreaker circuit = properties.circuitBreaker();
        this.circuitBreaker = CircuitBreaker.of(NAME, CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(circuit.slidingWindowSize())
            .minimumNumberOfCalls(circuit.minimumNumberOfCalls())
            .failureRateThreshold(circuit.failureRateThreshold())
            .slowCallDurationThreshold(circuit.slowCallDurationThreshold())
            .slowCallRateThreshold(circuit.slowCallRateThreshold())
            .waitDurationInOpenState(circuit.waitDurationInOpenState())
            .permittedNumberOfCallsInHalfOpenState(circuit.permittedNumberOfCallsInHalfOpenState())
            .recordResult(result -> result instanceof PaymentGatewayResult r && FAILURES.contains(r.outcome()))
            .build());
    }

    /** permit 을 얻고 서킷이 허락하면 request 를 실행해 그 결과를, 아니면 실행하지 않고 NOT_SENT 를 돌려준다. */
    public PaymentGatewayResult run(Long orderId, Supplier<PaymentGatewayResult> request) {
        try {
            return Bulkhead.decorateSupplier(bulkhead, CircuitBreaker.decorateSupplier(circuitBreaker, request)).get();
        } catch (BulkheadFullException e) {
            log.warn("PG 결제 요청 동시 실행 한도 초과, 요청 미전송 [orderId = {}]", orderId);
            return PaymentGatewayResult.notSent();
        } catch (CallNotPermittedException e) {
            log.warn("PG 서킷 열림, 요청 미전송 [orderId = {}, state = {}]", orderId, circuitBreaker.getState());
            return PaymentGatewayResult.notSent();
        }
    }
}
