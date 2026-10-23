package com.loopers.infrastructure.payment;

import com.loopers.application.payment.PaymentCreateCommand;
import com.loopers.application.payment.PaymentCreateObservation;
import com.loopers.application.payment.PaymentGateway;
import com.loopers.application.payment.PaymentLookupObservation;
import com.loopers.application.payment.PgTransaction;
import com.loopers.application.payment.PgTransactionStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResilientPgLookupGatewayTest {
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final PaymentGateway delegate = mock(PaymentGateway.class);
    private final CircuitBreaker breaker = new PgLookupCircuitBreakerConfiguration().pgLookupCircuitBreaker(
        new PgLookupCircuitBreakerProperties(5, 5, 50, Duration.ofSeconds(2), 2));
    private static final PaymentLookupObservation OK = new PaymentLookupObservation.Found(List.of());

    @AfterEach
    void stop() { executor.shutdownNow(); }

    @DisplayName("일시적 조회 실패 뒤 재시도에 성공하면 회로의 논리적 성공은 한 번만 기록한다.")
    @Test
    void retriesTemporaryFailureAndCountsOneLogicalSuccess() {
        when(delegate.lookup("TX")).thenReturn(unavailable(PaymentLookupObservation.FailureKind.TEMPORARY_HTTP), OK);
        assertThat(gateway(true, false, false).lookup("TX")).isEqualTo(OK);
        verify(delegate, times(2)).lookup("TX");
        assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @DisplayName("재시도 가능한 조회 실패가 계속되면 최초 포함 3회까지만 호출하고 회로 실패는 한 번 기록한다.")
    @ParameterizedTest
    @EnumSource(value = PaymentLookupObservation.FailureKind.class, names = {"TEMPORARY_HTTP", "TIMEOUT", "NETWORK"})
    void capsRetryAtThreeTotalAttempts(PaymentLookupObservation.FailureKind kind) {
        when(delegate.lookupByOrder(anyString())).thenReturn(unavailable(kind));
        assertThat(gateway(true, false, false).lookupByOrder("ORDER")).isEqualTo(unavailable(kind));
        verify(delegate, times(3)).lookupByOrder("ORDER");
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @DisplayName("재시도 대상이 아닌 조회 불가 결과는 추가 호출하지 않는다.")
    @ParameterizedTest
    @EnumSource(value = PaymentLookupObservation.FailureKind.class, names = {"HTTP_ERROR", "INVALID_RESPONSE", "CANCELLED", "OTHER"})
    void doesNotRetryOtherUnavailableResults(PaymentLookupObservation.FailureKind kind) {
        when(delegate.lookup("TX")).thenReturn(unavailable(kind));
        gateway(true, false, false).lookup("TX");
        verify(delegate, times(1)).lookup("TX");
        if (kind == PaymentLookupObservation.FailureKind.CANCELLED) {
            assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
        }
    }

    @DisplayName("정상 업무 상태와 거래 없음은 재시도하거나 회로 실패로 집계하지 않는다.")
    @Test
    void doesNotRetryBusinessStatesOrMissingTransactions() {
        PaymentGateway gateway = gateway(true, false, false);
        when(delegate.lookup("PENDING")).thenReturn(new PaymentLookupObservation.Found(List.of(
            new PgTransaction("PENDING", "ORDER", 7000, PgTransactionStatus.PENDING))));
        when(delegate.lookup("FAILED")).thenReturn(new PaymentLookupObservation.Found(List.of(
            new PgTransaction("FAILED", "ORDER", 7000, PgTransactionStatus.FAILED))));
        when(delegate.lookup("missing")).thenReturn(new PaymentLookupObservation.NotFound());
        when(delegate.lookupByOrder("empty")).thenReturn(OK);
        gateway.lookup("PENDING"); gateway.lookup("FAILED"); gateway.lookup("missing"); gateway.lookupByOrder("empty");
        verify(delegate, times(3)).lookup(anyString());
        verify(delegate).lookupByOrder("empty");
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @DisplayName("각 재시도는 허가를 소비하고 로컬 호출 제한은 회로 표본에서 제외한다.")
    @Test
    void eachRetryConsumesPermitAndLocalRejectionIsIgnoredByCircuit() {
        when(delegate.lookup(anyString())).thenReturn(unavailable(PaymentLookupObservation.FailureKind.TEMPORARY_HTTP));
        PgLookupPolicyProperties settings = new PgLookupPolicyProperties(
            new PgLookupPolicyProperties.RetrySettings(true, 3, Duration.ofMillis(100), 2, 0.2),
            new PgLookupPolicyProperties.RateSettings(true, 2, Duration.ofDays(1)),
            new PgLookupPolicyProperties.TimeSettings(false, Duration.ofMillis(900)));
        PaymentGateway gateway = new CircuitBreakingPaymentGateway(
            new ResilientPgLookupGateway(delegate, settings, executor), breaker);
        assertThat(gateway.lookup("TX")).isInstanceOfSatisfying(PaymentLookupObservation.Unavailable.class,
            result -> assertThat(result.kind()).isEqualTo(PaymentLookupObservation.FailureKind.RATE_LIMITED));
        verify(delegate, times(2)).lookup("TX");
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @DisplayName("두 조회 방식은 허가를 공유하고 회로 OPEN에서는 내부 조회 정책에 진입하지 않는다.")
    @Test
    void twoLookupMethodsSharePermitsAndOpenBypassesAllInnerPolicies() {
        when(delegate.lookup(anyString())).thenReturn(OK);
        when(delegate.lookupByOrder(anyString())).thenReturn(OK);
        PaymentGateway gateway = gateway(true, true, false);
        for (int index = 0; index < 20; index++) { gateway.lookup("TX"); }
        assertThat(gateway.lookupByOrder("ORDER")).isInstanceOfSatisfying(PaymentLookupObservation.Unavailable.class,
            result -> assertThat(result.kind()).isEqualTo(PaymentLookupObservation.FailureKind.RATE_LIMITED));
        assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(5);
        breaker.transitionToOpenState();
        gateway.lookup("TX");
        verify(delegate, times(20)).lookup("TX");
        verify(delegate, times(0)).lookupByOrder(anyString());
    }

    @DisplayName("전체 조회 시간이 초과되면 실행 작업을 취소하고 후속 재시도를 막는다.")
    @Test
    void deadlineCancelsWorkerAndPreventsLaterRetry() throws Exception {
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(delegate.lookup("TX")).thenAnswer(invocation -> {
            calls.incrementAndGet();
            try { Thread.sleep(2000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { stopped.countDown(); }
            return unavailable(PaymentLookupObservation.FailureKind.TIMEOUT);
        });
        long start = System.nanoTime();
        assertThat(gateway(true, false, true).lookup("TX")).isInstanceOfSatisfying(PaymentLookupObservation.Unavailable.class,
            result -> assertThat(result.kind()).isEqualTo(PaymentLookupObservation.FailureKind.DEADLINE_EXCEEDED));
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isBetween(750L, 1500L);
        assertThat(stopped.await(1, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @DisplayName("호출자가 대기 중 중단되면 backoff를 끝내고 다음 HTTP 조회를 보내지 않는다.")
    @Test
    void callerCancellationStopsBackoffWithoutNextHttpAttempt() throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        when(delegate.lookup("TX")).thenAnswer(invocation -> {
            first.countDown(); return unavailable(PaymentLookupObservation.FailureKind.TEMPORARY_HTTP);
        });
        PaymentGateway gateway = gateway(true, false, true);
        Thread caller = Thread.ofVirtual().start(() -> gateway.lookup("TX"));
        assertThat(first.await(1, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(1000);
        assertThat(caller.isAlive()).isFalse();
        executor.shutdown();
        assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        verify(delegate, times(1)).lookup("TX");
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @DisplayName("TimeLimiter가 꺼져 있어도 backoff 중 interrupt는 취소로 반환하고 회로 표본에서 제외한다.")
    @ParameterizedTest(name = "RateLimiter={0}")
    @ValueSource(booleans = {false, true})
    void callerInterruptionDuringBackoffIsExcludedWithoutTimeLimiter(boolean rateEnabled) throws Exception {
        CountDownLatch firstCall = new CountDownLatch(1);
        when(delegate.lookup("TX")).thenAnswer(invocation -> {
            firstCall.countDown();
            return unavailable(PaymentLookupObservation.FailureKind.TEMPORARY_HTTP);
        });
        PgLookupPolicyProperties settings = new PgLookupPolicyProperties(
            new PgLookupPolicyProperties.RetrySettings(true, 3, Duration.ofSeconds(30), 2, 0),
            new PgLookupPolicyProperties.RateSettings(rateEnabled, 20, Duration.ofDays(1)),
            new PgLookupPolicyProperties.TimeSettings(false, Duration.ofMillis(900)));
        PaymentGateway gateway = new CircuitBreakingPaymentGateway(
            new ResilientPgLookupGateway(delegate, settings, executor), breaker);
        AtomicReference<PaymentLookupObservation> observation = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        Thread caller = Thread.ofVirtual().unstarted(() -> {
            try {
                observation.set(gateway.lookup("TX"));
            } catch (RuntimeException exception) {
                failure.set(exception);
            } finally {
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            caller.start();
            assertThat(firstCall.await(1, TimeUnit.SECONDS)).isTrue();
            // 이 경로의 대기는 Retry backoff뿐이다. HTTP 호출 전 interrupt와 구분한다.
            await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(1))
                .until(() -> caller.getState() == Thread.State.TIMED_WAITING);
            caller.interrupt();
            caller.join(1000);

            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(observation.get()).isEqualTo(unavailable(PaymentLookupObservation.FailureKind.CANCELLED));
            assertThat(interruptPreserved.get()).isTrue();
            verify(delegate, times(1)).lookup("TX");
            assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
            assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        } finally {
            caller.interrupt();
            caller.join(1000);
        }
    }

    @DisplayName("회로 OPEN에서도 결제 생성은 모든 조회 회복 정책을 우회한다.")
    @Test
    void createBypassesAllPoliciesEvenWhenOpen() {
        when(delegate.create(any())).thenReturn(new PaymentCreateObservation.Unknown("lost"));
        breaker.transitionToOpenState();
        assertThat(gateway(true, true, true).create(new PaymentCreateCommand("ORDER", 7000, "TEST", "0000")))
            .isInstanceOf(PaymentCreateObservation.Unknown.class);
        verify(delegate).create(any());
    }

    private PaymentGateway gateway(boolean retry, boolean rate, boolean time) {
        PgLookupPolicyProperties settings = new PgLookupPolicyProperties(
            new PgLookupPolicyProperties.RetrySettings(retry, 3, Duration.ofMillis(100), 2, 0.2),
            new PgLookupPolicyProperties.RateSettings(rate, 20, Duration.ofDays(1)),
            new PgLookupPolicyProperties.TimeSettings(time, Duration.ofMillis(900)));
        return new CircuitBreakingPaymentGateway(new ResilientPgLookupGateway(delegate, settings, executor), breaker);
    }

    private PaymentLookupObservation unavailable(PaymentLookupObservation.FailureKind kind) {
        return new PaymentLookupObservation.Unavailable(kind.name(), kind);
    }
}
