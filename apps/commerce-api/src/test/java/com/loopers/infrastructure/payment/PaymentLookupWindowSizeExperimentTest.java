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
 * 창 4·최소 표본 4, 창 20·최소 표본 20, 창 20·최소 표본 4를 두 장애에서 비교한다.
 * 거래 하나만 실패할 때 정상 조회가 막히는지, 조회 전체가 실패할 때 몇 번째 GET에서 차단하는지 확인한다.
 * 요청은 하나씩 차례로 보내며, 회로의 OPEN 대기 시간이 지나지 않도록 시계를 멈춘 채 실행한다.
 */
@Tag("experiment")
class PaymentLookupWindowSizeExperimentTest {

    private static final int HEALTHY_TRANSACTIONS = 30;
    private static final int MAX_CUSTOMERS = 15;

    private FakePaymentServer pg;
    private TestPgPaymentAdapter adapter;
    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        pg = new FakePaymentServer();
        adapter = new PaymentProviderConfig().paymentProvider(pg.baseUrl(), "http://127.0.0.1:8080/api/v1/payments/callback",
            Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(500));
        for (int i = 0; i <= HEALTHY_TRANSACTIONS; i++) {
            PaymentObservation created = adapter.create(new PaymentRequest(
                "LP-WINDOW-" + i, "TEST", "0000-0000-0000-0000", 7000));
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

    @ParameterizedTest(name = "window={0}, minimumCalls={1}: healthy verified={2}, blocked={3}, circuit={4}")
    @CsvSource({"4, 4, 1, 29, OPEN", "20, 20, 30, 0, CLOSED", "20, 4, 1, 29, OPEN"})
    void oneFailingTransactionBlocksFewerHealthyLookupsOnlyWhenMoreSamplesAreRequired(
        int window, int minimumCalls, int expectedVerified, int expectedBlocked, State expectedState
    ) {
        ResilientPaymentProvider provider = providerWithWindow(window, minimumCalls);
        // 첫 거래만 최대 시도 횟수만큼 503을 받는다. 남은 예약은 정상 거래 전에 걷는다.
        for (int i = 0; i < 3; i++) {
            pg.replyNext(503, "");
        }
        assertThat(provider.findByTransactionKey(keys.getFirst()))
            .isEqualTo(PaymentObservation.unknown(UnknownCause.TEMPORARY_FAILURE));
        pg.clearQueuedFaults();

        int verified = 0;
        int blocked = 0;
        for (int i = 1; i <= HEALTHY_TRANSACTIONS; i++) {
            PaymentObservation result = provider.findByTransactionKey(keys.get(i));
            if (result.kind() == PaymentObservation.Kind.OBSERVED) {
                verified++;
            } else {
                assertThat(result).isEqualTo(PaymentObservation.unknown(UnknownCause.CIRCUIT_OPEN));
                blocked++;
            }
        }

        var circuit = provider.lookupCircuitBreaker();
        System.out.printf("window-one-failing,%d,%d,%d,%d,%s,%d,%d,%d%n", window, minimumCalls, verified, blocked,
            circuit.getState(),
            circuit.getMetrics().getNumberOfFailedCalls(), circuit.getMetrics().getNumberOfSuccessfulCalls(), pg.lookupCalls());
        assertThat(verified).isEqualTo(expectedVerified);
        assertThat(blocked).isEqualTo(expectedBlocked);
        assertThat(circuit.getState()).isEqualTo(expectedState);
    }

    @ParameterizedTest(name = "window={0}, minimumCalls={1}: opened at customer {2} after {3} GETs")
    @CsvSource({"4, 4, 2, 4", "20, 20, 7, 20", "20, 4, 2, 4"})
    void everyLookupFailingIsBlockedAfterTheMinimumSamples(
        int window, int minimumCalls, int expectedCustomer, int expectedGets
    ) {
        ResilientPaymentProvider provider = providerWithWindow(window, minimumCalls);
        for (int i = 0; i < MAX_CUSTOMERS * 3; i++) {
            pg.replyNext(503, "");
        }

        int openedAtCustomer = 0;
        int getsUntilOpen = 0;
        long started = System.nanoTime();
        long elapsedUntilOpen = 0;
        for (int customer = 1; customer <= MAX_CUSTOMERS; customer++) {
            int before = pg.lookupCalls();
            PaymentObservation result = provider.findByTransactionKey(keys.getFirst());
            System.out.printf("window-all-failing-trace,%d,%d,customer-%d,%d,%s,%s%n", window, minimumCalls, customer,
                pg.lookupCalls() - before, result.unknownCause(), provider.lookupCircuitBreaker().getState());
            if (provider.lookupCircuitBreaker().getState() == State.OPEN) {
                openedAtCustomer = customer;
                getsUntilOpen = pg.lookupCalls();
                elapsedUntilOpen = Duration.ofNanos(System.nanoTime() - started).toMillis();
                break;
            }
        }
        pg.clearQueuedFaults();

        // OPEN 뒤의 다음 고객은 PG에 묻지 않는다.
        int before = pg.lookupCalls();
        assertThat(provider.findByTransactionKey(keys.getFirst()))
            .isEqualTo(PaymentObservation.unknown(UnknownCause.CIRCUIT_OPEN));
        assertThat(pg.lookupCalls()).isEqualTo(before);

        System.out.printf("window-all-failing,%d,%d,%d,%d,%d%n",
            window, minimumCalls, openedAtCustomer, getsUntilOpen, elapsedUntilOpen);
        assertThat(openedAtCustomer).isEqualTo(expectedCustomer);
        assertThat(getsUntilOpen).isEqualTo(expectedGets);
        assertThat(provider.lookupCircuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(expectedGets);
        assertThat(provider.lookupCircuitBreaker().getMetrics().getFailureRate()).isEqualTo(100.0f);
    }

    private ResilientPaymentProvider providerWithWindow(int window, int minimumCalls) {
        LookupResilienceSettings base = LookupSettingsFixture.LEARNING;
        LookupResilienceSettings settings = new LookupResilienceSettings(base.budget(), base.attemptEstimate(),
            base.maxAttempts(), base.firstWait(), base.waitMultiplier(), window, minimumCalls,
            base.failureRateThreshold(), base.openWait(), base.halfOpenCalls());
        return new ResilientPaymentProvider(adapter, settings, new TestClock());
    }
}
