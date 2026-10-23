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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 감싸는 순서(retry → 회로 → GET)와 retry 최대 3회는 그대로 두고, 회로의 실패율 기준과 창 방식만 바꾸어 네 장면을 비교한다.
 * 지금 설정(최근 4개·50%), 실패율 80%(최근 4개·80%), 시간 창(최근 10초·최소 4·50%)이다.
 * ResilientPaymentProvider는 개수 기준 창으로 고정돼 있어, 같은 판정·순서로 이 테스트 안에서만 감싼다. 제품 코드는 바꾸지 않는다.
 * 요청은 하나씩 차례로 보낸다. 회로의 시계는 테스트 시계라 "시간이 흘렀다"를 직접 만든다.
 */
@Tag("experiment")
class PaymentLookupCircuitSettingsExperimentTest {

    private static final LookupResilienceSettings SETTINGS = LookupSettingsFixture.LEARNING;

    private FakePaymentServer pg;
    private TestPgPaymentAdapter adapter;
    private TestClock clock;
    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        pg = new FakePaymentServer();
        adapter = new PaymentProviderConfig().paymentProvider(pg.baseUrl(), "http://127.0.0.1:8080/api/v1/payments/callback",
            Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(500));
        clock = new TestClock();
        for (int i = 0; i < 6; i++) {
            PaymentObservation created = adapter.create(new PaymentRequest(
                "LP-CIRCUIT-SETTINGS-" + i, "TEST", "0000-0000-0000-0000", 7000));
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

    @ParameterizedTest(name = "{0} / {1}: verified={2}, blockedByCircuit={3}, circuit={4}")
    @CsvSource({
        "CURRENT,  ONE_FAILING,  1, 2, OPEN",
        "RATE_80,  ONE_FAILING,  3, 0, CLOSED",
        "TIME_10S, ONE_FAILING,  1, 2, OPEN",
        "CURRENT,  HALF_FAILING, 2, 2, OPEN",
        "RATE_80,  HALF_FAILING, 4, 0, CLOSED",
        "TIME_10S, HALF_FAILING, 2, 2, OPEN",
        "CURRENT,  CONTINUOUS,   0, 5, OPEN",
        "RATE_80,  CONTINUOUS,   0, 5, OPEN",
        "TIME_10S, CONTINUOUS,   0, 5, OPEN",
        "CURRENT,  NIGHT,        1, 2, OPEN",
        "RATE_80,  NIGHT,        3, 0, CLOSED",
        "TIME_10S, NIGHT,        3, 0, CLOSED"
    })
    void comparesFailureRateAndWindowTypeAcrossFourScenes(
        Policy policy, Scene scene, int expectedVerified, int expectedBlocked, State expectedState
    ) {
        CircuitBreaker circuit = circuitBreaker(policy);
        List<PaymentObservation> results = switch (scene) {
            case ONE_FAILING -> oneFailingThenHealthy(circuit, Duration.ZERO);
            case NIGHT -> oneFailingThenHealthy(circuit, Duration.ofMinutes(20));
            case HALF_FAILING -> halfFailing(circuit);
            case CONTINUOUS -> continuous(circuit);
        };

        int verified = (int) results.stream().filter(result -> result.kind() == PaymentObservation.Kind.OBSERVED).count();
        int blocked = (int) results.stream().filter(result -> result.unknownCause() == UnknownCause.CIRCUIT_OPEN).count();
        System.out.printf("circuit-settings,%s,%s,verified=%d,blockedByCircuit=%d,others=%d,pgLookups=%d,circuit=%s%n",
            policy, scene, verified, blocked, results.size() - verified - blocked, pg.lookupCalls(), circuit.getState());

        assertThat(verified).isEqualTo(expectedVerified);
        assertThat(blocked).isEqualTo(expectedBlocked);
        assertThat(circuit.getState()).isEqualTo(expectedState);
    }

    /** 첫 거래만 조회할 때마다 503, 그다음 시간이 흐른 뒤 정상 거래 셋이 차례로 확인한다. 결과는 정상 거래 셋의 것만 돌려준다. */
    private List<PaymentObservation> oneFailingThenHealthy(CircuitBreaker circuit, Duration gap) {
        for (int i = 0; i < SETTINGS.maxAttempts(); i++) {
            pg.replyNext(503, "");
        }
        assertThat(lookup(circuit, keys.getFirst())).isEqualTo(PaymentObservation.unknown(UnknownCause.TEMPORARY_FAILURE));
        pg.clearQueuedFaults();
        clock.advance(gap);
        List<PaymentObservation> results = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            results.add(lookup(circuit, keys.get(i)));
        }
        return results;
    }

