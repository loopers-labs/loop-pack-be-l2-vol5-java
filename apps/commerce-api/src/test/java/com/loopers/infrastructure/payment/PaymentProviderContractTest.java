package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.pg.PgPaymentRequestResult;
import com.loopers.application.payment.pg.PgPaymentStatusCheckResult;
import com.loopers.application.payment.pg.PgPaymentRequestCommand;
import com.loopers.application.payment.pg.PgStatus;
import com.loopers.application.payment.pg.PgTransactionInfo;
import com.loopers.support.pg.FakePgServer;
import com.loopers.support.pg.FakePgServer.FakeTransaction;
import com.loopers.support.pg.FakePgServer.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 adapter 가 로컬 PG 계약대로 HTTP 를 보내고, 응답을 포트의 결과로 옮기는지 확인한다. 무작위 지연·확률은 쓰지 않는다.
 * 업무 순서(주문·재고 상태)는 여기서 다루지 않는다.
 */
class PaymentProviderContractTest {

    private static final String ORDER_ID = "LP-ORD-0000000042";
    private static final String CARD_NO = "0000-0000-0000-0000";
    private static final String CALLBACK_URL = "http://127.0.0.1:8080/api/v1/payments/callback";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakePgServer pg;
    private PgHttpClient client;
    private PaymentGatewayImpl gateway;

    @BeforeEach
    void setUp() throws IOException {
        pg = FakePgServer.start();
        useClientWithReadTimeout(Duration.ofSeconds(3));
    }

    @AfterEach
    void tearDown() {
        client.shutdown();
        pg.close();
    }

    // 지연이 있는 시나리오는 읽기 timeout 을 짧게 잡은 client 로 바꿔서 확인한다.
    private void useClientWithReadTimeout(Duration readTimeout) {
        if (client != null) {
            client.shutdown();
        }
        PaymentPgProperties properties = new PaymentPgProperties(
            pg.baseUrl(), CALLBACK_URL, FakePgServer.USER_ID, Duration.ofSeconds(1), readTimeout, readTimeout
        );
        client = new PgHttpClient(properties, MAPPER);
        gateway = new PaymentGatewayImpl(client);
    }

    private PgPaymentRequestCommand request() {
        return new PgPaymentRequestCommand(ORDER_ID, "TEST", CARD_NO, 7_000);
    }

    @DisplayName("결제를 생성할 때,")
    @Nested
    class Create {
        @DisplayName("POST /api/v1/payments 로 계정 헤더와 JSON(문자열 식별자·정수 금액·콜백 주소)을 보낸다.")
        @Test
        void sendsContractShapedRequest() throws IOException {
            // act
            gateway.requestPayment(request());

            // assert
            RecordedRequest sent = pg.lastRequest();
            assertThat(sent.method()).isEqualTo("POST");
            assertThat(sent.rawPath()).isEqualTo("/api/v1/payments");
            assertThat(sent.headers()).containsEntry("x-user-id", "135135");
            assertThat(sent.headers().get("content-type")).startsWith("application/json");
            JsonNode body = MAPPER.readTree(sent.body());
            assertThat(body.get("orderId").isTextual()).isTrue();
            assertThat(body.get("orderId").asText()).isEqualTo(ORDER_ID);
            assertThat(body.get("cardType").asText()).isEqualTo("TEST");
            assertThat(body.get("cardNo").asText()).isEqualTo(CARD_NO);
            assertThat(body.get("amount").isIntegralNumber()).isTrue();
            assertThat(body.get("amount").asLong()).isEqualTo(7_000L);
            assertThat(body.get("callbackUrl").asText()).isEqualTo(CALLBACK_URL);
            assertThat(pg.createCalls()).isEqualTo(1);
        }

        @DisplayName("202 PENDING 응답은 수락이다. 거래가 1건 생기고 거래 키·주문 식별자·금액·상태를 돌려준다.")
        @Test
        void returnsAccepted_whenPending() {
            // act
            PgPaymentRequestResult result = gateway.requestPayment(request());

            // assert
            assertThat(result.outcome()).isEqualTo(PgPaymentRequestResult.Outcome.ACCEPTED);
            assertThat(result.transaction())
                .isEqualTo(new PgTransactionInfo("TEST-TX-0001", ORDER_ID, 7_000, PgStatus.PENDING));
            assertThat(pg.transactionCount()).isEqualTo(1);
            assertThat(pg.createCalls()).isEqualTo(1);
        }

