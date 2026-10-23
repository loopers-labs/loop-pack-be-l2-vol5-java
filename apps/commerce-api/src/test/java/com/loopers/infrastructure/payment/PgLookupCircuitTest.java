package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentCreateRequest;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentGatewayResult;
import com.loopers.domain.payment.PaymentLookupNotPermittedException;
import com.loopers.domain.payment.PaymentTransaction;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class PgLookupCircuitTest {
    private final MutableClock clock = new MutableClock();
    private final CircuitBreaker circuit = CircuitBreaker.of("test-local-pg-135135",
        CircuitBreakerConfig.from(PgLookupCircuit.configuration(clock))
            .currentTimestampFunction(ignored -> clock.nanos.get(), TimeUnit.NANOSECONDS).build());
    private final StubGateway delegate = new StubGateway();
    private final CircuitBreakingPaymentGateway gateway = new CircuitBreakingPaymentGateway(delegate, circuit);

    @AfterEach
    void stopsAnyOutstandingHalfOpenTimer() {
        circuit.reset();
    }

    @Test
    void selectedConfigurationIsCountBasedAndContainsTheApprovedThresholds() {
        var config = circuit.getCircuitBreakerConfig();
        assertThat(config.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
        assertThat(config.getSlidingWindowSize()).isEqualTo(20);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(10);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50);
        assertThat(config.getSlowCallRateThreshold()).isEqualTo(50);
        assertThat(config.getSlowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(3));
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(30_000);
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(2);
        assertThat(config.getMaxWaitDurationInHalfOpenState()).isEqualTo(Duration.ofSeconds(40));
        assertThat(config.isAutomaticTransitionFromOpenToHalfOpenEnabled()).isFalse();
    }

    @Test
    void waitsForTenSamplesThenOpensAtExactlyFiftyPercentFailures() {
        delegate.result = () -> PaymentGatewayResult.unknown(PaymentGatewayResult.Failure.NETWORK);
        repeatLookup(5);
        delegate.result = PaymentGatewayResult::notFound;
        repeatLookup(4);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        repeatLookup(1);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuit.getMetrics().getNumberOfBufferedCalls()).isEqualTo(10);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isEqualTo(5);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentGatewayResult.Failure.class, mode = EnumSource.Mode.EXCLUDE, names = "NONE")
    void unknownTransportOrResponseFailureIsOneFailedSample(PaymentGatewayResult.Failure failure) {
        delegate.result = () -> PaymentGatewayResult.unknown(failure);
        gateway.findByTransactionKey("TEST-TX-1");
        assertThat(delegate.lookups).hasValue(1);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        assertThat(circuit.getMetrics().getNumberOfSuccessfulCalls()).isZero();
    }

    @ParameterizedTest
    @EnumSource(PaymentTransaction.Status.class)
    void validBusinessStatusIncludingFailedIsSuccessfulCommunication(PaymentTransaction.Status status) {
        delegate.result = () -> PaymentGatewayResult.observed(List.of(transaction(status)));
        repeatLookup(12);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuit.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(12);
    }

    @Test
    void notFoundAndWellFormedConflictsAreNotCommunicationFailures() {
        repeatLookup(10);
        delegate.result = () -> PaymentGatewayResult.conflict(List.of(transaction(PaymentTransaction.Status.SUCCESS)));
        repeatLookup(10);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuit.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(20);
    }

    @Test
    void theTwoLookupPathsShareOneCircuitAndBlockedCallsDoNotReachHttpOrBecomeSamples() {
        open();
        int started = delegate.lookups.get();
        assertThatThrownBy(() -> gateway.findByProviderOrderId("LP-ORD-0000000042"))
            .isInstanceOf(PaymentLookupNotPermittedException.class).hasCause(null);
        assertThatThrownBy(() -> gateway.findByTransactionKey("TEST-TX-42"))
            .isInstanceOf(PaymentLookupNotPermittedException.class).hasCause(null);
        assertThat(delegate.lookups).hasValue(started);
        assertThat(circuit.getMetrics().getNumberOfBufferedCalls()).isEqualTo(10);
        assertThat(circuit.getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(2);
    }

    @Test
    void createBypassesTheLookupCircuitAndNeverProducesLookupSamples() {
        open();
        var request = new PaymentCreateRequest("LP-ORD-0000000042", 7000, "TEST", "0000");
        gateway.create(request);
        assertThat(delegate.creates).hasValue(1);
        assertThat(delegate.lookups).hasValue(10);
        assertThat(circuit.getMetrics().getNumberOfBufferedCalls()).isEqualTo(10);
    }

    @Test
    void aSlowSuccessCanOpenTheCircuitWithoutBecomingAPaymentFailure() {
        delegate.result = () -> {
            clock.advance(Duration.ofSeconds(3).plusNanos(1));
            return PaymentGatewayResult.observed(List.of(transaction(PaymentTransaction.Status.SUCCESS)));
        };
        repeatLookup(5);
        delegate.result = PaymentGatewayResult::notFound;
        repeatLookup(5);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuit.getMetrics().getNumberOfSlowCalls()).isEqualTo(5);
    }

    @Test
    void exactlyThreeSecondsIsNotASlowCall() {
        delegate.result = () -> {
            clock.advance(Duration.ofSeconds(3));
            return PaymentGatewayResult.notFound();
        };
        repeatLookup(10);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuit.getMetrics().getNumberOfSlowCalls()).isZero();
    }

    @Test
    void openWaitRequiresThirtySecondsAndTwoSuccessfulProbesCloseTheCircuit() {
        open();
        clock.advance(Duration.ofSeconds(30).minusNanos(1));
        assertThatThrownBy(() -> gateway.findByTransactionKey("TEST-TX-42"))
            .isInstanceOf(PaymentLookupNotPermittedException.class);
        clock.advance(Duration.ofNanos(2));
        delegate.result = PaymentGatewayResult::notFound;
        gateway.findByTransactionKey("TEST-TX-42");
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        gateway.findByProviderOrderId("LP-ORD-0000000042");
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(delegate.lookups).hasValue(12);
    }

    @Test
    void oneFailureOutOfTwoProbesReopensTheCircuit() {
        open();
        clock.advance(Duration.ofSeconds(31));
        delegate.result = PaymentGatewayResult::notFound;
        gateway.findByTransactionKey("TEST-TX-42");
        delegate.result = () -> PaymentGatewayResult.unknown(PaymentGatewayResult.Failure.TIMEOUT);
        gateway.findByTransactionKey("TEST-TX-42");
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void atMostTwoProbesCanBeInFlightInHalfOpenState() {
        open();
        clock.advance(Duration.ofSeconds(31));
        assertThat(circuit.tryAcquirePermission()).isTrue();
        assertThat(circuit.tryAcquirePermission()).isTrue();
        assertThatThrownBy(() -> gateway.findByTransactionKey("TEST-TX-42"))
            .isInstanceOf(PaymentLookupNotPermittedException.class);
        assertThat(delegate.lookups).hasValue(10);
        circuit.onSuccess(1, TimeUnit.MILLISECONDS);
        circuit.onSuccess(1, TimeUnit.MILLISECONDS);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void incompleteHalfOpenProbesExpireRatherThanWaitingForever() {
        CircuitBreaker shortTimer = CircuitBreaker.of("short-half-open-timer",
            CircuitBreakerConfig.from(PgLookupCircuit.configuration(clock))
                .maxWaitDurationInHalfOpenState(Duration.ofMillis(200)).build());
        try {
            shortTimer.transitionToOpenState();
            clock.advance(Duration.ofSeconds(31));
            assertThat(shortTimer.tryAcquirePermission()).isTrue();
            assertThat(shortTimer.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
            await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(10))
                .untilAsserted(() -> assertThat(shortTimer.getState()).isEqualTo(CircuitBreaker.State.OPEN));
        } finally {
            shortTimer.reset();
        }
    }

    @Test
    void slidingWindowRetainsOnlyTheMostRecentTwentySamples() {
        repeatLookup(25);
        assertThat(delegate.lookups).hasValue(25);
        assertThat(circuit.getMetrics().getNumberOfBufferedCalls()).isEqualTo(20);
    }

    @Test
    void unexpectedAdapterExceptionsArePropagatedAndNotReplacedWithFakeResults() {
        IllegalStateException failure = new IllegalStateException("closed adapter");
        delegate.result = () -> { throw failure; };
        assertThatThrownBy(() -> gateway.findByTransactionKey("TEST-TX-42")).isSameAs(failure);
        assertThat(delegate.lookups).hasValue(1);
    }

    @Test
    void closingTheManagedDecoratorClosesItsHttpDelegate() throws Exception {
        gateway.close();
        assertThat(delegate.closed).isTrue();
    }

    private void open() {
        delegate.result = () -> PaymentGatewayResult.unknown(PaymentGatewayResult.Failure.NETWORK);
        repeatLookup(10);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    private void repeatLookup(int count) {
        for (int index = 0; index < count; index++) {
            gateway.findByProviderOrderId("LP-ORD-0000000042");
        }
    }

    private static PaymentTransaction transaction(PaymentTransaction.Status status) {
        return new PaymentTransaction("135135", "LP-ORD-0000000042", "TEST-TX-42", 7000, status);
    }

    private static final class StubGateway implements PaymentGateway, AutoCloseable {
        private final AtomicInteger lookups = new AtomicInteger();
        private final AtomicInteger creates = new AtomicInteger();
        private Supplier<PaymentGatewayResult> result = PaymentGatewayResult::notFound;
        private boolean closed;

        @Override
        public PaymentGatewayResult create(PaymentCreateRequest request) {
            creates.incrementAndGet();
            return PaymentGatewayResult.observed(List.of(transaction(PaymentTransaction.Status.PENDING)));
        }

        @Override
        public PaymentGatewayResult findByTransactionKey(String transactionKey) {
            lookups.incrementAndGet();
            return result.get();
        }

        @Override
        public PaymentGatewayResult findByProviderOrderId(String providerOrderId) {
            lookups.incrementAndGet();
            return result.get();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is used by this test");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.EPOCH.plusNanos(nanos.get());
        }

        private void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
