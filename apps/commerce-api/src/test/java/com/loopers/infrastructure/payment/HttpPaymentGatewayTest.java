package com.loopers.infrastructure.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.support.payment.LocalPgServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.stream.Stream;

class HttpPaymentGatewayTest {
    private static final String PROVIDER_ACCOUNT = "135135";
    private static final String TEST_CARD_TYPE = "TEST";
    private static final String TEST_CARD_NUMBER = "0000-0000-0000-0000";
    private static final URI CALLBACK_URL = URI.create("http://127.0.0.1:8080/api/v1/payments/callback");

    private LocalPgServer localPgServer;
    private HttpPaymentGateway paymentGateway;

    @BeforeEach
    void setUp() {
        localPgServer = LocalPgServer.start();
        paymentGateway = new HttpPaymentGateway(
                RestClient.builder().messageConverters(converters -> converters.forEach(converter -> {
                    if (converter instanceof MappingJackson2HttpMessageConverter jsonConverter) {
                        jsonConverter.getObjectMapper().enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);
                    }
                })), URI.create(localPgServer.baseUrl()), CALLBACK_URL,
                Duration.ofMillis(500), Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        if (paymentGateway != null) {
            paymentGateway.close();
        }
        if (localPgServer != null) {
            localPgServer.close();
        }
    }

    @Test
    void 결제_생성을_요청하면_PG_계정_헤더와_규격에_맞는_JSON을_POST로_한_번_전송한다()
            throws JsonProcessingException {
        // arrange
        String providerOrderId = "LP-ORD-0000000042";
        long paymentAmount = 7_000;
        var expectedRequestBody = new ObjectMapper().readTree("""
                {
                  "orderId": "LP-ORD-0000000042",
                  "amount": 7000,
                  "cardType": "TEST",
                  "cardNo": "0000-0000-0000-0000",
                  "callbackUrl": "http://127.0.0.1:8080/api/v1/payments/callback"
                }
                """);

        // act
        paymentGateway.create(providerOrderId, paymentAmount, TEST_CARD_TYPE, TEST_CARD_NUMBER);

        // assert
        assertThat(localPgServer.createRequestCount()).isEqualTo(1);
        assertThat(localPgServer.receivedAccount()).isEqualTo(PROVIDER_ACCOUNT);
        assertThat(localPgServer.receivedRequest()).isEqualTo(expectedRequestBody);
    }

    @Test
    void PG가_결제를_처리중으로_접수하면_거래키와_주문번호와_금액을_반환한다() {
        // arrange
        String providerOrderId = "LP-ORD-0000000042";
        long paymentAmount = 7_000;

        // act
        var acceptedPayment = paymentGateway.create(providerOrderId, paymentAmount, TEST_CARD_TYPE, TEST_CARD_NUMBER);

        // assert
        var transactionCreatedByPg = localPgServer.transactions().getFirst();
        assertThat(transactionCreatedByPg.status()).isEqualTo(LocalPgServer.TransactionStatus.PENDING);
        assertThat(acceptedPayment.transactionKey()).isEqualTo(transactionCreatedByPg.transactionKey());
        assertThat(acceptedPayment.providerOrderId()).isEqualTo(providerOrderId);
        assertThat(acceptedPayment.amount()).isEqualTo(paymentAmount);
    }

    @Test
    void PG에서_성공한_결제를_주문번호로_GET_조회하면_거래키와_결제_성공_상태를_반환한다() {
        // arrange
        String providerOrderId = "LP-ORD-0000000042";
        long paymentAmount = 7_000;
        var createdPayment = paymentGateway.create(providerOrderId, paymentAmount, TEST_CARD_TYPE, TEST_CARD_NUMBER);
        localPgServer.succeedPaymentForOrder(providerOrderId);

        // act
        var foundPayments = paymentGateway.findByOrderId(providerOrderId);

        // assert
        assertThat(localPgServer.lookupRequestCount()).isEqualTo(1);
        assertThat(localPgServer.lastLookupOrderId()).isEqualTo(providerOrderId);
        assertThat(localPgServer.receivedAccount()).isEqualTo(PROVIDER_ACCOUNT);
        assertThat(localPgServer.createRequestCount()).isEqualTo(1);
        assertThat(foundPayments).containsExactly(new PaymentGateway.ObservedPayment(
                createdPayment.transactionKey(), providerOrderId, paymentAmount, PaymentStatus.SUCCESS));
    }

    @Test
    void PG에_주문번호의_거래가_없으면_GET_조회는_빈_목록을_반환하고_거래를_생성하지_않는다() {
        // arrange
        String providerOrderId = "LP-ORD-0000000042";

        // act
        var foundPayments = paymentGateway.findByOrderId(providerOrderId);

        // assert
        assertThat(foundPayments).isEmpty();
        assertThat(localPgServer.createRequestCount()).isZero();
    }