        @DisplayName("거래 생성 전 확정 거절(422 + 정확한 code + false + 주문·금액 일치)은 효과 없는 거절이다. 거래는 0건이다.")
        @Test
        void returnsRejectedNoEffect_whenConfirmedRejection() {
            // arrange
            pg.onCreate(FakePgServer.rejectBeforeCreate());

            // act
            PgPaymentRequestResult result = gateway.requestPayment(request());

            // assert
            assertThat(result.outcome()).isEqualTo(PgPaymentRequestResult.Outcome.REJECTED_WITHOUT_TRANSACTION);
            assertThat(pg.transactionCount()).isZero();
            assertThat(pg.createCalls()).isEqualTo(1);
        }

        static Stream<Arguments> notConfirmedRejections() {
            String ok = "\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":7000";
            return Stream.of(
                Arguments.of("본문 없는 422", 422, ""),
                Arguments.of("다른 오류 code", 422,
                    "{\"code\":\"SOMETHING_ELSE\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":7000,\"transactionCreated\":false}"),
                Arguments.of("transactionCreated 가 true", 422, "{" + ok + ",\"transactionCreated\":true}"),
                Arguments.of("transactionCreated 가 문자열 false", 422, "{" + ok + ",\"transactionCreated\":\"false\"}"),
                Arguments.of("transactionCreated 누락", 422, "{" + ok + "}"),
                Arguments.of("주문 식별자 불일치", 422,
                    "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"LP-ORD-0000000099\",\"amount\":7000,\"transactionCreated\":false}"),
                Arguments.of("금액 불일치", 422,
                    "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":1,\"transactionCreated\":false}"),
                Arguments.of("깨진 JSON", 422, "{\"code\":"),
                Arguments.of("400", 400, "{\"code\":\"BAD_REQUEST\"}"),
                Arguments.of("500", 500, "{\"code\":\"ERROR\"}"),
                Arguments.of("503", 503, ""),
                Arguments.of("수락 계약(202)이 아닌 200", 200,
                    "{\"transactionKey\":\"TEST-TX-0001\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":7000,\"status\":\"PENDING\"}")
            );
        }

        @DisplayName("확정 거절 조건이 하나라도 어긋나거나 그 밖의 비정상 응답이면 결과 미확인이다. 재시도하지 않는다.")
        @ParameterizedTest(name = "{0}")
        @MethodSource("notConfirmedRejections")
        void returnsUnknown_whenNotConfirmedRejection(String name, int status, String body) {
            // arrange
            pg.onCreate(FakePgServer.respond(status, body));

            // act
            PgPaymentRequestResult result = gateway.requestPayment(request());

            // assert
            assertThat(result.outcome()).isEqualTo(PgPaymentRequestResult.Outcome.UNKNOWN);
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(pg.transactionCount()).isZero();
        }

        static Stream<Arguments> untrustedAcceptances() {
            return Stream.of(
                Arguments.of("빈 본문", ""),
                Arguments.of("깨진 JSON", "{\"transactionKey\":"),
                Arguments.of("거래 키 누락",
                    "{\"orderId\":\"LP-ORD-0000000042\",\"amount\":7000,\"status\":\"PENDING\"}"),
                Arguments.of("주문 식별자 불일치",
                    "{\"transactionKey\":\"TEST-TX-0001\",\"orderId\":\"LP-ORD-0000000099\",\"amount\":7000,\"status\":\"PENDING\"}"),
                Arguments.of("금액 불일치",
                    "{\"transactionKey\":\"TEST-TX-0001\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":1,\"status\":\"PENDING\"}"),
                Arguments.of("문자열 금액",
                    "{\"transactionKey\":\"TEST-TX-0001\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":\"7000\",\"status\":\"PENDING\"}"),
                Arguments.of("알 수 없는 상태",
                    "{\"transactionKey\":\"TEST-TX-0001\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":7000,\"status\":\"DONE\"}"),
                Arguments.of("생성 응답이 이미 SUCCESS (수락과 최종 성공을 구분한다)",
                    "{\"transactionKey\":\"TEST-TX-0001\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":7000,\"status\":\"SUCCESS\"}")
            );
        }

        @DisplayName("202 라도 본문이 요청과 맞는 PENDING 거래가 아니면 수락으로 믿지 않고 결과 미확인으로 둔다.")
        @ParameterizedTest(name = "{0}")
        @MethodSource("untrustedAcceptances")
        void returnsUnknown_whenAcceptedBodyIsNotTrusted(String name, String body) {
            // arrange
            pg.onCreate(FakePgServer.respond(202, body));

            // act
            PgPaymentRequestResult result = gateway.requestPayment(request());

            // assert
            assertThat(result.outcome()).isEqualTo(PgPaymentRequestResult.Outcome.UNKNOWN);
            assertThat(pg.createCalls()).isEqualTo(1);
        }

