package com.loopers.infrastructure.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loopers.application.payment.PaymentGateway;
import com.loopers.support.payment.LocalPgServer;
import com.loopers.support.payment.AdjustableClock;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class PaymentLookupCircuitTest {
    private static final String PROVIDER_ORDER_ID = "LP-ORD-0000000042";
    private LocalPgServer localPgServer;
    private HttpPaymentGateway httpGateway;
    private PaymentGateway paymentGateway;
    private CircuitBreaker lookupCircuit;
    private AdjustableClock clock;

    @BeforeEach
    void setUp() {
        localPgServer = LocalPgServer.start();
        httpGateway = new HttpPaymentGateway(RestClient.builder(), URI.create(localPgServer.baseUrl()),
                URI.create("http://127.0.0.1:8080/api/v1/payments/callback"),
                Duration.ofMillis(500), Duration.ofMillis(500));
        var configuredCircuit = new PaymentLookupCircuitConfig().paymentLookupCircuitBreaker(
                10, 10, 50, Duration.ofSeconds(10), 1);
        clock = new AdjustableClock();
        var circuitConfig = CircuitBreakerConfig.from(configuredCircuit.getCircuitBreakerConfig()).clock(clock).build();
        lookupCircuit = CircuitBreaker.of("test-pg-lookup", circuitConfig);
        paymentGateway = new CircuitBreakingPaymentGateway(httpGateway, lookupCircuit);
    }

    @AfterEach
    void tearDown() {
        localPgServer.releaseResponses();
        httpGateway.close();
        localPgServer.close();
    }

    @Test
    void PG_조회가_10회_연속_실패한_뒤에는_추가_GET을_보내지_않는다() {
        // arrange
        openCircuitWithTenFailedLookups();

        // act
        Throwable lookupFailure = catchThrowable(() -> paymentGateway.findByOrderId(PROVIDER_ORDER_ID));

        // assert
        assertThat(lookupFailure).isInstanceOf(PaymentGateway.LookupBlockedException.class);
        assertThat(localPgServer.lookupRequestCount()).isEqualTo(10);
    }

    @Test
    void PG_조회가_모두_실패해도_최소_표본_10회가_모이기_전에는_차단하지_않는다() {
        // arrange
        localPgServer.failPaymentLookups();

        // act
        requestFailedLookups(9);

        // assert
        assertThat(lookupCircuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void PG_조회가_10회_연속_실패하면_서킷을_연다() {
        // arrange
        localPgServer.failPaymentLookups();

        // act
        requestFailedLookups(10);

        // assert
        assertThat(lookupCircuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void PG_조회_10회_중_4회만_실패하면_실패율이_50퍼센트_미만이므로_차단하지_않는다() {
        // arrange
        requestSuccessfulLookups(6);
        localPgServer.failPaymentLookups();

        // act
        requestFailedLookups(4);

        // assert
        assertThat(lookupCircuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void PG_조회_10회_중_5회가_실패하면_실패율이_50퍼센트이므로_차단한다() {
        // arrange
        requestSuccessfulLookups(5);
        localPgServer.failPaymentLookups();

        // act
        requestFailedLookups(5);

        // assert
        assertThat(lookupCircuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void 과거에_PG_조회가_성공했어도_최근_10회_중_5회가_실패하면_차단한다() {
        // arrange
        requestSuccessfulLookups(10);
        localPgServer.failPaymentLookups();
        requestFailedLookups(4);

        // act
        Throwable lookupFailure = catchThrowable(() -> paymentGateway.findByOrderId(PROVIDER_ORDER_ID));

        // assert
        assertThat(lookupFailure).isInstanceOf(PaymentGateway.UnconfirmedException.class);
        assertThat(lookupCircuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void 서킷이_열린_뒤_10초가_지나기_전에는_PG_조회가_회복되어도_GET을_보내지_않는다() {
        // arrange
        openCircuitWithTenFailedLookups();
        localPgServer.restorePaymentLookups();
        clock.advance(Duration.ofSeconds(9));

        // act
        Throwable lookupFailure = catchThrowable(() -> paymentGateway.findByOrderId(PROVIDER_ORDER_ID));

        // assert
        assertThat(lookupFailure).isInstanceOf(PaymentGateway.LookupBlockedException.class);
        assertThat(localPgServer.lookupRequestCount()).isEqualTo(10);
    }

    @Test
    void 서킷이_열린_뒤_10초가_지나면_PG에_시험_GET을_보낸다() {
        // arrange
        openCircuitWithTenFailedLookups();
        localPgServer.restorePaymentLookups();

        clock.advance(Duration.ofSeconds(11));

        // act
        paymentGateway.findByOrderId(PROVIDER_ORDER_ID);

        // assert
        assertThat(localPgServer.lookupRequestCount()).isEqualTo(11);
    }

    @Test
    void 차단_후_시험_조회가_성공하면_서킷을_닫는다() {
        // arrange
        openCircuitWithTenFailedLookups();
        localPgServer.restorePaymentLookups();
        clock.advance(Duration.ofSeconds(11));

        // act
        paymentGateway.findByOrderId(PROVIDER_ORDER_ID);

        // assert
        assertThat(lookupCircuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void 차단_후_시험_조회도_실패하면_서킷을_다시_연다() {
        // arrange
        openCircuitWithTenFailedLookups();
        clock.advance(Duration.ofSeconds(11));

        // act
        Throwable lookupFailure = catchThrowable(() -> paymentGateway.findByOrderId(PROVIDER_ORDER_ID));

        // assert
        assertThat(lookupFailure).isInstanceOf(PaymentGateway.UnconfirmedException.class);
        assertThat(lookupCircuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void 차단_후_시험_조회가_진행_중이면_두_번째_GET은_보내지_않는다() throws Exception {
        // arrange
        openCircuitWithTenFailedLookups();
        localPgServer.restorePaymentLookups();
        localPgServer.withholdLookupResponse();
        clock.advance(Duration.ofSeconds(11));

        try (var requests = Executors.newSingleThreadExecutor()) {
            var firstProbe = requests.submit(() -> paymentGateway.findByOrderId(PROVIDER_ORDER_ID));
            try {
                assertThat(localPgServer.awaitLookupRequest()).isTrue();
                assertThat(lookupCircuit.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

                // act
                Throwable secondLookupFailure = catchThrowable(() -> paymentGateway.findByOrderId(PROVIDER_ORDER_ID));

                // assert
                assertThat(secondLookupFailure).isInstanceOf(PaymentGateway.LookupBlockedException.class);
                assertThat(localPgServer.lookupRequestCount()).isEqualTo(11);
            } finally {
                localPgServer.releaseResponses();
            }
            firstProbe.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void PG_조회_서킷이_열려_있어도_별도_결제_생성_POST는_보낸다() {
        // arrange
        openCircuitWithTenFailedLookups();

        // act
        paymentGateway.create(PROVIDER_ORDER_ID, 7_000, "TEST", "0000-0000-0000-0000");

        // assert
        assertThat(localPgServer.createRequestCount()).isEqualTo(1);
    }

    @Test
    void PG_조회_응답이_10회_연속_시간을_초과하면_서킷을_연다() {
        // arrange
        localPgServer.withholdLookupResponse();

        // act
        requestFailedLookups(10);

        // assert
        assertThat(lookupCircuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void 조회_응답_본문을_읽다가_타임아웃되면_서킷에_실패로_집계한다() {
        // arrange
        localPgServer.withholdLookupResponseBody();

        // act
        catchThrowable(() -> paymentGateway.findByOrderId(PROVIDER_ORDER_ID));

        // assert
        assertThat(lookupCircuit.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void 조회_응답_본문이_끊기면_서킷에_실패로_집계한다() {
        // arrange
        localPgServer.truncateLookupResponseBody();

        // act
        catchThrowable(() -> paymentGateway.findByOrderId(PROVIDER_ORDER_ID));

        // assert
        assertThat(lookupCircuit.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void 조회_응답이_잘못된_JSON이면_서킷의_통신_실패_표본에서_제외한다() {
        // arrange
        localPgServer.respondWithMalformedLookupResponse();

        // act
        catchThrowable(() -> paymentGateway.findByOrderId(PROVIDER_ORDER_ID));

        // assert
        assertThat(lookupCircuit.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void PG가_조회_요청을_4xx로_거절하면_가용성_장애_표본에_포함하지_않는다() {
        // arrange
        localPgServer.rejectPaymentLookups();

        // act
        requestFailedLookups(10);

        // assert
        assertThat(lookupCircuit.getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(lookupCircuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private void openCircuitWithTenFailedLookups() {
        localPgServer.failPaymentLookups();
        requestFailedLookups(10);
    }

    private void requestFailedLookups(int count) {
        for (int i = 0; i < count; i++) {
            assertThrows(PaymentGateway.UnconfirmedException.class,
                    () -> paymentGateway.findByOrderId(PROVIDER_ORDER_ID));
        }
    }

    private void requestSuccessfulLookups(int count) {
        for (int i = 0; i < count; i++) {
            paymentGateway.findByOrderId(PROVIDER_ORDER_ID);
        }
    }
}