    /** 고객 넷이 차례로 확인한다. 고객마다 첫 GET만 503이고 다시 물으면 정상이다. */
    private List<PaymentObservation> halfFailing(CircuitBreaker circuit) {
        List<PaymentObservation> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            pg.replyNext(503, "");
            results.add(lookup(circuit, keys.get(i)));
            pg.clearQueuedFaults();
        }
        return results;
    }

    /** 모든 GET이 503이고 고객 여섯이 차례로 확인한다. */
    private List<PaymentObservation> continuous(CircuitBreaker circuit) {
        for (int i = 0; i < 6 * SETTINGS.maxAttempts(); i++) {
            pg.replyNext(503, "");
        }
        List<PaymentObservation> results = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            results.add(lookup(circuit, keys.get(i)));
        }
        return results;
    }

    /** ResilientPaymentProvider와 같은 retry 조건(일시적 실패, 남은 예산)과 순서(retry → 회로 → GET)로 감싼다. */
    private PaymentObservation lookup(CircuitBreaker circuit, String key) {
        long deadline = System.nanoTime() + SETTINGS.budget().toNanos();
        Retry retry = Retry.of("circuitSettingsExperiment", RetryConfig.<PaymentObservation>custom()
            .maxAttempts(SETTINGS.maxAttempts())
            .intervalFunction(IntervalFunction.ofExponentialBackoff(SETTINGS.firstWait(), SETTINGS.waitMultiplier()))
            .retryOnResult(result -> isTemporaryFailure(result) && hasTimeForAnotherAttempt(deadline))
            .ignoreExceptions(CallNotPermittedException.class)
            .build());
        try {
            return retry.executeSupplier(() -> circuit.executeSupplier(() -> adapter.findByTransactionKey(key)));
        } catch (CallNotPermittedException exception) {
            return PaymentObservation.unknown(UnknownCause.CIRCUIT_OPEN);
        }
    }

    private CircuitBreaker circuitBreaker(Policy policy) {
        CircuitBreakerConfig.Builder config = CircuitBreakerConfig.custom()
            .minimumNumberOfCalls(SETTINGS.minimumCalls())
            .waitDurationInOpenState(SETTINGS.openWait())
            .permittedNumberOfCallsInHalfOpenState(SETTINGS.halfOpenCalls())
            .automaticTransitionFromOpenToHalfOpenEnabled(false)
            .recordResult(PaymentLookupCircuitSettingsExperimentTest::isTemporaryFailure)
            .clock(clock);
        switch (policy) {
            case CURRENT -> config.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(SETTINGS.windowSize()).failureRateThreshold(SETTINGS.failureRateThreshold());
            case RATE_80 -> config.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(SETTINGS.windowSize()).failureRateThreshold(80);
            case TIME_10S -> config.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                .slidingWindowSize(10).failureRateThreshold(SETTINGS.failureRateThreshold());
        }
        return CircuitBreaker.of("circuitSettingsExperiment", config.build());
    }

    private static boolean isTemporaryFailure(Object result) {
        return result instanceof PaymentObservation observation
            && observation.unknownCause() == UnknownCause.TEMPORARY_FAILURE;
    }

    private static boolean hasTimeForAnotherAttempt(long deadline) {
        Duration longestWait = Duration.ofMillis(200);
        return deadline - System.nanoTime() >= longestWait.plus(SETTINGS.attemptEstimate()).toNanos();
    }

    enum Policy { CURRENT, RATE_80, TIME_10S }

    enum Scene { ONE_FAILING, HALF_FAILING, CONTINUOUS, NIGHT }
}