        @DisplayName("거래가 생긴 뒤 응답만 유실되면 결과 미확인이다. 거래는 1건, create 는 1회이며 자동으로 다시 보내지 않는다.")
        @Test
        void returnsUnknown_andDoesNotResend_whenResponseIsLost() {
            // arrange
            pg.onCreate(FakePgServer.acceptThenDropResponse());

            // act
            PgPaymentRequestResult result = gateway.requestPayment(request());

            // assert
            assertThat(result.outcome()).isEqualTo(PgPaymentRequestResult.Outcome.UNKNOWN);
            assertThat(pg.transactionCount()).isEqualTo(1);
            assertThat(pg.createCalls()).isEqualTo(1);
        }

        @DisplayName("읽기 timeout 안에 응답이 없으면 결과 미확인이다. 거래는 남아 있고 create 는 1회뿐이다.")
        @Test
        void returnsUnknown_andDoesNotResend_whenReadTimesOut() {
            // arrange
            useClientWithReadTimeout(Duration.ofMillis(300));
            pg.onCreate(FakePgServer.acceptAfterDelay(Duration.ofMillis(1_500)));

            // act
            PgPaymentRequestResult result = gateway.requestPayment(request());

            // assert
            assertThat(result.outcome()).isEqualTo(PgPaymentRequestResult.Outcome.UNKNOWN);
            assertThat(pg.transactionCount()).isEqualTo(1);
            assertThat(pg.createCalls()).isEqualTo(1);
        }

        @DisplayName("응답이 유실된 거래는 주문 식별자로 조회하면 1건으로 찾을 수 있다 (거래가 없다는 뜻이 아니다).")
        @Test
        void findsTransactionByOrder_afterResponseIsLost() {
            // arrange
            pg.onCreate(FakePgServer.acceptThenDropResponse());
            gateway.requestPayment(request());

            // act
            PgPaymentStatusCheckResult lookup = gateway.checkPaymentStatusByOrder(ORDER_ID);

            // assert
            assertThat(lookup.outcome()).isEqualTo(PgPaymentStatusCheckResult.Outcome.ANSWERED);
            assertThat(lookup.transactions()).hasSize(1);
            assertThat(pg.createCalls()).isEqualTo(1);
        }
    }

    @DisplayName("거래 키로 조회할 때,")
    @Nested
    class FindTransaction {
        @DisplayName("GET /api/v1/payments/{transactionKey} 로 계정 헤더를 보내고, 거래 한 건을 돌려준다.")
        @Test
        void sendsContractShapedRequest_andReturnsTransaction() {
            // arrange
            FakeTransaction seeded = pg.addTransaction(ORDER_ID, 7_000, "SUCCESS");

            // act
            PgPaymentStatusCheckResult lookup = gateway.checkPaymentStatusByTransactionKey(seeded.key());

            // assert
            RecordedRequest sent = pg.lastRequest();
            assertThat(sent.method()).isEqualTo("GET");
            assertThat(sent.rawPath()).isEqualTo("/api/v1/payments/" + seeded.key());
            assertThat(sent.headers()).containsEntry("x-user-id", "135135");
            assertThat(lookup.outcome()).isEqualTo(PgPaymentStatusCheckResult.Outcome.ANSWERED);
            assertThat(lookup.transactions())
                .containsExactly(new PgTransactionInfo(seeded.key(), ORDER_ID, 7_000, PgStatus.SUCCESS));
            assertThat(pg.lookupByKeyCalls()).isEqualTo(1);
        }

        @DisplayName("PENDING·SUCCESS·FAILED 상태를 각각 구분해 돌려준다.")
        @Test
        void mapsEachExternalStatus() {
            // arrange
            FakeTransaction pending = pg.addTransaction("LP-ORD-0000000001", 100, "PENDING");
            FakeTransaction success = pg.addTransaction("LP-ORD-0000000002", 200, "SUCCESS");
            FakeTransaction failed = pg.addTransaction("LP-ORD-0000000003", 300, "FAILED");

            // act & assert
            assertThat(statusOf(gateway.checkPaymentStatusByTransactionKey(pending.key()))).isEqualTo(PgStatus.PENDING);
            assertThat(statusOf(gateway.checkPaymentStatusByTransactionKey(success.key()))).isEqualTo(PgStatus.SUCCESS);
            assertThat(statusOf(gateway.checkPaymentStatusByTransactionKey(failed.key()))).isEqualTo(PgStatus.FAILED);
        }

