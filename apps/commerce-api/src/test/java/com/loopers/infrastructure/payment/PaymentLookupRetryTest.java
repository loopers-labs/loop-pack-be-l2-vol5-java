package com.loopers.infrastructure.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.loopers.application.payment.PaymentGateway;
import com.loopers.support.payment.LocalPgServer;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

class PaymentLookupRetryTest {
    private static final String PROVIDER_ORDER_ID = "LP-ORD-0000000042";
    private LocalPgServer localPgServer;
    private HttpPaymentGateway httpGateway;
    private CircuitBreaker lookupCircuit;
    private PaymentGateway paymentGateway;

    @BeforeEach
    void setUp() {
        localPgServer = LocalPgServer.start();
        httpGateway = new HttpPaymentGateway(RestClient.builder(), URI.create(localPgServer.baseUrl()),
                URI.create("http://127.0.0.1:8080/api/v1/payments/callback"),
                Duration.ofMillis(500), Duration.ofSeconds(2));
        lookupCircuit = new PaymentLookupCircuitConfig().paymentLookupCircuitBreaker(
                10, 10, 50, Duration.ofSeconds(10), 1);
        paymentGateway = gatewayWithBudget(Duration.ofMillis(200), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        localPgServer.releaseResponses();
        httpGateway.close();
        localPgServer.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {502, 503, 504})
    void 첫_PG_GET이_일시적인_서버_오류로_실패하면_GET을_한_번_더_보낸다(int status) {
        // arrange
        localPgServer.respondToLookupRequestsWith(status);

        // act
        paymentGateway.findByOrderId(PROVIDER_ORDER_ID);

        // assert
        assertThat(localPgServer.lookupRequestCount()).isEqualTo(2);
    }

    @Test
    void 두_번의_PG_GET이_모두_실패하면_세_번째_GET은_보내지_않는다() {
        // arrange
        localPgServer.failPaymentLookups();

        // act
        Throwable lookupFailure = catchThrowable(() -> paymentGateway.findByOrderId(PROVIDER_ORDER_ID));

        // assert
        assertThat(lookupFailure).isInstanceOf(PaymentGateway.UnconfirmedException.class);
        assertThat(localPgServer.lookupRequestCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(LocalPgServer.TransactionStatus.class)
    void PG가_정상적인_결제_상태를_응답하면_GET을_재시도하지_않는다(LocalPgServer.TransactionStatus status) {
        // arrange
        paymentGateway.create(PROVIDER_ORDER_ID, 7_000, "TEST", "0000-0000-0000-0000");
        if (status == LocalPgServer.TransactionStatus.SUCCESS) {
            localPgServer.succeedPaymentForOrder(PROVIDER_ORDER_ID);
        } else if (status == LocalPgServer.TransactionStatus.FAILED) {
            localPgServer.failPaymentForOrder(PROVIDER_ORDER_ID);
        }

        // act
        paymentGateway.findByOrderId(PROVIDER_ORDER_ID);

        // assert
        assertThat(localPgServer.lookupRequestCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 429, 500})
    void PG가_재시도_대상이_아닌_HTTP_오류를_응답하면_GET을_한_번만_보낸다(int status) {
        // arrange
        localPgServer.respondToLookupRequestsWith(status);

        // act
        Throwable lookupFailure = catchThrowable(() -> paymentGateway.findByOrderId(PROVIDER_ORDER_ID));

        // assert
        assertThat(lookupFailure).isInstanceOf(PaymentGateway.UnconfirmedException.class);
        assertThat(localPgServer.lookupRequestCount()).isEqualTo(1);
    }

    @Test
    void PG_조회_응답이_JSON_규격에_맞지_않으면_GET을_재시도하지_않는다() {
        // arrange
        localPgServer.respondWithMalformedLookupResponse();

        // act
        Throwable invalidResponse = catchThrowable(() -> paymentGateway.findByOrderId(PROVIDER_ORDER_ID));

        // assert
        assertThat(invalidResponse).isInstanceOf(PaymentGateway.UnconfirmedException.class);
        assertThat(localPgServer.lookupRequestCount()).isEqualTo(1);
    }

    @Test
    void 최초_GET과_재시도_GET이_모두_실패하면_서킷에_각각_실패로_집계한다() {
        // arrange
        localPgServer.failPaymentLookups();

        // act
        catchThrowable(() -> paymentGateway.findByOrderId(PROVIDER_ORDER_ID));

        // assert
        assertThat(lookupCircuit.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
    }

    @Test
    void 서킷이_이미_OPEN이면_최초_GET도_보내지_않는다() {
        // arrange
        lookupCircuit.transitionToOpenState();

        // act
        Throwable blockedLookup = catchThrowable(() -> paymentGateway.findByOrderId(PROVIDER_ORDER_ID));

        // assert
        assertThat(blockedLookup).isInstanceOf(PaymentGateway.LookupBlockedException.class);
        assertThat(localPgServer.lookupRequestCount()).isZero();
    }

    @Test
    void 첫_GET_실패로_서킷이_OPEN되면_재시도_GET을_PG에_보내지_않는다() {
        // arrange
        lookupCircuit = new PaymentLookupCircuitConfig().paymentLookupCircuitBreaker(
                1, 1, 50, Duration.ofSeconds(10), 1);
        paymentGateway = gatewayWithBudget(Duration.ofMillis(200), Duration.ofSeconds(5));
        localPgServer.failPaymentLookups();

        // act
        Throwable blockedRetry = catchThrowable(() -> paymentGateway.findByOrderId(PROVIDER_ORDER_ID));

        // assert
        assertThat(blockedRetry).isInstanceOf(PaymentGateway.LookupBlockedException.class);
        assertThat(localPgServer.lookupRequestCount()).isEqualTo(1);
    }

    @Test
    void HALF_OPEN에서_허용한_GET이_실패하면_추가_GET없이_다시_OPEN이_된다() {
        // arrange
        lookupCircuit.transitionToOpenState();
        lookupCircuit.transitionToHalfOpenState();
        localPgServer.failPaymentLookups();

        // act
        catchThrowable(() -> paymentGateway.findByOrderId(PROVIDER_ORDER_ID));

        // assert
        assertThat(lookupCircuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(localPgServer.lookupRequestCount()).isEqualTo(1);
    }

    @Test
    void 첫_GET이_타임아웃된_뒤_PG가_응답할_수_있으면_GET을_한_번_더_보낸다() {
        // arrange
        localPgServer.withholdLookupResponse();
        lookupCircuit.getEventPublisher().onError(event -> localPgServer.releaseResponses());

        // act
        paymentGateway.findByOrderId(PROVIDER_ORDER_ID);

        // assert
        assertThat(localPgServer.lookupRequestCount()).isEqualTo(2);
    }

    @Test
    void 조회_응답_본문을_읽다가_타임아웃되면_GET을_한_번_더_보낸다() {
        // arrange
        localPgServer.withholdLookupResponseBody();
        lookupCircuit.getEventPublisher().onError(event -> localPgServer.releaseResponses());

        // act
        paymentGateway.findByOrderId(PROVIDER_ORDER_ID);

        // assert
        assertThat(localPgServer.lookupRequestCount()).isEqualTo(2);
    }

    @Test
    void 주문번호_조회_중_응답_본문이_끊기면_GET을_한_번_더_보낸다() {
        // arrange
        localPgServer.truncateLookupResponseBody();

        // act
        Throwable lookupFailure = catchThrowable(() -> paymentGateway.findByOrderId(PROVIDER_ORDER_ID));

        // assert
        assertThat(lookupFailure).isInstanceOf(PaymentGateway.UnconfirmedException.class);
        assertThat(localPgServer.lookupRequestCount()).isEqualTo(2);
    }

    @Test
    void 거래키_조회_중_응답_본문이_끊기면_같은_거래를_한_번_더_조회한다() {
        // arrange
        var acceptedPayment = paymentGateway.create(PROVIDER_ORDER_ID, 7_000, "TEST", "0000-0000-0000-0000");
        localPgServer.truncateLookupResponseBody();

        // act
        Throwable lookupFailure = catchThrowable(() -> paymentGateway.findByTransactionKey(acceptedPayment.transactionKey()));

        // assert
        assertThat(lookupFailure).isInstanceOf(PaymentGateway.UnconfirmedException.class);
        assertThat(localPgServer.lookupRequestCount()).isEqualTo(2);
        assertThat(localPgServer.lastLookupTransactionKey()).isEqualTo(acceptedPayment.transactionKey());
    }

    @Test
    void 총_예산이_개별_HTTP_타임아웃보다_짧으면_첫_GET도_남은_예산까지만_기다린다() {
        // arrange
        paymentGateway = gatewayWithBudget(Duration.ofMillis(200), Duration.ofMillis(300));
        localPgServer.withholdLookupResponse();

        // act
        long startedAt = System.nanoTime();
        Throwable lookupFailure = catchThrowable(() -> paymentGateway.findByOrderId(PROVIDER_ORDER_ID));
        var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        // assert
        assertThat(lookupFailure).isInstanceOf(PaymentGateway.UnconfirmedException.class);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void 첫_GET이_실패했어도_재시도_간격을_기다릴_예산이_없으면_추가_GET을_보내지_않는다() {
        // arrange
        paymentGateway = gatewayWithBudget(Duration.ofSeconds(2), Duration.ofSeconds(1));
        localPgServer.failPaymentLookups();

        // act
        catchThrowable(() -> paymentGateway.findByOrderId(PROVIDER_ORDER_ID));

        // assert
        assertThat(localPgServer.lookupRequestCount()).isEqualTo(1);
    }

    @Test
    void 재시도_GET도_개별_타임아웃을_새로_쓰지_않고_남은_총_예산까지만_기다린다() {
        // arrange
        paymentGateway = gatewayWithBudget(Duration.ofMillis(200), Duration.ofMillis(500));
        localPgServer.failNextPaymentLookup();
        localPgServer.withholdLookupResponse();

        // act
        long startedAt = System.nanoTime();
        Throwable lookupFailure = catchThrowable(() -> paymentGateway.findByOrderId(PROVIDER_ORDER_ID));
        var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        // assert
        assertThat(lookupFailure).isInstanceOf(PaymentGateway.UnconfirmedException.class);
        assertThat(localPgServer.lookupRequestCount()).isEqualTo(2);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void 결제_생성_POST의_응답이_유실되어도_POST를_재시도하지_않는다() {
        // arrange
        localPgServer.loseResponseAfterCreatingTransaction();

        // act
        Throwable creationFailure = catchThrowable(() ->
                paymentGateway.create(PROVIDER_ORDER_ID, 7_000, "TEST", "0000-0000-0000-0000"));

        // assert
        assertThat(creationFailure).isInstanceOf(PaymentGateway.UnconfirmedException.class);
        assertThat(localPgServer.createRequestCount()).isEqualTo(1);
    }

    @Test
    void 이미_중단된_요청은_PG_GET을_시작하지_않는다() {
        // arrange
        Thread.currentThread().interrupt();

        try {
            // act
            Throwable interruptedLookup = catchThrowable(() -> paymentGateway.findByOrderId(PROVIDER_ORDER_ID));

            // assert
            assertThat(interruptedLookup).isInstanceOf(PaymentGateway.UnconfirmedException.class);
            assertThat(localPgServer.lookupRequestCount()).isZero();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void 거래키_GET이_일시적인_서버_오류로_실패하면_같은_거래를_한_번_더_조회한다() {
        // arrange
        var acceptedPayment = paymentGateway.create(PROVIDER_ORDER_ID, 7_000, "TEST", "0000-0000-0000-0000");
        localPgServer.failNextPaymentLookup();

        // act
        var foundPayment = paymentGateway.findByTransactionKey(acceptedPayment.transactionKey());

        // assert
        assertThat(foundPayment.transactionKey()).isEqualTo(acceptedPayment.transactionKey());
        assertThat(localPgServer.lookupRequestCount()).isEqualTo(2);
    }

    @Test
    void 거래키_조회도_서킷이_OPEN이면_PG에_GET을_보내지_않는다() {
        // arrange
        lookupCircuit.transitionToOpenState();

        // act
        var blockedLookup = catchThrowable(() -> paymentGateway.findByTransactionKey("TEST-TX-1"));

        // assert
        assertThat(blockedLookup).isInstanceOf(PaymentGateway.LookupBlockedException.class);
        assertThat(localPgServer.lookupRequestCount()).isZero();
    }

    @Test
    void 거래키_조회도_최초_GET과_재시도_GET을_합친_총_예산까지만_기다린다() {
        // arrange
        var acceptedPayment = paymentGateway.create(PROVIDER_ORDER_ID, 7_000, "TEST", "0000-0000-0000-0000");
        paymentGateway = gatewayWithBudget(Duration.ofMillis(200), Duration.ofMillis(500));
        localPgServer.failNextPaymentLookup();
        localPgServer.withholdLookupResponse();

        // act
        long startedAt = System.nanoTime();
        var lookupFailure = catchThrowable(() -> paymentGateway.findByTransactionKey(acceptedPayment.transactionKey()));
        var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        // assert
        assertThat(lookupFailure).isInstanceOf(PaymentGateway.UnconfirmedException.class);
        assertThat(localPgServer.lookupRequestCount()).isEqualTo(2);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
    }

    private PaymentGateway gatewayWithBudget(Duration retryWait, Duration totalTimeout) {
        var circuitGateway = new CircuitBreakingPaymentGateway(httpGateway, lookupCircuit);
        return new RetryingPaymentGateway(circuitGateway, 2, retryWait, totalTimeout);
    }
}
