package com.loopers.domains.payment.out;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.common.domain.Money;
import com.loopers.domains.payment.app.port.PaymentCancelCommand;
import com.loopers.domains.payment.app.port.PaymentCancelResult;
import com.loopers.domains.payment.app.port.PaymentCreateCommand;
import com.loopers.domains.payment.app.port.PaymentCreateResult;
import com.loopers.domains.payment.app.port.PaymentTransaction;
import com.loopers.domains.payment.app.port.PaymentTransactionLookup;
import com.loopers.domains.payment.app.port.PaymentTransactionStatus;
import com.loopers.domains.payment.app.port.PaymentTransactionsLookup;
import com.loopers.domains.payment.out.TestPgServer.CancelBehavior;
import com.loopers.domains.payment.out.TestPgServer.CreateBehavior;
import com.loopers.domains.payment.out.TestPgServer.LookupBehavior;
import com.loopers.domains.payment.out.TestPgServer.RecordedRequest;
import com.loopers.domains.payment.out.TestPgServer.StoredTransaction;
import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class TestPgPaymentGatewayTest {
    private static final String USER_ID = "135135";
    private static final String CALLBACK_URL = "http://localhost:8080/api/v1/payments/callback";
    private static final String ORDER_ID = "LP-ORD-0000000042";
    private static final String CARD_NO = "1234-5678-9814-1451";
    private static final long AMOUNT = 5_000;
    private static final Duration SERVER_DELAY = Duration.ofMillis(1_500);
    private static final Duration READ_TIMEOUT = Duration.ofMillis(400);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TestPgServer server;
    private TestPgPaymentGateway gateway;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() throws IOException {
        server = new TestPgServer(USER_ID, SERVER_DELAY);
        gateway = gatewayFor(server.baseUrl());
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(TestPgPaymentGateway.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        ((Logger) LoggerFactory.getLogger(TestPgPaymentGateway.class)).detachAppender(logs);
        server.close();
    }

    @Nested
    @DisplayName("요청 계약")
    class RequestContract {
        @Test
        @DisplayName("결제 생성은 POST /api/v1/payments로 X-USER-ID와 JSON 본문(숫자 amount, 설정의 callbackUrl)을 보낸다")
        void sendsCreateRequestByContract() throws IOException {
            gateway.create(command());

            RecordedRequest request = server.requests().get(0);
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.rawPath()).isEqualTo("/api/v1/payments");
            assertThat(request.header("X-USER-ID")).isEqualTo(USER_ID);
            assertThat(request.header("Content-Type")).startsWith("application/json");
            JsonNode body = objectMapper.readTree(request.body());
            assertThat(body.get("orderId").isTextual()).isTrue();
            assertThat(body.get("orderId").asText()).isEqualTo(ORDER_ID);
            assertThat(body.get("cardType").asText()).isEqualTo("SAMSUNG");
            assertThat(body.get("cardNo").asText()).isEqualTo(CARD_NO);
            assertThat(body.get("amount").isIntegralNumber()).isTrue();
            assertThat(body.get("amount").asLong()).isEqualTo(AMOUNT);
            assertThat(body.get("callbackUrl").asText()).isEqualTo(CALLBACK_URL);
            assertThat(body.size()).isEqualTo(5);
        }

        @Test
        @DisplayName("거래 키 조회는 GET /api/v1/payments/{transactionKey}로 X-USER-ID를 보내고 키를 경로 값으로 인코딩한다")
        void sendsKeyLookupByContract() {
            gateway.findByTransactionKey("TR/1 2");

            RecordedRequest request = server.requests().get(0);
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.rawPath()).isEqualTo("/api/v1/payments/TR%2F1%202");
            assertThat(request.header("X-USER-ID")).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("주문 조회는 GET /api/v1/payments?orderId=로 보내고 특수 문자를 query 값으로 인코딩한다")
        void encodesOrderIdQuery() {
            String orderId = "LP-ORD 42&status=SUCCESS+가";

            gateway.findByOrderId(orderId);

            RecordedRequest request = server.requests().get(0);
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.rawPath()).isEqualTo("/api/v1/payments");
            assertThat(request.header("X-USER-ID")).isEqualTo(USER_ID);
            assertThat(request.rawQuery()).startsWith("orderId=").doesNotContain("&").doesNotContain("+");
            assertThat(URLDecoder.decode(request.rawQuery().substring("orderId=".length()), StandardCharsets.UTF_8))
                .isEqualTo(orderId);
        }
    }

    @Nested
    @DisplayName("생성 결과 분류")
    class CreateClassification {
        private static final Set<CreateBehavior> ACCEPTED_SCENES = EnumSet.of(
            CreateBehavior.ACCEPT_PENDING,
            CreateBehavior.ACCEPT_SUCCESS,
            CreateBehavior.ACCEPT_FAILED);
        private static final Set<CreateBehavior> REJECTED_NO_EFFECT_SCENES = EnumSet.of(
            CreateBehavior.REJECT_BEFORE_CREATE);
        private static final Set<CreateBehavior> UNKNOWN_SCENES = EnumSet.of(
            CreateBehavior.ACCEPT_WITH_200,
            CreateBehavior.ACCEPT_BLANK_TRANSACTION_KEY,
            CreateBehavior.ACCEPT_UNKNOWN_STATUS,
            CreateBehavior.ACCEPT_CANCELED_STATUS,
            CreateBehavior.ACCEPT_AMOUNT_MISMATCH,
            CreateBehavior.ACCEPT_ORDER_ID_MISMATCH,
            CreateBehavior.ACCEPT_AMOUNT_AS_STRING,
            CreateBehavior.ACCEPT_BROKEN_JSON,
            CreateBehavior.REJECT_WITHOUT_BODY,
            CreateBehavior.REJECT_TRANSACTION_CREATED,
            CreateBehavior.REJECT_OTHER_CODE,
            CreateBehavior.REJECT_AMOUNT_MISMATCH,
            CreateBehavior.ERROR_400,
            CreateBehavior.ERROR_500_AFTER_CREATE,
            CreateBehavior.ERROR_500_WITHOUT_CREATE,
            CreateBehavior.ERROR_500_WITH_REJECT_BODY,
            CreateBehavior.ERROR_500_ECHO_REQUEST,
            CreateBehavior.DELAY_AFTER_CREATE,
            CreateBehavior.DROP_CONNECTION_AFTER_CREATE);

        static Stream<CreateBehavior> acceptedScenes() {
            return ACCEPTED_SCENES.stream();
        }

        static Stream<CreateBehavior> rejectedNoEffectScenes() {
            return REJECTED_NO_EFFECT_SCENES.stream();
        }

        static Stream<CreateBehavior> unknownScenes() {
            return UNKNOWN_SCENES.stream();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("acceptedScenes")
        @DisplayName("202 응답이 접수 계약을 모두 만족하면 접수됨으로 분류한다")
        void classifiesAccepted(CreateBehavior behavior) {
            server.createBehavior(behavior);

            assertThat(gateway.create(command())).isInstanceOf(PaymentCreateResult.Accepted.class);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("rejectedNoEffectScenes")
        @DisplayName("422 응답이 거래 미생성 거절 계약을 모두 만족하면 효과 없는 거절로 분류한다")
        void classifiesRejectedNoEffect(CreateBehavior behavior) {
            server.createBehavior(behavior);

            assertThat(gateway.create(command())).isInstanceOf(PaymentCreateResult.RejectedNoEffect.class);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("unknownScenes")
        @DisplayName("접수·거절 계약을 벗어나거나 응답을 끝까지 받지 못하면 결과 모름으로 분류한다")
        void classifiesUnknown(CreateBehavior behavior) {
            server.createBehavior(behavior);

            assertThat(gateway.create(command())).isInstanceOf(PaymentCreateResult.Unknown.class);
        }

        @Test
        @DisplayName("닫힌 포트로 연결이 거부되면 미발송으로 분류한다")
        void connectionRefusedIsNotSent() throws IOException {
            TestPgPaymentGateway closedPortGateway = gatewayFor(URI.create("http://127.0.0.1:" + closedPort()));

            assertThat(closedPortGateway.create(command())).isInstanceOf(PaymentCreateResult.NotSent.class);
        }

        @Test
        @DisplayName("테스트 PG의 모든 생성 장면이 접수됨·효과 없는 거절·결과 모름 중 정확히 한 칸에 속한다")
        void everySceneBelongsToExactlyOneResult() {
            Set<CreateBehavior> classified = EnumSet.noneOf(CreateBehavior.class);
            classified.addAll(ACCEPTED_SCENES);
            classified.addAll(REJECTED_NO_EFFECT_SCENES);
            classified.addAll(UNKNOWN_SCENES);

            assertThat(classified).isEqualTo(EnumSet.allOf(CreateBehavior.class));
            assertThat(ACCEPTED_SCENES.size() + REJECTED_NO_EFFECT_SCENES.size() + UNKNOWN_SCENES.size())
                .isEqualTo(CreateBehavior.values().length);
        }

        @Test
        @DisplayName("202 접수 응답이면 PG가 보고한 거래 키·주문 식별자·금액·상태를 그대로 돌려준다")
        void returnsAcceptedTransaction() {
            server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);

            PaymentCreateResult result = gateway.create(command());

            String key = server.transactions().get(0).transactionKey();
            assertThat(result).isEqualTo(new PaymentCreateResult.Accepted(
                new PaymentTransaction(key, ORDER_ID, new Money(AMOUNT), PaymentTransactionStatus.SUCCESS)));
        }

        @Test
        @DisplayName("202 응답의 거래 상태가 CANCELED면 접수 계약 밖이라 결과 모름으로 분류한다")
        void canceledStatusOnCreateIsUnknown() {
            server.createBehavior(CreateBehavior.ACCEPT_CANCELED_STATUS);

            PaymentCreateResult result = gateway.create(command());

            assertThat(result).isInstanceOf(PaymentCreateResult.Unknown.class);
            assertThat(server.createCalls()).isEqualTo(1);
        }

        @Test
        @DisplayName("422 계약 거절이면 PG의 거절 코드를 돌려준다")
        void returnsRejectCode() {
            server.createBehavior(CreateBehavior.REJECT_BEFORE_CREATE);

            PaymentCreateResult result = gateway.create(command());

            assertThat(result).isEqualTo(new PaymentCreateResult.RejectedNoEffect(TestPgServer.REJECT_CODE));
            assertThat(server.transactions()).isEmpty();
        }

        @Test
        @DisplayName("read timeout이면 테스트 PG에는 거래가 만들어졌어도 결과 모름이며 다시 보내지 않는다")
        void readTimeoutIsUnknownEvenIfCreated() {
            server.createBehavior(CreateBehavior.DELAY_AFTER_CREATE);

            PaymentCreateResult result = gateway.create(command());

            assertThat(result).isInstanceOf(PaymentCreateResult.Unknown.class);
            assertThat(server.transactions()).hasSize(1);
            assertThat(server.createCalls()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("생성 공통 보장")
    class CreateGuarantees {
        @ParameterizedTest(name = "{0}")
        @EnumSource(CreateBehavior.class)
        @DisplayName("모든 장면에서 create를 정확히 한 번만 보낸다")
        void sendsCreateExactlyOnce(CreateBehavior behavior) {
            server.createBehavior(behavior);

            gateway.create(command());

            assertThat(server.createCalls()).isEqualTo(1);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(CreateBehavior.class)
        @DisplayName("모든 장면에서 카드 번호를 결과·로그에 남기지 않는다")
        void hidesCardNumber(CreateBehavior behavior) {
            server.createBehavior(behavior);

            PaymentCreateResult result = gateway.create(command());

            assertThat(result.toString()).doesNotContain(CARD_NO);
            assertThat(loggedText()).doesNotContain(CARD_NO);
        }

        @Test
        @DisplayName("연결이 거부되어 미발송이어도 카드 번호를 결과·로그에 남기지 않는다")
        void hidesCardNumberWhenNotSent() throws IOException {
            TestPgPaymentGateway closedPortGateway = gatewayFor(URI.create("http://127.0.0.1:" + closedPort()));

            PaymentCreateResult result = closedPortGateway.create(command());

            assertThat(result.toString()).doesNotContain(CARD_NO);
            assertThat(loggedText()).doesNotContain(CARD_NO);
        }
    }

    @Nested
    @DisplayName("예외 원인 분류")
    class ExceptionCause {
        static Stream<Arguments> causes() {
            return Stream.of(
                arguments("연결 거부(ConnectException) → 연결 단계", new ConnectException("Connection refused"), true),
                arguments("connect timeout(HttpConnectTimeoutException) → 연결 단계",
                    new HttpConnectTimeoutException("HTTP connect timed out"), true),
                arguments("read timeout(HttpTimeoutException) → 연결 단계 아님", new HttpTimeoutException("request timed out"),
                    false),
                arguments("그 밖의 IOException → 연결 단계 아님", new IOException("connection reset"), false)
            );
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("causes")
        @DisplayName("HTTP 클라이언트 예외의 원인 타입이 연결 단계 실패일 때만 미발송으로 본다")
        void classifiesByCauseType(String name, IOException cause, boolean expected) {
            ResourceAccessException exception = new ResourceAccessException("I/O error", cause);

            assertThat(TestPgPaymentGateway.failedBeforeSending(exception)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("조회")
    class Lookup {
        @ParameterizedTest(name = "거래 수={0}")
        @ValueSource(ints = {0, 1, 2})
        @DisplayName("주문 거래 확인은 저장 키와 무관하게 주문 거래 전체와 ALL 보증을 보고한다")
        void verifiesAllOrderTransactions(int count) {
            for (int index = 0; index < count; index++) {
                server.addTransaction(ORDER_ID, AMOUNT, "SUCCESS");
            }
            PaymentTransactionsLookup result = gateway.verifyOrder(ORDER_ID, "TR-STORED");
            assertThat(result).isInstanceOf(PaymentTransactionsLookup.Found.class);
            PaymentTransactionsLookup.Found found = (PaymentTransactionsLookup.Found) result;
            assertThat(found.transactions()).hasSize(count);
            assertThat(found.coverage()).isEqualTo(PaymentTransactionsLookup.Coverage.ALL);
            assertThat(server.lookupCalls()).isEqualTo(1);
        }

        @Test
        @DisplayName("거래 키로 조회하면 200 거래를 그대로 돌려준다")
        void findsTransactionByKey() {
            String key = server.addTransaction(ORDER_ID, AMOUNT, "SUCCESS");

            PaymentTransactionLookup result = gateway.findByTransactionKey(key);

            assertThat(result).isEqualTo(new PaymentTransactionLookup.Found(
                new PaymentTransaction(key, ORDER_ID, new Money(AMOUNT), PaymentTransactionStatus.SUCCESS)));
        }

        @Test
        @DisplayName("PG가 404로 답하면 거래 없음을 돌려준다")
        void returnsNotFoundFor404() {
            PaymentTransactionLookup result = gateway.findByTransactionKey("TR-UNKNOWN");

            assertThat(result).isInstanceOf(PaymentTransactionLookup.NotFound.class);
        }

        @Test
        @DisplayName("주문 식별자로 조회한 거래가 없으면 빈 목록을 돌려준다")
        void returnsEmptyList() {
            PaymentTransactionsLookup result = gateway.findByOrderId(ORDER_ID);

            assertThat(result).isEqualTo(new PaymentTransactionsLookup.Found(List.of(), PaymentTransactionsLookup.Coverage.ALL));
        }

        @Test
        @DisplayName("같은 주문 식별자의 거래가 여러 건이면 모두 돌려준다")
        void returnsAllTransactionsOfOrder() {
            String first = server.addTransaction(ORDER_ID, AMOUNT, "FAILED");
            String second = server.addTransaction(ORDER_ID, AMOUNT, "PENDING");
            server.addTransaction("LP-ORD-0000000099", AMOUNT, "SUCCESS");

            PaymentTransactionsLookup result = gateway.findByOrderId(ORDER_ID);

            assertThat(result).isEqualTo(new PaymentTransactionsLookup.Found(List.of(
                new PaymentTransaction(first, ORDER_ID, new Money(AMOUNT), PaymentTransactionStatus.FAILED),
                new PaymentTransaction(second, ORDER_ID, new Money(AMOUNT), PaymentTransactionStatus.PENDING)),
                PaymentTransactionsLookup.Coverage.ALL));
        }

        @Test
        @DisplayName("주문 조회한 거래 상태가 CANCELED면 조회 실패가 아니라 CANCELED 거래로 돌려준다")
        void reportsCanceledTransactionByOrder() {
            String key = server.addTransaction(ORDER_ID, AMOUNT, "CANCELED");

            PaymentTransactionsLookup result = gateway.verifyOrder(ORDER_ID, key);

            assertThat(result).isInstanceOf(PaymentTransactionsLookup.Found.class);
            assertThat(((PaymentTransactionsLookup.Found) result).transactions())
                .extracting(PaymentTransaction::transactionKey, transaction -> transaction.status().name())
                .containsExactly(tuple(key, "CANCELED"));
        }

        @Test
        @DisplayName("거래 키로 조회한 거래 상태가 CANCELED면 조회 실패가 아니라 CANCELED 거래로 돌려준다")
        void reportsCanceledTransactionByKey() {
            String key = server.addTransaction(ORDER_ID, AMOUNT, "CANCELED");

            PaymentTransactionLookup result = gateway.findByTransactionKey(key);

            assertThat(result).isInstanceOf(PaymentTransactionLookup.Found.class);
            assertThat(((PaymentTransactionLookup.Found) result).transaction().status().name()).isEqualTo("CANCELED");
        }

        @Test
        @DisplayName("포트가 모르는 상태 문자열이면 주문 조회 전체를 조회 실패로 돌려준다")
        void failsLookupForUnknownStatus() {
            server.addTransaction(ORDER_ID, AMOUNT, "REFUNDING");

            assertThat(gateway.verifyOrder(ORDER_ID, null)).isInstanceOf(PaymentTransactionsLookup.Failed.class);
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({"ERROR_500, ERROR_RESPONSE", "BROKEN_JSON, ERROR_RESPONSE", "DELAY, UNREACHABLE"})
        @DisplayName("주문 조회 실패는 PG가 오류·깨진 응답을 준 경우 ERROR_RESPONSE, 응답을 받지 못한 경우 UNREACHABLE로 나눈다")
        void classifiesLookupFailureKind(LookupBehavior behavior, PaymentTransactionsLookup.FailureKind expected) {
            server.addTransaction(ORDER_ID, AMOUNT, "SUCCESS");
            server.lookupBehavior(behavior);

            PaymentTransactionsLookup result = gateway.verifyOrder(ORDER_ID, null);

            assertThat(result).isInstanceOfSatisfying(PaymentTransactionsLookup.Failed.class,
                failed -> assertThat(failed.kind()).isEqualTo(expected));
        }

        static Stream<Arguments> failures() {
            return Stream.of(
                arguments("키 조회 500", LookupBehavior.ERROR_500, true),
                arguments("키 조회 read timeout", LookupBehavior.DELAY, true),
                arguments("키 조회 깨진 JSON", LookupBehavior.BROKEN_JSON, true),
                arguments("주문 조회 500", LookupBehavior.ERROR_500, false),
                arguments("주문 조회 read timeout", LookupBehavior.DELAY, false),
                arguments("주문 조회 깨진 JSON", LookupBehavior.BROKEN_JSON, false),
                arguments("주문 조회 다른 주문의 거래", LookupBehavior.WRONG_ORDER_ID, false)
            );
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("failures")
        @DisplayName("5xx·timeout·깨진 응답은 조회 실패이고 다른 주문 ID는 사실대로 보고한다")
        void returnsFailedForBrokenLookups(String name, LookupBehavior behavior, boolean byKey) {
            String key = server.addTransaction(ORDER_ID, AMOUNT, "SUCCESS");
            server.lookupBehavior(behavior);

            Object result = byKey ? gateway.findByTransactionKey(key) : gateway.findByOrderId(ORDER_ID);

            if (behavior == LookupBehavior.WRONG_ORDER_ID) {
                assertThat(result).isInstanceOf(PaymentTransactionsLookup.Found.class);
                PaymentTransactionsLookup.Found found = (PaymentTransactionsLookup.Found) result;
                assertThat(found.transactions().get(0).providerOrderId()).isEqualTo(ORDER_ID + "-X");
            } else {
                assertThat(result).isInstanceOfAny(PaymentTransactionLookup.Failed.class, PaymentTransactionsLookup.Failed.class);
            }
            assertThat(server.lookupCalls()).isEqualTo(1);
            PaymentTransactionsLookup verified = gateway.verifyOrder(ORDER_ID, key);
            if (behavior == LookupBehavior.WRONG_ORDER_ID) {
                assertThat(verified).isInstanceOf(PaymentTransactionsLookup.Found.class);
            } else {
                assertThat(verified).isInstanceOf(PaymentTransactionsLookup.Failed.class);
            }
        }
    }

    // 취소 API는 과정 PG 계약에 없는 가정이다(w10s5-3). 분류 원칙은 create와 같다.
    @Nested
    @DisplayName("취소")
    class Cancel {
        private static final String SECRET_BODY_MARKER = "PG-SECRET-BODY";
        private static final Set<CancelBehavior> CANCELED_SCENES = EnumSet.of(CancelBehavior.NORMAL);
        private static final Set<CancelBehavior> UNKNOWN_SCENES = EnumSet.of(
            CancelBehavior.OK_WITHOUT_CANCEL,
            CancelBehavior.OK_AMOUNT_MISMATCH,
            CancelBehavior.OK_ORDER_ID_MISMATCH,
            CancelBehavior.OK_KEY_MISMATCH,
            CancelBehavior.OK_BROKEN_JSON,
            CancelBehavior.ACCEPT_202,
            CancelBehavior.REJECT_OTHER_CODE,
            CancelBehavior.REJECT_WITHOUT_BODY,
            CancelBehavior.REJECT_AFTER_CANCEL,
            CancelBehavior.ERROR_500_AFTER_CANCEL,
            CancelBehavior.ERROR_500_WITHOUT_CANCEL,
            CancelBehavior.ERROR_500_WITH_SECRET_BODY,
            CancelBehavior.DELAY_AFTER_CANCEL,
            CancelBehavior.DROP_CONNECTION_AFTER_CANCEL);

        static Stream<CancelBehavior> unknownScenes() {
            return UNKNOWN_SCENES.stream();
        }

        @Test
        @DisplayName("취소는 POST /api/v1/payments/{transactionKey}/cancel로 X-USER-ID와 JSON 본문(orderId, 숫자 amount)만 보낸다")
        void sendsCancelRequestByContract() throws IOException {
            gateway.cancel(new PaymentCancelCommand(ORDER_ID, "TR/1 2", new Money(AMOUNT)));

            assertThat(server.requests()).hasSize(1);
            RecordedRequest request = server.requests().get(0);
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.rawPath()).isEqualTo("/api/v1/payments/TR%2F1%202/cancel");
            assertThat(request.header("X-USER-ID")).isEqualTo(USER_ID);
            assertThat(request.header("Content-Type")).startsWith("application/json");
            JsonNode body = objectMapper.readTree(request.body());
            assertThat(body.get("orderId").asText()).isEqualTo(ORDER_ID);
            assertThat(body.get("amount").isIntegralNumber()).isTrue();
            assertThat(body.get("amount").asLong()).isEqualTo(AMOUNT);
            assertThat(body.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("200 응답이 요청한 거래 키·주문·금액과 CANCELED 상태를 모두 담으면 취소됨으로 분류하고 그 거래를 돌려준다")
        void classifiesCanceled() {
            String key = server.addTransaction(ORDER_ID, AMOUNT, "SUCCESS");

            PaymentCancelResult result = gateway.cancel(cancelCommand(key));

            assertThat(result).isEqualTo(new PaymentCancelResult.Canceled(
                new PaymentTransaction(key, ORDER_ID, new Money(AMOUNT), PaymentTransactionStatus.CANCELED)));
        }

        @Test
        @DisplayName("이미 CANCELED인 거래를 취소하면 같은 200 응답이므로 취소됨으로 분류한다")
        void classifiesAlreadyCanceledAsCanceled() {
            String key = server.addTransaction(ORDER_ID, AMOUNT, "CANCELED");

            assertThat(gateway.cancel(cancelCommand(key))).isInstanceOf(PaymentCancelResult.Canceled.class);
        }

        @ParameterizedTest(name = "요청 주문={0}, 요청 금액={1}")
        @CsvSource({"LP-ORD-0000000099, 5000", "LP-ORD-0000000042, 5001"})
        @DisplayName("422 응답이 효과 없음 코드·canceled=false와 요청 값을 그대로 담으면 효과 없는 거절로 분류한다")
        void classifiesRejectedNoEffect(String requestOrderId, long requestAmount) {
            String key = server.addTransaction(ORDER_ID, AMOUNT, "SUCCESS");

            PaymentCancelResult result = gateway.cancel(new PaymentCancelCommand(requestOrderId, key, new Money(requestAmount)));

            assertThat(result).isEqualTo(new PaymentCancelResult.RejectedNoEffect(TestPgServer.CANCEL_REJECT_CODE));
            assertThat(server.transactions()).containsExactly(new StoredTransaction(key, ORDER_ID, AMOUNT, "SUCCESS"));
        }

        @Test
        @DisplayName("거래가 없다는 404 응답은 효과 없음 코드가 아니므로 결과 모름으로 분류한다")
        void notFoundIsUnknown() {
            assertThat(gateway.cancel(cancelCommand("TR-404"))).isInstanceOf(PaymentCancelResult.Unknown.class);
            assertThat(server.cancelCalls()).isEqualTo(1);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("unknownScenes")
        @DisplayName("취소·거절 계약을 벗어나거나 응답을 끝까지 받지 못하면 결과 모름으로 분류한다")
        void classifiesUnknown(CancelBehavior behavior) {
            String key = server.addTransaction(ORDER_ID, AMOUNT, "SUCCESS");
            server.cancelBehavior(behavior);

            assertThat(gateway.cancel(cancelCommand(key))).isInstanceOf(PaymentCancelResult.Unknown.class);
        }

        @Test
        @DisplayName("닫힌 포트로 연결이 거부되면 미발송으로 분류한다")
        void connectionRefusedIsNotSent() throws IOException {
            TestPgPaymentGateway closedPortGateway = gatewayFor(URI.create("http://127.0.0.1:" + closedPort()));

            assertThat(closedPortGateway.cancel(cancelCommand("TR-1"))).isInstanceOf(PaymentCancelResult.NotSent.class);
        }

        @Test
        @DisplayName("read timeout이면 테스트 PG에서 취소됐어도 결과 모름이며 다시 보내지 않는다")
        void readTimeoutIsUnknownEvenIfCanceled() {
            String key = server.addTransaction(ORDER_ID, AMOUNT, "SUCCESS");
            server.loseCancelResponse(ORDER_ID);

            PaymentCancelResult result = gateway.cancel(cancelCommand(key));

            assertThat(result).isInstanceOf(PaymentCancelResult.Unknown.class);
            assertThat(server.transactions()).containsExactly(new StoredTransaction(key, ORDER_ID, AMOUNT, "CANCELED"));
            assertThat(server.cancelCalls()).isEqualTo(1);
        }

        @Test
        @DisplayName("테스트 PG의 모든 취소 장면이 취소됨·결과 모름 중 정확히 한 칸에 속한다")
        void everySceneBelongsToExactlyOneResult() {
            Set<CancelBehavior> classified = EnumSet.noneOf(CancelBehavior.class);
            classified.addAll(CANCELED_SCENES);
            classified.addAll(UNKNOWN_SCENES);

            assertThat(classified).isEqualTo(EnumSet.allOf(CancelBehavior.class));
            assertThat(CANCELED_SCENES.size() + UNKNOWN_SCENES.size()).isEqualTo(CancelBehavior.values().length);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(CancelBehavior.class)
        @DisplayName("모든 장면에서 취소를 정확히 한 번만 보내고 create·조회로 세지 않는다")
        void sendsCancelExactlyOnce(CancelBehavior behavior) {
            String key = server.addTransaction(ORDER_ID, AMOUNT, "SUCCESS");
            server.cancelBehavior(behavior);

            gateway.cancel(cancelCommand(key));

            assertThat(server.cancelCalls()).isEqualTo(1);
            assertThat(server.cancelCalls(ORDER_ID)).isEqualTo(1);
            assertThat(server.createCalls()).isZero();
            assertThat(server.lookupCalls()).isZero();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(CancelBehavior.class)
        @DisplayName("모든 장면에서 응답 본문을 결과·로그에 남기지 않는다")
        void hidesResponseBody(CancelBehavior behavior) {
            String key = server.addTransaction(ORDER_ID, AMOUNT, "SUCCESS");
            server.cancelBehavior(behavior);

            PaymentCancelResult result = gateway.cancel(cancelCommand(key));

            assertThat(server.cancelCalls()).isEqualTo(1);
            assertThat(result.toString()).doesNotContain(SECRET_BODY_MARKER).doesNotContain("{");
            assertThat(loggedText()).doesNotContain(SECRET_BODY_MARKER).doesNotContain("{\"");
        }

        private PaymentCancelCommand cancelCommand(String key) {
            return new PaymentCancelCommand(ORDER_ID, key, new Money(AMOUNT));
        }
    }

    private TestPgPaymentGateway gatewayFor(URI baseUrl) {
        return new TestPgPaymentGateway(
            new TestPgProperties(baseUrl, USER_ID, CALLBACK_URL, Duration.ofMillis(300), READ_TIMEOUT), objectMapper);
    }

    private static PaymentCreateCommand command() {
        return new PaymentCreateCommand(ORDER_ID, "SAMSUNG", CARD_NO, new Money(AMOUNT));
    }

    private String loggedText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : logs.list) {
            text.append(event.getFormattedMessage()).append('\n');
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                text.append(proxy.getClassName()).append(": ").append(proxy.getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
