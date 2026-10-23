package com.loopers.infrastructure.payment;

import com.loopers.application.payment.PaymentCreateCommand;
import com.loopers.application.payment.PaymentCreateObservation;
import com.loopers.application.payment.PaymentLookupObservation;
import com.loopers.application.payment.PgTransaction;
import com.loopers.application.payment.PgTransactionStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CircuitBreakingPaymentGatewayTest {
    private TestPgPaymentGateway delegate;
    private CircuitBreaker breaker;
    private CircuitBreakingPaymentGateway gateway;

    @BeforeEach
    void prepare() {
        delegate = mock(TestPgPaymentGateway.class);
        breaker = new PgLookupCircuitBreakerConfiguration().pgLookupCircuitBreaker(
            new PgLookupCircuitBreakerProperties(5, 5, 50, Duration.ofSeconds(2), 2));
        gateway = new CircuitBreakingPaymentGateway(delegate, breaker);
    }

    @Test
    void countsUnavailableResultsAcrossLookupMethodsAndBlocksFurtherHttpCalls() {
        when(delegate.lookup(anyString())).thenReturn(new PaymentLookupObservation.Unavailable("timeout"));
        when(delegate.lookupByOrder(anyString())).thenReturn(new PaymentLookupObservation.Unavailable("timeout"));
        for (int index = 0; index < 5; index++) {
            if (index % 2 == 0) {
                gateway.lookup("TX-1");
            } else {
                gateway.lookupByOrder("LP-ORD-0000000042");
            }
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(gateway.lookup("TX-1")).isInstanceOf(PaymentLookupObservation.Unavailable.class);
        assertThat(gateway.lookupByOrder("LP-ORD-0000000042")).isInstanceOf(PaymentLookupObservation.Unavailable.class);
        verify(delegate, times(3)).lookup(anyString());
        verify(delegate, times(2)).lookupByOrder(anyString());
        assertThat(breaker.getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(2);
    }

    @Test
    void treatsFailedPaymentsAndMissingTransactionsAsSuccessfulLookups() {
        when(delegate.lookup("TX-1")).thenReturn(new PaymentLookupObservation.Found(List.of(
            new PgTransaction("TX-1", "LP-ORD-0000000042", 7000, PgTransactionStatus.FAILED))));
        when(delegate.lookup("missing")).thenReturn(new PaymentLookupObservation.NotFound());
        for (int index = 0; index < 5; index++) {
            gateway.lookup(index % 2 == 0 ? "TX-1" : "missing");
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(5);
    }

    @Test
    void doesNotRoutePaymentCreationThroughLookupCircuit() {
        breaker.transitionToOpenState();
        when(delegate.create(any())).thenReturn(new PaymentCreateObservation.Accepted("TX-1"));

        assertThat(gateway.create(new PaymentCreateCommand("LP-ORD-0000000042", 7000, "TEST", "0000")))
            .isInstanceOf(PaymentCreateObservation.Accepted.class);
        verify(delegate, times(1)).create(any());
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @DisplayName("호출자 취소는 두 조회에서 확인 불가로 반환하고 회로 표본에서 제외한다.")
    @Test
    void excludesCallerCancellationAcrossLookupMethods() {
        PaymentLookupObservation.Unavailable cancelled = new PaymentLookupObservation.Unavailable(
            "CANCELLED", PaymentLookupObservation.FailureKind.CANCELLED);
        when(delegate.lookup("TX-1")).thenReturn(cancelled);
        when(delegate.lookupByOrder("LP-ORD-0000000042")).thenReturn(cancelled);

        assertThat(gateway.lookup("TX-1")).isEqualTo(cancelled);
        assertThat(gateway.lookupByOrder("LP-ORD-0000000042")).isEqualTo(cancelled);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isZero();
        verify(delegate, times(1)).lookup("TX-1");
        verify(delegate, times(1)).lookupByOrder("LP-ORD-0000000042");
    }
}