        private PgStatus statusOf(PgPaymentStatusCheckResult lookup) {
            return lookup.transactions().get(0).status();
        }

        @DisplayName("PG 가 모르는 거래 키(404)는 조회 불가가 아니라 거래 없음이다.")
        @Test
        void returnsNotFound_whenUnknownKey() {
            assertThat(gateway.checkPaymentStatusByTransactionKey("TEST-TX-9999").outcome()).isEqualTo(PgPaymentStatusCheckResult.Outcome.KEY_NOT_FOUND);
        }

        static Stream<Arguments> unreadableLookups() {
            return Stream.of(
                Arguments.of("500", 500, "{\"code\":\"ERROR\"}"),
                Arguments.of("503", 503, ""),
                Arguments.of("401", 401, "{\"code\":\"UNAUTHORIZED\"}"),
                Arguments.of("깨진 JSON", 200, "{\"transactionKey\":"),
                Arguments.of("빈 본문", 200, ""),
                Arguments.of("문자열 금액", 200,
                    "{\"transactionKey\":\"TEST-TX-0001\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":\"7000\",\"status\":\"SUCCESS\"}"),
                Arguments.of("알 수 없는 상태", 200,
                    "{\"transactionKey\":\"TEST-TX-0001\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":7000,\"status\":\"DONE\"}"),
                Arguments.of("객체가 아닌 본문", 200, "[]")
            );
        }

        @DisplayName("서버 오류나 해석할 수 없는 응답이면 조회 불가이고, 거래 없음으로 바꾸지 않는다.")
        @ParameterizedTest(name = "{0}")
        @MethodSource("unreadableLookups")
        void returnsUnavailable_whenLookupFails(String name, int status, String body) {
            // arrange
            pg.respondToLookupsWith(status, body);

            // act
            PgPaymentStatusCheckResult lookup = gateway.checkPaymentStatusByTransactionKey("TEST-TX-0001");

            // assert
            assertThat(lookup.outcome()).isEqualTo(PgPaymentStatusCheckResult.Outcome.COULD_NOT_CHECK);
        }

        @DisplayName("읽기 timeout 안에 응답이 없으면 조회 불가다.")
        @Test
        void returnsUnavailable_whenReadTimesOut() {
            // arrange
            useClientWithReadTimeout(Duration.ofMillis(300));
            pg.delayLookups(Duration.ofMillis(1_500));

            // act
            PgPaymentStatusCheckResult lookup = gateway.checkPaymentStatusByTransactionKey("TEST-TX-0001");

            // assert
            assertThat(lookup.outcome()).isEqualTo(PgPaymentStatusCheckResult.Outcome.COULD_NOT_CHECK);
        }
    }

    @DisplayName("주문 식별자로 거래 목록을 조회할 때,")
    @Nested
    class FindTransactions {
        @DisplayName("GET /api/v1/payments?orderId= 로 계정 헤더와 주문 식별자를 보내고, 같은 주문의 거래만 돌려준다.")
        @Test
        void sendsContractShapedRequest_andReturnsTransactionsOfTheOrder() {
            // arrange
            FakeTransaction mine = pg.addTransaction(ORDER_ID, 7_000, "PENDING");
            pg.addTransaction("LP-ORD-0000000099", 500, "SUCCESS");

            // act
            PgPaymentStatusCheckResult lookup = gateway.checkPaymentStatusByOrder(ORDER_ID);

            // assert
            RecordedRequest sent = pg.lastRequest();
            assertThat(sent.method()).isEqualTo("GET");
            assertThat(sent.rawPath()).isEqualTo("/api/v1/payments");
            assertThat(sent.rawQuery()).isEqualTo("orderId=" + ORDER_ID);
            assertThat(sent.headers()).containsEntry("x-user-id", "135135");
            assertThat(lookup.outcome()).isEqualTo(PgPaymentStatusCheckResult.Outcome.ANSWERED);
            assertThat(lookup.transactions())
                .containsExactly(new PgTransactionInfo(mine.key(), ORDER_ID, 7_000, PgStatus.PENDING));
            assertThat(pg.lookupByOrderCalls()).isEqualTo(1);
        }