    @Test
    void 결제_생성_응답의_주문번호가_요청한_주문번호와_다르면_PG_결과_미확인_오류가_발생한다() {
        // arrange
        String providerOrderId = "LP-ORD-0000000042";
        long paymentAmount = 7_000;
        localPgServer.respondWithDifferentOrderId();

        // act
        Throwable requestFailure = catchThrowable(() ->
                paymentGateway.create(providerOrderId, paymentAmount, TEST_CARD_TYPE, TEST_CARD_NUMBER));

        // assert
        assertThat(requestFailure).isInstanceOf(PaymentGateway.UnconfirmedException.class);
    }

    @Test
    void PG에서_실패한_결제를_조회하면_어댑터가_FAILED_상태를_반환한다() {
        // arrange
        String providerOrderId = "LP-ORD-0000000042";
        paymentGateway.create(providerOrderId, 7_000, TEST_CARD_TYPE, TEST_CARD_NUMBER);
        localPgServer.failPaymentForOrder(providerOrderId);

        // act
        var paymentsFoundByPg = paymentGateway.findByOrderId(providerOrderId);

        // assert
        assertThat(paymentsFoundByPg).singleElement()
                .extracting(PaymentGateway.ObservedPayment::status).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void PG_응답_금액이_결제_요청_금액과_다르면_어댑터가_예외를_던진다() {
        // arrange
        String providerOrderId = "LP-ORD-0000000042";
        long paymentAmount = 7_000;
        localPgServer.respondWithDifferentAmount();

        // act
        Throwable responseValidationFailure = catchThrowable(() ->
                paymentGateway.create(providerOrderId, paymentAmount, TEST_CARD_TYPE, TEST_CARD_NUMBER));

        // assert
        assertThat(responseValidationFailure).isInstanceOf(PaymentGateway.UnconfirmedException.class);
    }
    @Test
    void 거래키로_GET_조회하면_기존_PG_결제_한_건을_반환한다() {
        // arrange
        String providerOrderId = "LP-ORD-0000000042";
        var acceptedPayment = paymentGateway.create(providerOrderId, 7_000, TEST_CARD_TYPE, TEST_CARD_NUMBER);
        localPgServer.succeedPaymentForOrder(providerOrderId);

        // act
        var foundPayment = paymentGateway.findByTransactionKey(acceptedPayment.transactionKey());

        // assert
        assertThat(foundPayment).isEqualTo(new PaymentGateway.ObservedPayment(
                acceptedPayment.transactionKey(), providerOrderId, 7_000, PaymentStatus.SUCCESS));
        assertThat(localPgServer.lastLookupTransactionKey()).isEqualTo(acceptedPayment.transactionKey());
        assertThat(localPgServer.createRequestCount()).isEqualTo(1);
    }

    @Test
    void 주문번호의_특수문자도_GET_쿼리에서_원래_주문번호로_전달한다() {
        // arrange
        String providerOrderId = "LP-ORD &한글+/?=42%";
        var acceptedPayment = paymentGateway.create(providerOrderId, 7_000, TEST_CARD_TYPE, TEST_CARD_NUMBER);

        // act
        var foundPayments = paymentGateway.findByOrderId(providerOrderId);

        // assert
        assertThat(localPgServer.lastLookupOrderId()).isEqualTo(providerOrderId);
        assertThat(foundPayments).singleElement()
                .extracting(PaymentGateway.ObservedPayment::transactionKey).isEqualTo(acceptedPayment.transactionKey());
    }

    @Test
    void PG에_없는_거래키를_조회하면_결과를_확정할_수_없다() {
        // arrange
        String missingTransactionKey = "missing/key?with=special&value=한글";

        // act
        var lookupFailure = catchThrowable(() -> paymentGateway.findByTransactionKey(missingTransactionKey));

        // assert
        assertThat(lookupFailure).isInstanceOf(PaymentGateway.UnconfirmedException.class);
        assertThat(localPgServer.lastLookupTransactionKey()).isEqualTo(missingTransactionKey);
        assertThat(localPgServer.createRequestCount()).isZero();
    }

    @Test
    void PG가_거래_생성_전_거절을_보장하면_결제_효과가_없는_거절로_구분한다() {
        // arrange
        String providerOrderId = "LP-ORD-0000000042";
        long paymentAmount = 7_000;
        localPgServer.rejectBeforeCreatingTransaction();

        // act
        var rejection = catchThrowable(() -> paymentGateway.create(
                providerOrderId, paymentAmount, TEST_CARD_TYPE, TEST_CARD_NUMBER));

        // assert
        assertThat(rejection).isInstanceOfSatisfying(PaymentGateway.RejectedBeforeCreateException.class, failure -> {
            assertThat(failure.providerOrderId()).isEqualTo(providerOrderId);
            assertThat(failure.amount()).isEqualTo(paymentAmount);
        });
        assertThat(localPgServer.transactions()).isEmpty();
    }

    @Test
    void PG가_결제_생성에_503을_응답해도_HTTP_클라이언트가_POST를_다시_보내지_않는다() {
        // arrange
        localPgServer.respondBeforeCreateWith(503, "");

        // act
        catchThrowable(() -> paymentGateway.create(
                "LP-ORD-0000000042", 7_000, TEST_CARD_TYPE, TEST_CARD_NUMBER));

        // assert
        assertThat(localPgServer.createRequestCount()).isEqualTo(1);
    }

    @Test
    void PG_생성_응답_금액이_정수가_아니면_미확인_오류로_처리한다() {
        // arrange
        localPgServer.respondWithCreateBody("""
                {"transactionKey":"TEST-TX-1","orderId":"LP-ORD-0000000042","amount":7000.9,"status":"PENDING"}
                """);

        // act
        var invalidResponse = catchThrowable(() -> paymentGateway.create(
                "LP-ORD-0000000042", 7_000, TEST_CARD_TYPE, TEST_CARD_NUMBER));

        // assert
        assertThat(invalidResponse).isInstanceOf(PaymentGateway.UnconfirmedException.class);
    }

    @Test
    void PG_조회_상태가_문자열이_아닌_숫자이면_미확인_오류로_처리한다() {
        // arrange
        localPgServer.respondWithLookupBody("""
                [{"transactionKey":"TEST-TX-1","orderId":"LP-ORD-0000000042","amount":7000,"status":2}]
                """);

        // act
        var invalidResponse = catchThrowable(() -> paymentGateway.findByOrderId("LP-ORD-0000000042"));

        // assert
        assertThat(invalidResponse).isInstanceOf(PaymentGateway.UnconfirmedException.class);
    }

    @Test
    void PG_주문번호_조회_응답이_배열이_아니면_미확인_오류로_처리한다() {
        // arrange
        localPgServer.respondWithLookupBody("""
                {"transactionKey":"TEST-TX-1","orderId":"LP-ORD-0000000042","amount":7000,"status":"SUCCESS"}
                """);

        // act
        var invalidResponse = catchThrowable(() -> paymentGateway.findByOrderId("LP-ORD-0000000042"));

        // assert
        assertThat(invalidResponse).isInstanceOf(PaymentGateway.UnconfirmedException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unverifiedRejections")
    void 거래_생성_전_거절_보장을_검증할_수_없으면_결제_결과를_미확인으로_남긴다(
            String reason, int responseStatus, String responseBody) {
        // arrange
        localPgServer.respondBeforeCreateWith(responseStatus, responseBody);

        // act
        var requestFailure = catchThrowable(() -> paymentGateway.create(
                "LP-ORD-0000000042", 7_000, TEST_CARD_TYPE, TEST_CARD_NUMBER));

        // assert
        assertThat(requestFailure).isInstanceOf(PaymentGateway.UnconfirmedException.class);
    }

    private static Stream<Arguments> unverifiedRejections() {
        String validBody = """
                {"code":"TEST_PG_REJECTED_BEFORE_CREATE","orderId":"LP-ORD-0000000042",
                 "amount":7000,"transactionCreated":false}
                """;
        return Stream.of(
                Arguments.of("422 이외의 4xx", 400, validBody),
                Arguments.of("서버 오류", 503, validBody),
                Arguments.of("거절 코드 불일치", 422, validBody.replace("BEFORE_CREATE", "OTHER")),
                Arguments.of("주문번호 불일치", 422, validBody.replace("0000000042", "0000000043")),
                Arguments.of("금액 불일치", 422, validBody.replace("7000", "7001")),
                Arguments.of("거래가 생성됨", 422, validBody.replace("false", "true")),
                Arguments.of("문자열 false", 422, validBody.replace("false", "\"false\"")),
                Arguments.of("문자열 금액", 422, validBody.replace("7000", "\"7000\"")),
                Arguments.of("소수 금액", 422, validBody.replace("7000", "7000.0")),
                Arguments.of("금액 누락", 422, validBody.replace("\"amount\":7000,", "")),
                Arguments.of("JSON 아님", 422, "not-json"),
                Arguments.of("JSON 뒤에 다른 내용이 있음", 422, validBody + "garbage"),
                Arguments.of("응답 본문 없음", 422, ""));
    }

}
