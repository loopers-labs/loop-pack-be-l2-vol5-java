package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentObservation.UnknownCause;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.support.TestClock;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.support.payment.LookupSettingsFixture;
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
 * 감싸는 순서(retry가 바깥)와 회로 설정은 그대로 두고 retry 최대 횟수만 1과 3으로 바꾸어 세 가지 장애 모양을 비교한다.
 * 회복 시점을 맞히려는 실험이 아니라, 장애 모양마다 retry 횟수가 무엇을 얻고 잃는지 보는 실험이다.
 * 요청은 하나씩 차례로 보내며, 회로의 OPEN 대기 시간이 지나지 않도록 시계를 멈춘 채 실행한다.
 */
@Tag("experiment")
class PaymentLookupRetryCountExperimentTest {

    private static final int TRANSACTIONS = 6;

    private FakePaymentServer pg;
    private TestPgPaymentAdapter adapter;
    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        pg = new FakePaymentServer();
        adapter = new PaymentProviderConfig().paymentProvider(pg.baseUrl(), "http://127.0.0.1:8080/api/v1/payments/callback",
            Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(500));
        for (int i = 0; i < TRANSACTIONS; i++) {
            PaymentObservation created = adapter.create(new PaymentRequest(
                "LP-RETRY-COUNT-" + i, "TEST", "0000-0000-0000-0000", 7000));
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

    /** 고객 넷 중 첫 고객의 첫 GET만 503. 다시 물으면 회복되는 짧은 끊김이다. */
    @ParameterizedTest(name = "maxAttempts={0}: verified={1}, unverified={2}, gets={3}")
    @CsvSource({"1, 3, 1, 4", "3, 4, 0, 5"})
    void briefGlitchIsRecoveredOnlyWhenRetryIsAllowed(int maxAttempts, int expectedVerified, int expectedUnverified,
                                                      int expectedGets) {
        ResilientPaymentProvider provider = providerWithMaxAttempts(maxAttempts);
        pg.replyNext(503, "");

        Outcome outcome = lookupInOrder("brief-glitch", maxAttempts, provider, keys.subList(0, 4));

        assertThat(outcome.verified()).isEqualTo(expectedVerified);
        assertThat(outcome.unverified()).isEqualTo(expectedUnverified);
        assertThat(outcome.blocked()).isZero();
        assertThat(outcome.gets()).isEqualTo(expectedGets);
        assertThat(outcome.state()).isEqualTo(State.CLOSED);
    }

    /** 모든 GET이 503. 다시 물어도 회복되지 않는 장애에서 고객 여섯이 차례로 확인한다. */
    @ParameterizedTest(name = "maxAttempts={0}: opened at customer {1}, gets={2}, blocked={3}")
    @CsvSource({"1, 4, 4, 2", "3, 2, 4, 4"})
    void continuousFailureOpensAfterTheSameGetsButFewerCustomersWithMoreRetries(
        int maxAttempts, int expectedOpenedAt, int expectedGets, int expectedBlocked
    ) {
        ResilientPaymentProvider provider = providerWithMaxAttempts(maxAttempts);
        for (int i = 0; i < TRANSACTIONS * 3; i++) {
            pg.replyNext(503, "");
        }

        Outcome outcome = lookupInOrder("continuous-failure", maxAttempts, provider, keys);

        assertThat(outcome.verified()).isZero();
        assertThat(outcome.openedAtCustomer()).isEqualTo(expectedOpenedAt);
        assertThat(outcome.gets()).isEqualTo(expectedGets);
        assertThat(outcome.blocked()).isEqualTo(expectedBlocked);
        assertThat(outcome.state()).isEqualTo(State.OPEN);
    }

    /** 첫 거래만 조회할 때마다 503이고 뒤의 세 거래는 정상이다. */
    @ParameterizedTest(name = "maxAttempts={0}: healthy verified={1}, blocked={2}, circuit={3}")
    @CsvSource({"1, 3, 0, CLOSED", "3, 1, 2, OPEN"})
    void oneFailingTransactionFillsMoreOfTheWindowWithMoreRetries(
        int maxAttempts, int expectedHealthyVerified, int expectedBlocked, State expectedState
    ) {
        ResilientPaymentProvider provider = providerWithMaxAttempts(maxAttempts);
        for (int i = 0; i < maxAttempts; i++) {
            pg.replyNext(503, "");
        }
        assertThat(provider.findByTransactionKey(keys.getFirst()))
            .isEqualTo(PaymentObservation.unknown(UnknownCause.TEMPORARY_FAILURE));
        pg.clearQueuedFaults();

        Outcome healthy = lookupInOrder("one-failing", maxAttempts, provider, keys.subList(1, 4));

        assertThat(healthy.verified()).isEqualTo(expectedHealthyVerified);
        assertThat(healthy.blocked()).isEqualTo(expectedBlocked);
        assertThat(pg.lookupCalls()).isEqualTo(4);
        assertThat(healthy.state()).isEqualTo(expectedState);
    }

    private Outcome lookupInOrder(String scene, int maxAttempts, ResilientPaymentProvider provider, List<String> customers) {
        int verified = 0;
        int unverified = 0;
        int blocked = 0;
        int openedAt = 0;
        long started = System.nanoTime();
        for (int i = 0; i < customers.size(); i++) {
            int before = pg.lookupCalls();
            PaymentObservation result = provider.findByTransactionKey(customers.get(i));
            if (result.kind() == PaymentObservation.Kind.OBSERVED) {
                verified++;
            } else if (result.unknownCause() == UnknownCause.CIRCUIT_OPEN && pg.lookupCalls() == before) {
                blocked++;
            } else {
                unverified++;
            }
            State state = provider.lookupCircuitBreaker().getState();
            if (state == State.OPEN && openedAt == 0) {
                openedAt = i + 1;
            }
            System.out.printf("retry-count-trace,%s,%d,customer-%d,%d,%s,%s,%s%n", scene, maxAttempts, i + 1,
                pg.lookupCalls() - before, result.kind(), result.unknownCause(), state);
        }
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
        Outcome outcome = new Outcome(verified, unverified, blocked, pg.lookupCalls(), openedAt,
            provider.lookupCircuitBreaker().getState());
        System.out.printf("retry-count-summary,%s,%d,%s,%dms%n", scene, maxAttempts, outcome, elapsed);
        return outcome;
    }

    private ResilientPaymentProvider providerWithMaxAttempts(int maxAttempts) {
        LookupResilienceSettings base = LookupSettingsFixture.LEARNING;
        LookupResilienceSettings settings = new LookupResilienceSettings(base.budget(), base.attemptEstimate(),
            maxAttempts, base.firstWait(), base.waitMultiplier(), base.windowSize(), base.minimumCalls(),
            base.failureRateThreshold(), base.openWait(), base.halfOpenCalls());
        return new ResilientPaymentProvider(adapter, settings, new TestClock());
    }

    /** blocked: 회로가 막아 GET 없이 끝남. unverified: GET은 보냈지만 끝내 확인하지 못함. */
    private record Outcome(int verified, int unverified, int blocked, int gets, int openedAtCustomer, State state) {}
}