        @DisplayName("거래가 없으면 빈 목록이다. 조회 불가와 구분한다.")
        @Test
        void returnsEmptyList_whenNoTransaction() {
            PgPaymentStatusCheckResult lookup = gateway.checkPaymentStatusByOrder(ORDER_ID);
            assertThat(lookup.outcome()).isEqualTo(PgPaymentStatusCheckResult.Outcome.ANSWERED);
            assertThat(lookup.transactions()).isEmpty();
        }

        @DisplayName("같은 주문의 거래가 여러 건이면 모두 돌려준다. 하나를 고르는 일은 호출하는 쪽의 판단이다.")
        @Test
        void returnsAllTransactions_whenMultiple() {
            // arrange
            pg.addTransaction(ORDER_ID, 7_000, "PENDING");
            pg.addTransaction(ORDER_ID, 7_000, "SUCCESS");

            // act
            PgPaymentStatusCheckResult lookup = gateway.checkPaymentStatusByOrder(ORDER_ID);

            // assert
            assertThat(lookup.outcome()).isEqualTo(PgPaymentStatusCheckResult.Outcome.ANSWERED);
            assertThat(lookup.transactions()).extracting(PgTransactionInfo::status)
                .containsExactly(PgStatus.PENDING, PgStatus.SUCCESS);
        }

        @DisplayName("query 값은 URI builder 로 인코딩한다. 특수문자가 다른 파라미터로 해석되지 않는다.")
        @Test
        void encodesQueryValue() {
            // arrange
            String tricky = "LP ORD&x=1";

            // act
            gateway.checkPaymentStatusByOrder(tricky);

            // assert
            RecordedRequest sent = pg.lastRequest();
            assertThat(sent.rawQuery()).isEqualTo("orderId=LP%20ORD%26x%3D1");
            assertThat(sent.queryParams()).hasSize(1).containsEntry("orderId", tricky);
        }

        static Stream<Arguments> unreadableLists() {
            return Stream.of(
                Arguments.of("500", 500, "{\"code\":\"ERROR\"}"),
                Arguments.of("깨진 JSON", 200, "[{\"transactionKey\":"),
                Arguments.of("빈 본문", 200, ""),
                Arguments.of("배열이 아닌 본문", 200, "{}"),
                Arguments.of("해석할 수 없는 원소", 200, "[{\"transactionKey\":\"TEST-TX-0001\"}]")
            );
        }

        @DisplayName("서버 오류나 해석할 수 없는 응답이면 조회 불가다. 빈 목록으로 바꾸지 않는다.")
        @ParameterizedTest(name = "{0}")
        @MethodSource("unreadableLists")
        void returnsUnavailable_whenLookupFails(String name, int status, String body) {
            // arrange
            pg.respondToLookupsWith(status, body);

            // act
            PgPaymentStatusCheckResult lookup = gateway.checkPaymentStatusByOrder(ORDER_ID);

            // assert
            assertThat(lookup.outcome()).isEqualTo(PgPaymentStatusCheckResult.Outcome.COULD_NOT_CHECK);
        }

        @DisplayName("읽기 timeout 안에 응답이 없으면 조회 불가다.")
        @Test
        void returnsUnavailable_whenReadTimesOut() {
            // arrange
            useClientWithReadTimeout(Duration.ofMillis(300));
            pg.delayLookups(Duration.ofMillis(1_500));

            // act
            PgPaymentStatusCheckResult lookup = gateway.checkPaymentStatusByOrder(ORDER_ID);

            // assert
            assertThat(lookup.outcome()).isEqualTo(PgPaymentStatusCheckResult.Outcome.COULD_NOT_CHECK);
        }
    }

    @DisplayName("카드 번호를 다룰 때,")
    @Nested
    class CardNumber {
        @DisplayName("요청의 문자열 표현에는 카드 번호가 드러나지 않는다.")
        @Test
        void doesNotExposeCardNumber_inToString() {
            assertThat(request().toString()).doesNotContain(CARD_NO).contains(ORDER_ID);
        }

        @DisplayName("PG 에 전달할 때만 본문에 들어가고, 조회 요청에는 실리지 않는다.")
        @Test
        void sendsCardNumberOnlyInCreateBody() {
            // act
            gateway.requestPayment(request());
            gateway.checkPaymentStatusByOrder(ORDER_ID);

            // assert
            List<RecordedRequest> sent = pg.requests();
            assertThat(sent.get(0).body()).contains(CARD_NO);
            assertThat(sent.get(1).body()).doesNotContain(CARD_NO);
            assertThat(sent.get(1).rawQuery()).doesNotContain(CARD_NO);
        }
    }
}
