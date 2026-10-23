package com.loopers.infrastructure.payment.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.vo.PaymentCreateResult;
import com.loopers.domain.payment.gateway.PaymentGatewayRequest;
import com.loopers.domain.payment.vo.PaymentLookupResult;
import com.loopers.domain.payment.vo.ProviderTransaction;
import com.loopers.domain.payment.enums.ProviderTransactionStatus;
import com.loopers.support.pg.TestPgGateways;
import com.loopers.support.pg.TestPgServer;
import com.loopers.support.pg.TestPgServer.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** HTTP 형식 · 응답 분류 · 숨은 재시도 여부만 확인함. 상태 전이는 PaymentTest, 재조회 · 회로는 TestPgLookupResilienceTest 가 확인함 */
class PaymentProviderContractTest {

    private static final String ORDER_ID = "LP-ORD-0000000042";
    private static final long AMOUNT = 7_000L;
    private static final String CALLBACK_URL = "http://127.0.0.1:8080/api/v1/payments/callback";
    private static final Duration READ_TIMEOUT = Duration.ofMillis(500);
    private static final PaymentGatewayRequest REQUEST = new PaymentGatewayRequest(ORDER_ID, "TEST", "0000-0000-0000-0000", AMOUNT);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TestPgServer pg;
    private TestPgPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        pg = TestPgServer.start();
        gateway = gatewayFor(pg.baseUrl());
    }

    @AfterEach
    void tearDown() {
        pg.close();
    }

    /** 재조회 없이 한 번만 보내 응답 분류만 봄 */
    private TestPgPaymentGateway gatewayFor(String baseUrl) {
        return TestPgGateways.gateway(baseUrl, READ_TIMEOUT, TestPgGateways.singleAttemptPolicy());
    }

    @DisplayName("생성 요청은, ")
    @Nested
    class CreateRequest {
        @DisplayName("POST /api/v1/payments 에 제공자 계정 헤더와 JSON 본문(문자열 ID · 정수 금액 · 고정 callback URL)으로 한 번 보낸다.")
        @Test
        void sendsContractShape() throws Exception {
            // act
            gateway.create(REQUEST);

            // assert
            RecordedRequest request = pg.lastRequest();
            JsonNode body = objectMapper.readTree(request.body());
            assertAll(
                () -> assertThat(pg.createCount()).isEqualTo(1),
                () -> assertThat(request.method()).isEqualTo("POST"),
                () -> assertThat(request.path()).isEqualTo("/api/v1/payments"),
                () -> assertThat(request.header("X-USER-ID")).contains("135135"),
                () -> assertThat(request.header("Content-Type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/json")),
                () -> assertThat(body.path("orderId").isTextual()).isTrue(),
                () -> assertThat(body.path("orderId").textValue()).isEqualTo(ORDER_ID),
                () -> assertThat(body.path("cardType").textValue()).isEqualTo("TEST"),
                () -> assertThat(body.path("cardNo").textValue()).isEqualTo("0000-0000-0000-0000"),
                () -> assertThat(body.path("amount").isIntegralNumber()).isTrue(),
                () -> assertThat(body.path("amount").longValue()).isEqualTo(AMOUNT),
                () -> assertThat(body.path("callbackUrl").textValue()).isEqualTo(CALLBACK_URL)
            );
        }

        @DisplayName("요청 값의 문자열 표현에는 카드 번호가 드러나지 않는다.")
        @Test
        void masksCardNumberInToString() {
            assertThat(REQUEST.toString()).doesNotContain("0000-0000-0000-0000").contains("****");
        }
    }

    @DisplayName("생성 응답이, ")
    @Nested
    class CreateResponse {
        @DisplayName("202 + PENDING + 같은 주문 · 금액이면, 거래 키와 함께 생성 수락이다.")
        @Test
        void mapsAccepted() {
            // act
            PaymentCreateResult result = gateway.create(REQUEST);

            // assert
            String transactionKey = pg.transactionsOf(ORDER_ID).get(0).transactionKey();
            assertAll(
                () -> assertThat(result).isEqualTo(new PaymentCreateResult.Accepted(transactionKey)),
                () -> assertThat(pg.transactions()).hasSize(1)
            );
        }

        @DisplayName("계약대로 422 확정 거절이면, 효과 0건 확정 거절이고 거래는 0건이다.")
        @Test
        void mapsRejectedWithoutEffect() {
            // arrange
            pg.willRejectBeforeCreate();

            // act
            PaymentCreateResult result = gateway.create(REQUEST);

            // assert
            assertAll(
                () -> assertThat(result).isInstanceOf(PaymentCreateResult.RejectedWithoutEffect.class),
                () -> assertThat(pg.transactions()).isEmpty(),
                () -> assertThat(pg.createCount()).isEqualTo(1)
            );
        }

        static Stream<Arguments> unknownResponses() {
            String rejected = "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"%s\",\"amount\":%d,\"transactionCreated\":%s}";
            String accepted = "{\"transactionKey\":\"%s\",\"orderId\":\"%s\",\"amount\":%d,\"status\":\"%s\"}";
            return Stream.of(
                Arguments.of("본문 없는 422", 422, ""),
                Arguments.of("다른 code 의 422", 422, "{\"code\":\"LIMIT_EXCEEDED\",\"orderId\":\"" + ORDER_ID + "\",\"amount\":7000,\"transactionCreated\":false}"),
                Arguments.of("transactionCreated=true 인 422", 422, rejected.formatted(ORDER_ID, AMOUNT, "true")),
                Arguments.of("transactionCreated 가 문자열인 422", 422, rejected.formatted(ORDER_ID, AMOUNT, "\"false\"")),
                Arguments.of("transactionCreated 가 없는 422", 422, "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"" + ORDER_ID + "\",\"amount\":7000}"),
                Arguments.of("주문이 다른 422", 422, rejected.formatted("LP-ORD-0000000043", AMOUNT, "false")),
                Arguments.of("금액이 다른 422", 422, rejected.formatted(ORDER_ID, AMOUNT + 1, "false")),
                Arguments.of("주문이 다른 202", 202, accepted.formatted("TEST-TX-0042", "LP-ORD-0000000043", AMOUNT, "PENDING")),
                Arguments.of("금액이 다른 202", 202, accepted.formatted("TEST-TX-0042", ORDER_ID, AMOUNT + 1, "PENDING")),
                Arguments.of("PENDING 이 아닌 202", 202, accepted.formatted("TEST-TX-0042", ORDER_ID, AMOUNT, "SUCCESS")),
                Arguments.of("거래 키가 빈 202", 202, accepted.formatted("", ORDER_ID, AMOUNT, "PENDING")),
                Arguments.of("금액이 소수인 202", 202, "{\"transactionKey\":\"TEST-TX-0042\",\"orderId\":\"" + ORDER_ID + "\",\"amount\":7000.5,\"status\":\"PENDING\"}"),
                Arguments.of("해석할 수 없는 JSON", 202, "{not-json"),
                Arguments.of("400", 400, "{\"code\":\"BAD_REQUEST\"}"),
                Arguments.of("500", 500, "{\"code\":\"INTERNAL\"}")
            );
        }

        @DisplayName("계약과 정확히 맞지 않으면, 효과 0건의 증거로 보지 않고 결과 미확인이다.")
        @ParameterizedTest(name = "{0}")
        @MethodSource("unknownResponses")
        void mapsUnknown(String name, int status, String body) {
            // arrange
            pg.willRespondToCreate(status, body);

            // act
            PaymentCreateResult result = gateway.create(REQUEST);

            // assert
            assertAll(
                () -> assertThat(result).isInstanceOf(PaymentCreateResult.Unknown.class),
                () -> assertThat(pg.createCount()).isEqualTo(1)
            );
        }

        @DisplayName("거래를 만든 뒤 응답만 끊기면, 결과 미확인이고 생성 요청은 다시 보내지 않는다. (숨은 재시도 없음)")
        @Test
        void mapsDroppedResponseToUnknown_withoutHiddenRetry() {
            // arrange
            pg.willDropResponseAfterCreate();

            // act
            PaymentCreateResult result = gateway.create(REQUEST);

            // assert
            assertAll(
                () -> assertThat(result).isInstanceOf(PaymentCreateResult.Unknown.class),
                () -> assertThat(pg.createCount()).isEqualTo(1),
                () -> assertThat(pg.transactionsOf(ORDER_ID)).hasSize(1)
            );
        }

        @DisplayName("응답 대기 한도를 넘기면, 한도 근처에서 결과 미확인으로 끝나고 생성 요청은 한 번이다.")
        @Test
        void mapsTimeoutToUnknown() {
            // arrange
            pg.willDelayResponseAfterCreate(Duration.ofSeconds(3));
            long startedAt = System.nanoTime();

            // act
            PaymentCreateResult result = gateway.create(REQUEST);

            // assert
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
            assertAll(
                () -> assertThat(result).isInstanceOf(PaymentCreateResult.Unknown.class),
                () -> assertThat(elapsed).isLessThan(Duration.ofSeconds(2)),
                () -> assertThat(pg.createCount()).isEqualTo(1),
                () -> assertThat(pg.transactionsOf(ORDER_ID)).hasSize(1)
            );
        }

        @DisplayName("PG 에 연결할 수 없으면, 결과 미확인이다.")
        @Test
        void mapsConnectionFailureToUnknown() {
            // arrange
            TestPgServer stopped = TestPgServer.start();
            String baseUrl = stopped.baseUrl();
            stopped.close();

            // act
            PaymentCreateResult result = gatewayFor(baseUrl).create(REQUEST);

            // assert
            assertThat(result).isInstanceOf(PaymentCreateResult.Unknown.class);
        }
    }

    @DisplayName("주문 단위 조회는, ")
    @Nested
    class LookupByOrder {
        @DisplayName("GET /api/v1/payments?orderId= 에 제공자 계정 헤더로 보내고, 같은 주문의 거래를 그대로 옮긴다.")
        @Test
        void sendsContractShape_andMapsTransactions() {
            // arrange
            String pending = pg.addTransaction(ORDER_ID, AMOUNT, "PENDING");
            String succeeded = pg.addTransaction(ORDER_ID, AMOUNT, "SUCCESS");
            pg.addTransaction("LP-ORD-0000000043", AMOUNT, "FAILED");

            // act
            PaymentLookupResult result = gateway.findByProviderOrderId(ORDER_ID);

            // assert
            RecordedRequest request = pg.lastRequest();
            assertAll(
                () -> assertThat(request.method()).isEqualTo("GET"),
                () -> assertThat(request.path()).isEqualTo("/api/v1/payments"),
                () -> assertThat(request.queryParam("orderId")).contains(ORDER_ID),
                () -> assertThat(request.header("X-USER-ID")).contains("135135"),
                () -> assertThat(result).isEqualTo(new PaymentLookupResult.Found(List.of(
                    new ProviderTransaction(pending, ORDER_ID, AMOUNT, ProviderTransactionStatus.PENDING),
                    new ProviderTransaction(succeeded, ORDER_ID, AMOUNT, ProviderTransactionStatus.SUCCESS))))
            );
        }

        @DisplayName("거래가 없으면, 빈 목록으로 돌려준다. (조회 불가와 다름)")
        @Test
        void mapsEmptyArray() {
            PaymentLookupResult result = gateway.findByProviderOrderId(ORDER_ID);

            assertThat(result).isEqualTo(new PaymentLookupResult.Found(List.of()));
        }

        @DisplayName("주문 값의 예약 문자도 인코딩해, PG 가 받은 값이 보낸 값과 같다.")
        @Test
        void encodesQueryValue() {
            // arrange
            String orderId = "LP ORD+&=/?#가";

            // act
            gateway.findByProviderOrderId(orderId);

            // assert
            RecordedRequest request = pg.lastRequest();
            assertAll(
                () -> assertThat(request.queryParam("orderId")).contains(orderId),
                () -> assertThat(request.rawQuery()).doesNotContain(" ", "+", "#").startsWith("orderId=")
            );
        }

        static Stream<Arguments> unavailableResponses() {
            return Stream.of(
                Arguments.of("500", 500, "{\"code\":\"INTERNAL\"}"),
                Arguments.of("404", 404, "{}"),
                Arguments.of("배열이 아닌 200", 200, "{\"transactionKey\":\"TEST-TX-0042\"}"),
                Arguments.of("해석할 수 없는 200", 200, "[{"),
                Arguments.of("모르는 상태 값", 200, "[{\"transactionKey\":\"TEST-TX-0042\",\"orderId\":\"" + ORDER_ID + "\",\"amount\":7000,\"status\":\"CANCELLED\"}]"),
                Arguments.of("금액이 문자열", 200, "[{\"transactionKey\":\"TEST-TX-0042\",\"orderId\":\"" + ORDER_ID + "\",\"amount\":\"7000\",\"status\":\"SUCCESS\"}]"),
                Arguments.of("거래 키 없음", 200, "[{\"orderId\":\"" + ORDER_ID + "\",\"amount\":7000,\"status\":\"SUCCESS\"}]")
            );
        }

        @DisplayName("200 배열이 아니거나 거래 형식이 계약과 다르면, 조회 불가다.")
        @ParameterizedTest(name = "{0}")
        @MethodSource("unavailableResponses")
        void mapsUnavailable(String name, int status, String body) {
            // arrange
            pg.lookupWillRespond(status, body);

            // act
            PaymentLookupResult result = gateway.findByProviderOrderId(ORDER_ID);

            // assert
            assertThat(result).isInstanceOf(PaymentLookupResult.Unavailable.class);
        }

        @DisplayName("응답 대기 한도를 넘기면, 한도 근처에서 조회 불가로 끝난다.")
        @Test
        void mapsTimeoutToUnavailable() {
            // arrange
            pg.lookupWillDelay(Duration.ofSeconds(3));
            long startedAt = System.nanoTime();

            // act
            PaymentLookupResult result = gateway.findByProviderOrderId(ORDER_ID);

            // assert
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
            assertAll(
                () -> assertThat(result).isInstanceOf(PaymentLookupResult.Unavailable.class),
                () -> assertThat(elapsed).isLessThan(Duration.ofSeconds(2)),
                () -> assertThat(pg.lookupCount()).isEqualTo(1)
            );
        }

        @DisplayName("PG 에 연결할 수 없으면, 조회 불가다.")
        @Test
        void mapsConnectionFailureToUnavailable() {
            // arrange
            TestPgServer stopped = TestPgServer.start();
            String baseUrl = stopped.baseUrl();
            stopped.close();

            // act
            PaymentLookupResult result = gatewayFor(baseUrl).findByProviderOrderId(ORDER_ID);

            // assert
            assertThat(result).isInstanceOf(PaymentLookupResult.Unavailable.class);
        }
    }

    @DisplayName("거래 키 단건 조회는, ")
    @Nested
    class LookupByTransactionKey {
        @DisplayName("GET /api/v1/payments/{transactionKey} 로 보내고 거래 한 건을 옮긴다.")
        @Test
        void sendsContractShape_andMapsTransaction() {
            // arrange
            PaymentCreateResult created = gateway.create(REQUEST);
            String transactionKey = ((PaymentCreateResult.Accepted) created).transactionKey();
            pg.complete(transactionKey, "SUCCESS");

            // act
            PaymentLookupResult result = gateway.findByTransactionKey(transactionKey);

            // assert
            RecordedRequest request = pg.lastRequest();
            assertAll(
                () -> assertThat(request.method()).isEqualTo("GET"),
                () -> assertThat(request.path()).isEqualTo("/api/v1/payments/" + transactionKey),
                () -> assertThat(request.header("X-USER-ID")).contains("135135"),
                () -> assertThat(result).isEqualTo(new PaymentLookupResult.Found(List.of(
                    new ProviderTransaction(transactionKey, ORDER_ID, AMOUNT, ProviderTransactionStatus.SUCCESS))))
            );
        }

        @DisplayName("없는 거래 키(404)면, 빈 목록이다. 생성 확정 거절과 다르다.")
        @Test
        void mapsNotFoundToEmpty() {
            PaymentLookupResult result = gateway.findByTransactionKey("TEST-TX-9999");

            assertThat(result).isEqualTo(new PaymentLookupResult.Found(List.of()));
        }
    }
}
