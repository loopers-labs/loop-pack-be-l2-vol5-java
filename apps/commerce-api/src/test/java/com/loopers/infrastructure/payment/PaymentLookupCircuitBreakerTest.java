package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentObservation.UnknownCause;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.support.TestClock;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.support.payment.LookupSettingsFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED;
import static io.github.resilience4j.circuitbreaker.CircuitBreaker.State.HALF_OPEN;
import static io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조회 회로를 retry 안쪽에 두었을 때(fallback → retry → 회로 → GET) 실제 GET 수와 회로 상태를 확인한다.
 * 회로는 최근 4개·최소 4개·실패율 50% 이상·OPEN 10초·HALF_OPEN 시험 2번이다.
 */
class PaymentLookupCircuitBreakerTest {

    private static final String KEY = "TEST-TX-1";
    private final TestClock clock = new TestClock();
    private FakePaymentServer pg;
    private TestPgPaymentAdapter adapter;
    private ResilientPaymentProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        pg = new FakePaymentServer();
        adapter = new PaymentProviderConfig().paymentProvider(pg.baseUrl(), "http://127.0.0.1:8080/api/v1/payments/callback",
            Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(500));
        provider = new ResilientPaymentProvider(adapter, LookupSettingsFixture.LEARNING, clock);
        provider.create(new PaymentRequest("LP-ORD-0000000042", "TEST", "0000-0000-0000-0000", 7000));
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
    void everyRetriedLookupIsCountedSoContinuousFailureOpensTheCircuitOnTheSecondCall() {
        failNextLookups(6);

        PaymentObservation first = provider.findByTransactionKey(KEY);

        assertThat(first).isEqualTo(PaymentObservation.unknown(UnknownCause.TEMPORARY_FAILURE));
        assertThat(pg.lookupCalls()).isEqualTo(3);
        assertThat(provider.lookupCircuitBreaker().getState()).isEqualTo(CLOSED);

        PaymentObservation second = provider.findByTransactionKey(KEY);

        // 네 번째 실패로 OPEN이 되고, 남은 재시도는 회로에 막혀 GET 없이 끝난다.
        assertThat(second).isEqualTo(PaymentObservation.unknown(UnknownCause.CIRCUIT_OPEN));
        assertThat(pg.lookupCalls()).isEqualTo(4);
        assertThat(provider.lookupCircuitBreaker().getState()).isEqualTo(OPEN);
    }

    @Test
    void openCircuitReturnsImmediatelyWithoutLookupOrRetryWait() {
        openCircuit();
        int lookupsBefore = pg.lookupCalls();

        long started = System.nanoTime();
        PaymentObservation result = provider.findByOrderId("LP-ORD-0000000042");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(result).isEqualTo(PaymentObservation.unknown(UnknownCause.CIRCUIT_OPEN));
        assertThat(pg.lookupCalls()).isEqualTo(lookupsBefore);
        assertThat(elapsed).isLessThan(Duration.ofMillis(100));
    }

    @Test
    void answeredFailuresAndUnexpectedResponsesDoNotOpenTheCircuit() {
        pg.changeStatus(KEY, "FAILED");
        for (int i = 0; i < 4; i++) {
            assertThat(provider.findByTransactionKey(KEY).transactions()).hasSize(1);
        }
        for (int i = 0; i < 4; i++) {
            pg.replyNext(200, "not-json");
            assertThat(provider.findByTransactionKey(KEY))
                .isEqualTo(PaymentObservation.unknown(UnknownCause.UNEXPECTED_RESPONSE));
        }

        assertThat(provider.lookupCircuitBreaker().getState()).isEqualTo(CLOSED);
        assertThat(provider.lookupCircuitBreaker().getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(4);
        assertThat(provider.lookupCircuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(pg.lookupCalls()).isEqualTo(8);
    }

    @Test
    void unexpectedHalfOpenResponsesCountAsSuccessButRemainUnknown() {
        openCircuit();
        clock.advance(Duration.ofSeconds(11));
        int lookupsBefore = pg.lookupCalls();
        pg.replyNext(200, "not-json");
        pg.replyNext(200, "not-json");

        assertThat(provider.findByTransactionKey(KEY))
            .isEqualTo(PaymentObservation.unknown(UnknownCause.UNEXPECTED_RESPONSE));
        assertThat(provider.lookupCircuitBreaker().getState()).isEqualTo(HALF_OPEN);
        assertThat(provider.lookupCircuitBreaker().getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
        assertThat(provider.lookupCircuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();

        assertThat(provider.findByTransactionKey(KEY))
            .isEqualTo(PaymentObservation.unknown(UnknownCause.UNEXPECTED_RESPONSE));
        assertThat(provider.lookupCircuitBreaker().getState()).isEqualTo(CLOSED);
        assertThat(pg.lookupCalls() - lookupsBefore).isEqualTo(2);
        assertThat(pg.createCalls()).isEqualTo(1);
    }

    @Test
    void recoveredPgIsNotAskedUntilTheWaitPassesThenTwoProbesCloseTheCircuit() {
        openCircuit();
        int lookupsBefore = pg.lookupCalls();

        clock.advance(Duration.ofSeconds(9));
        assertThat(provider.findByTransactionKey(KEY))
            .isEqualTo(PaymentObservation.unknown(UnknownCause.CIRCUIT_OPEN));
        assertThat(pg.lookupCalls()).isEqualTo(lookupsBefore);

        clock.advance(Duration.ofSeconds(2));
        // 자동 전환이 꺼져 있어 시간이 지나도 다음 요청이 오기 전까지는 OPEN이다.
        assertThat(provider.lookupCircuitBreaker().getState()).isEqualTo(OPEN);

        assertThat(provider.findByTransactionKey(KEY).kind()).isEqualTo(PaymentObservation.Kind.OBSERVED);
        assertThat(provider.lookupCircuitBreaker().getState()).isEqualTo(HALF_OPEN);
        assertThat(provider.findByTransactionKey(KEY).kind()).isEqualTo(PaymentObservation.Kind.OBSERVED);
        assertThat(provider.lookupCircuitBreaker().getState()).isEqualTo(CLOSED);
        assertThat(pg.lookupCalls()).isEqualTo(lookupsBefore + 2);
    }

    @Test
    void oneRetriedCallCanUseBothProbesAndReopenTheCircuitEvenIfItsAnswerArrives() {
        openCircuit();
        clock.advance(Duration.ofSeconds(11));
        int lookupsBefore = pg.lookupCalls();
        pg.replyNext(503, "");

        PaymentObservation result = provider.findByTransactionKey(KEY);

        // 시험 1번째는 503, retry가 한 시험 2번째는 정상. 2개 중 실패 50%라 다시 OPEN이다.
        assertThat(result.kind()).isEqualTo(PaymentObservation.Kind.OBSERVED);
        assertThat(pg.lookupCalls()).isEqualTo(lookupsBefore + 2);
        assertThat(provider.lookupCircuitBreaker().getState()).isEqualTo(OPEN);
    }

    private void openCircuit() {
        failNextLookups(4);
        provider.findByTransactionKey(KEY);
        provider.findByTransactionKey(KEY);
        assertThat(provider.lookupCircuitBreaker().getState()).isEqualTo(OPEN);
    }

    private void failNextLookups(int count) {
        for (int i = 0; i < count; i++) {
            pg.replyNext(503, "");
        }
    }
}
