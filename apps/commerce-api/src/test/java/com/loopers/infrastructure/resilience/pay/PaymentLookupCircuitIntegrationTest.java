package com.loopers.infrastructure.resilience.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.loopers.application.pay.gateway.PaymentLookupGateway;
import com.loopers.application.pay.gateway.PaymentLookupGateway.LookupRequest;
import com.loopers.application.pay.gateway.PaymentProviderGateway;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentObservation;
import com.loopers.domain.pay.model.ProviderPaymentStatus;
import com.loopers.support.test.IntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class PaymentLookupCircuitIntegrationTest {
    private static final PaymentProviderGateway.ExpectedPayment EXPECTED =
        new PaymentProviderGateway.ExpectedPayment("LP-ORD-0000000042", 7000, "PG-1");
    private static final LookupRequest REQUEST =
        new LookupRequest(EXPECTED, PaymentAttempt.Outcome.UNKNOWN, PaymentAttempt.Rejection.NONE);
    @Autowired private PaymentLookupGateway lookup;
    @Autowired private PaymentProviderGateway provider;
    @Autowired private CircuitBreakerRegistry circuits;
    @Autowired private MeterRegistry meters;

    @BeforeEach void reset() { circuit().reset(); }
    @AfterEach void clean() { circuit().reset(); }

    @Test
    @DisplayName("실제 조회 프록시는 결제 FAILED를 정상, 통신 오류를 실패, 403을 제외하며 기본 지표에 연결한다")
    void classifiesThroughProxyAndPublishesMetrics() {
        assertThat(AopUtils.isAopProxy(lookup)).isTrue();
        double failuresBefore = failures();
        PaymentObservation rejectedPayment = verified(ProviderPaymentStatus.FAILED);
        doReturn(rejectedPayment).when(provider).lookup(any());
        assertThat(lookup.lookup(REQUEST)).isEqualTo(rejectedPayment);
        doReturn(PaymentObservation.httpFailure(503)).when(provider).lookup(any());
        assertThat(lookup.lookup(REQUEST).httpStatus()).isEqualTo(503);
        doReturn(PaymentObservation.httpFailure(403)).when(provider).lookup(any());
        assertThat(lookup.lookup(REQUEST).httpStatus()).isEqualTo(403);
        assertThat(circuit().getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
        assertThat(circuit().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        assertThat(circuit().getMetrics().getNumberOfBufferedCalls()).isEqualTo(2);
        assertThat(failures() - failuresBefore).isEqualTo(1);
        verify(provider, times(3)).lookup(EXPECTED);
    }

    @Test
    @DisplayName("최소 5건부터 실패율을 판단하고 OPEN은 adapter 호출 없이 차단, HALF_OPEN은 제외를 빼고 2건으로 복귀한다")
    void opensAndRecoversWithoutBypassingProxy() {
        doReturn(PaymentObservation.httpFailure(503)).when(provider).lookup(any());
        for (int i = 0; i < 3; i++) { lookup.lookup(REQUEST); }
        doReturn(verified(ProviderPaymentStatus.PENDING)).when(provider).lookup(any());
        lookup.lookup(REQUEST);
        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        lookup.lookup(REQUEST);
        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(lookup.lookup(REQUEST).reason()).isEqualTo(PaymentObservation.Reason.CIRCUIT_BLOCKED);
        assertThat(circuit().getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(1);
        verify(provider, times(5)).lookup(EXPECTED);

        circuit().transitionToHalfOpenState();
        doReturn(PaymentObservation.httpFailure(401)).when(provider).lookup(any());
        lookup.lookup(REQUEST);
        assertThat(circuit().getMetrics().getNumberOfBufferedCalls()).isZero();
        doReturn(verified(ProviderPaymentStatus.FAILED)).when(provider).lookup(any());
        lookup.lookup(REQUEST);
        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        doReturn(PaymentObservation.missing()).when(provider).lookup(any());
        lookup.lookup(REQUEST);
        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("운영 설정의 HALF_OPEN 2건은 1초 초과 느림 1건만으로도 재차단한다")
    void keepsAgreedSettingsAndReopensOnSlowProbe() {
        var config = circuit().getCircuitBreakerConfig();
        assertThat(config.getSlidingWindowType()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
        assertThat(config.getSlidingWindowSize()).isEqualTo(10);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50);
        assertThat(config.getSlowCallRateThreshold()).isEqualTo(50);
        assertThat(config.getSlowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(1));
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(10000L);
        assertThat(config.isAutomaticTransitionFromOpenToHalfOpenEnabled()).isFalse();
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(2);
        assertThat(config.getMaxWaitDurationInHalfOpenState()).isEqualTo(Duration.ofSeconds(10));
        circuit().transitionToOpenState();
        circuit().transitionToHalfOpenState();
        circuit().acquirePermission();
        circuit().onSuccess(1000, TimeUnit.MILLISECONDS);
        assertThat(circuit().getMetrics().getNumberOfSlowCalls()).isZero();
        circuit().acquirePermission();
        circuit().onSuccess(1001, TimeUnit.MILLISECONDS);
        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @ParameterizedTest
    @CsvSource({"SUCCESS,NONE,FAILED,0", "FAILED,NONE,SUCCESS,0", "FAILED,NO_EFFECT,PENDING,0",
        "SUCCESS,NONE,SUCCESS,1", "SUCCESS,NONE,PENDING,1", "PENDING,NONE,FAILED,1"})
    @DisplayName("알려진 종결·존재 충돌은 회로에서 제외하되 원래 관찰은 저장 경로에 전달한다")
    void excludesKnownConflicts(PaymentAttempt.Outcome outcome, PaymentAttempt.Rejection rejection,
                               ProviderPaymentStatus status, int samples) {
        PaymentObservation observation = verified(status);
        doReturn(observation).when(provider).lookup(any());
        var request = new LookupRequest(EXPECTED, outcome, rejection);
        assertThat(lookup.lookup(request)).isSameAs(observation);
        assertThat(circuit().getMetrics().getNumberOfBufferedCalls()).isEqualTo(samples);
        assertThat(circuit().getMetrics().getNumberOfFailedCalls()).isZero();
        verify(provider).lookup(EXPECTED);
    }

    private CircuitBreaker circuit() { return circuits.circuitBreaker(CircuitPaymentLookupGateway.CIRCUIT_NAME); }

    private double failures() {
        return meters.get("resilience4j.circuitbreaker.calls")
            .tags("name", CircuitPaymentLookupGateway.CIRCUIT_NAME, "kind", "failed").timer().count();
    }

    private PaymentObservation verified(ProviderPaymentStatus status) {
        return PaymentObservation.verified(EXPECTED.transactionKey(), EXPECTED.providerOrderId(), EXPECTED.amount(), status);
    }
}
