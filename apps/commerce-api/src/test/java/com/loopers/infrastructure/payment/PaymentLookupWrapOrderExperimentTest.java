package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentObservation.UnknownCause;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.support.TestClock;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.support.payment.LookupSettingsFixture;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 장애에서 retry와 회로를 감싸는 순서만 바꾸어, 회로가 무엇을 표본으로 세는지 비교한다.
 * 장애 1: 고객마다 첫 GET은 503, 다시 물으면 정상. 고객 넷이 차례로 확인하면 PG가 받는 GET은 503과 정상이 번갈아 온다.
 * 장애 2: 첫 거래만 retry 끝까지 503이고, 뒤의 세 거래는 정상이다.
 * 회로가 바깥인 쪽은 제품 코드가 아니라 이 테스트 안에서만 감싼다. 두 쪽 모두 같은 설정(최대 3회, 창 4, 최소 4, 50%)을 쓴다.
 */
@Tag("experiment")
class PaymentLookupWrapOrderExperimentTest {

    private static final int CUSTOMERS = 4;
    private static final LookupResilienceSettings SETTINGS = LookupSettingsFixture.LEARNING;

    private FakePaymentServer pg;
    private TestPgPaymentAdapter adapter;
    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        pg = new FakePaymentServer();
        adapter = new PaymentProviderConfig().paymentProvider(pg.baseUrl(), "http://127.0.0.1:8080/api/v1/payments/callback",
            Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(500));
        for (int i = 0; i < CUSTOMERS; i++) {
            PaymentObservation created = adapter.create(new PaymentRequest(
                "LP-WRAP-ORDER-" + i, "TEST", "0000-0000-0000-0000", 7000));
            keys.add(created.transactions().getFirst().transactionKey());
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            adapter.close();
        } finally {
            pg.close();
        }
    }

    @Test
    void retryOutsideCountsEveryGetAndOpensOnAnUnstablePg() {
        ResilientPaymentProvider provider = new ResilientPaymentProvider(adapter, SETTINGS, new TestClock());

        Outcome outcome = run("retry-outside", provider::findByTransactionKey, provider.lookupCircuitBreaker());

        assertThat(outcome.verified()).isEqualTo(2);
        assertThat(outcome.blocked()).isEqualTo(2);
        assertThat(outcome.gets()).isEqualTo(4);
        assertThat(outcome.state()).isEqualTo(State.OPEN);
        assertThat(outcome.failedSamples()).isEqualTo(2);
        assertThat(outcome.successfulSamples()).isEqualTo(2);
    }

    @Test
    void circuitOutsideCountsOnlyTheFinalResultAndDoesNotNoticeTheSameFailures() {
        CircuitBreaker circuit = circuitBreaker();

        Outcome outcome = run("circuit-outside", key -> circuitOutside(circuit, key), circuit);

        assertThat(outcome.verified()).isEqualTo(4);
        assertThat(outcome.blocked()).isZero();
        assertThat(outcome.gets()).isEqualTo(8);
        assertThat(outcome.state()).isEqualTo(State.CLOSED);
        assertThat(outcome.failedSamples()).isZero();
        assertThat(outcome.successfulSamples()).isEqualTo(4);
    }

    @Test
    void retryOutsideLetsOneFailingTransactionFillTheWindowAndBlockHealthyLookups() {
        ResilientPaymentProvider provider = new ResilientPaymentProvider(adapter, SETTINGS, new TestClock());

        Outcome outcome = runOneFailingTransaction("retry-outside", provider::findByTransactionKey,
            provider.lookupCircuitBreaker());

        assertThat(outcome.verified()).isEqualTo(1);
        assertThat(outcome.blocked()).isEqualTo(2);
        assertThat(outcome.gets()).isEqualTo(4);
        assertThat(outcome.state()).isEqualTo(State.OPEN);
        assertThat(outcome.failedSamples()).isEqualTo(3);
        assertThat(outcome.successfulSamples()).isEqualTo(1);
    }

    @Test
    void circuitOutsideCountsOneFailingTransactionOnceAndKeepsHealthyLookupsFlowing() {
        CircuitBreaker circuit = circuitBreaker();

        Outcome outcome = runOneFailingTransaction("circuit-outside", key -> circuitOutside(circuit, key), circuit);

        assertThat(outcome.verified()).isEqualTo(3);
        assertThat(outcome.blocked()).isZero();
        assertThat(outcome.gets()).isEqualTo(6);
        assertThat(outcome.state()).isEqualTo(State.CLOSED);
        assertThat(outcome.failedSamples()).isEqualTo(1);
        assertThat(outcome.successfulSamples()).isEqualTo(3);
    }

    private Outcome run(String order, Function<String, PaymentObservation> lookup, CircuitBreaker circuit) {
        int verified = 0;
        int blocked = 0;
        for (int i = 0; i < CUSTOMERS; i++) {
            // 이 고객의 첫 GET만 503을 받는다. 차단돼 GET을 보내지 못하면 남은 예약을 다음 고객에게 넘기지 않는다.
            pg.replyNext(503, "");
            int before = pg.lookupCalls();
            PaymentObservation result = lookup.apply(keys.get(i));
            int gets = pg.lookupCalls() - before;
            pg.clearQueuedFaults();

            if (result.kind() == PaymentObservation.Kind.OBSERVED) {
                verified++;
            } else {
                assertThat(result).isEqualTo(PaymentObservation.unknown(UnknownCause.CIRCUIT_OPEN));
                blocked++;
            }
            System.out.printf("wrap-order-trace,%s,customer-%d,%d,%s,%s,%s,%d,%d%n", order, i, gets,
                result.kind(), result.unknownCause(), circuit.getState(),
                circuit.getMetrics().getNumberOfFailedCalls(), circuit.getMetrics().getNumberOfSuccessfulCalls());
        }
        Outcome outcome = new Outcome(verified, blocked, pg.lookupCalls(), circuit.getState(),
            circuit.getMetrics().getNumberOfFailedCalls(), circuit.getMetrics().getNumberOfSuccessfulCalls());
        System.out.printf("wrap-order-summary,%s,%d,%d,%d,%s,%d,%d%n", order, outcome.verified(), outcome.blocked(),
            outcome.gets(), outcome.state(), outcome.failedSamples(), outcome.successfulSamples());
        return outcome;
    }

    private Outcome runOneFailingTransaction(String order, Function<String, PaymentObservation> lookup,
                                             CircuitBreaker circuit) {
        for (int i = 0; i < SETTINGS.maxAttempts(); i++) {
            pg.replyNext(503, "");
        }
        assertThat(lookup.apply(keys.getFirst())).isEqualTo(PaymentObservation.unknown(UnknownCause.TEMPORARY_FAILURE));
        pg.clearQueuedFaults();

        int verified = 0;
        int blocked = 0;
        for (String key : keys.subList(1, CUSTOMERS)) {
            PaymentObservation result = lookup.apply(key);
            if (result.kind() == PaymentObservation.Kind.OBSERVED) {
                verified++;
            } else {
                assertThat(result).isEqualTo(PaymentObservation.unknown(UnknownCause.CIRCUIT_OPEN));
                blocked++;
            }
        }
        Outcome outcome = new Outcome(verified, blocked, pg.lookupCalls(), circuit.getState(),
            circuit.getMetrics().getNumberOfFailedCalls(), circuit.getMetrics().getNumberOfSuccessfulCalls());
        System.out.printf("wrap-order-one-failing,%s,%d,%d,%d,%s,%d,%d%n", order, outcome.verified(), outcome.blocked(),
            outcome.gets(), outcome.state(), outcome.failedSamples(), outcome.successfulSamples());
        return outcome;
    }

    /** ResilientPaymentProvider와 같은 판정(일시적 실패, 남은 예산)·설정으로, 감싸는 순서만 회로 → retry → GET으로 바꾼다. */
    private PaymentObservation circuitOutside(CircuitBreaker circuit, String key) {
        long deadline = System.nanoTime() + SETTINGS.budget().toNanos();
        Retry retry = Retry.of("wrapOrderExperiment", RetryConfig.<PaymentObservation>custom()
            .maxAttempts(SETTINGS.maxAttempts())
            .intervalFunction(IntervalFunction.ofExponentialBackoff(SETTINGS.firstWait(), SETTINGS.waitMultiplier()))
            .retryOnResult(result -> isTemporaryFailure(result) && hasTimeForAnotherAttempt(deadline))
            .build());
        try {
            return circuit.executeSupplier(() -> retry.executeSupplier(() -> adapter.findByTransactionKey(key)));
        } catch (CallNotPermittedException exception) {
            return PaymentObservation.unknown(UnknownCause.CIRCUIT_OPEN);
        }
    }

    private static CircuitBreaker circuitBreaker() {
        return CircuitBreaker.of("wrapOrderExperiment", CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(SETTINGS.windowSize())
            .minimumNumberOfCalls(SETTINGS.minimumCalls())
            .failureRateThreshold(SETTINGS.failureRateThreshold())
            .waitDurationInOpenState(SETTINGS.openWait())
            .permittedNumberOfCallsInHalfOpenState(SETTINGS.halfOpenCalls())
            .automaticTransitionFromOpenToHalfOpenEnabled(false)
            .recordResult(PaymentLookupWrapOrderExperimentTest::isTemporaryFailure)
            .build());
    }

    private static boolean hasTimeForAnotherAttempt(long deadline) {
        Duration longestWait = Duration.ofMillis(200);
        return deadline - System.nanoTime() >= longestWait.plus(SETTINGS.attemptEstimate()).toNanos();
    }

    private static boolean isTemporaryFailure(Object result) {
        return result instanceof PaymentObservation observation
            && observation.unknownCause() == UnknownCause.TEMPORARY_FAILURE;
    }

    private record Outcome(int verified, int blocked, int gets, State state, int failedSamples, int successfulSamples) {}
}
