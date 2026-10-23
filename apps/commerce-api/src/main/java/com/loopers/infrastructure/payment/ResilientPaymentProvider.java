package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentObservation.UnknownCause;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.payment.PaymentRequest;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import java.time.Clock;
import java.util.function.Supplier;

/**
 * 조회 GET만 감싼다. 순서는 바깥부터 fallback → retry → 회로 → GET이다.
 * <ul>
 *   <li>retry: 일시적 장애(timeout·연결 실패·5xx)일 때 최초 포함 최대 횟수까지. 남은 예산에 다음 쉬는 시간과
 *   조회 한 번의 추정 시간이 들어가지 않으면 횟수가 남아도 멈춘다. 진행 중인 호출을 예산으로 중단하지는 않는다.</li>
 *   <li>회로: GET마다 결과를 센다. 일시적 장애만 실패이고, 대답을 들은 결과(FAILED·PENDING·0건)와
 *   예상 밖 응답은 실패로 세지 않는다. 회로가 막은 호출은 다시 시도하지 않는다.</li>
 *   <li>fallback: 회로가 막으면 PG에 묻지 않고 CIRCUIT_OPEN을 돌려준다.</li>
 * </ul>
 * 생성 POST는 감싸지 않는다(ADR 0028). 숫자는 {@link LookupResilienceSettings}로 받는다.
 */
public class ResilientPaymentProvider implements PaymentProvider {

    private final PaymentProvider delegate;
    private final LookupResilienceSettings settings;
    private final CircuitBreaker lookupCircuitBreaker;

    public ResilientPaymentProvider(PaymentProvider delegate, LookupResilienceSettings settings, Clock clock) {
        this.delegate = delegate;
        this.settings = settings;
        this.lookupCircuitBreaker = CircuitBreaker.of("pgLookup", CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(settings.windowSize())
            .minimumNumberOfCalls(settings.minimumCalls())
            .failureRateThreshold(settings.failureRateThreshold())
            .waitDurationInOpenState(settings.openWait())
            .permittedNumberOfCallsInHalfOpenState(settings.halfOpenCalls())
            .automaticTransitionFromOpenToHalfOpenEnabled(false)
            .recordResult(ResilientPaymentProvider::isTemporaryFailure)
            .clock(clock)
            .build());
    }

    @Override
    public PaymentObservation create(PaymentRequest request) {
        return delegate.create(request);
    }

    @Override
    public PaymentObservation findByTransactionKey(String transactionKey) {
        return lookup(() -> delegate.findByTransactionKey(transactionKey));
    }

    @Override
    public PaymentObservation findByOrderId(String providerOrderId) {
        return lookup(() -> delegate.findByOrderId(providerOrderId));
    }

    /** 두 조회 경로가 함께 쓰는 회로. 상태 확인과 테스트 초기화에 쓴다. */
    public CircuitBreaker lookupCircuitBreaker() {
        return lookupCircuitBreaker;
    }

    private PaymentObservation lookup(Supplier<PaymentObservation> call) {
        long deadline = System.nanoTime() + settings.budget().toNanos();
        Retry retry = Retry.of("pgLookup", RetryConfig.<PaymentObservation>custom()
            .maxAttempts(settings.maxAttempts())
            .intervalFunction(IntervalFunction.ofExponentialBackoff(settings.firstWait(), settings.waitMultiplier()))
            .retryOnResult(observation -> isTemporaryFailure(observation) && hasTimeForAnotherAttempt(deadline))
            .ignoreExceptions(CallNotPermittedException.class)
            .build());
        try {
            return retry.executeSupplier(() -> lookupCircuitBreaker.executeSupplier(call));
        } catch (CallNotPermittedException exception) {
            return PaymentObservation.unknown(UnknownCause.CIRCUIT_OPEN);
        }
    }

    private static boolean isTemporaryFailure(Object result) {
        return result instanceof PaymentObservation observation
            && observation.unknownCause() == UnknownCause.TEMPORARY_FAILURE;
    }

    private boolean hasTimeForAnotherAttempt(long deadline) {
        return deadline - System.nanoTime() >= settings.longestWait().plus(settings.attemptEstimate()).toNanos();
    }
}
