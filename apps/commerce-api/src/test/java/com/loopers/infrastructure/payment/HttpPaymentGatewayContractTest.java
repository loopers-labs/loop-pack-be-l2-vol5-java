package com.loopers.infrastructure.payment;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentCreateCommand;
import com.loopers.domain.payment.PaymentCreateResult;
import com.loopers.domain.payment.PaymentLookupResult;
import com.loopers.domain.payment.PaymentTransaction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 실제 HTTP adapter(HttpPaymentGateway)의 계약 테스트 (설계 1-6 결정 ㉑~㉖, 7-3). Spring 컨텍스트·DB 없이 돈다.
 * 테스트 안에서 띄운 로컬 PG 서버(TestPaymentPgServer)로 요청 모양(메서드·경로·헤더·JSON 타입·금액)과 응답 해석을 본다.
 * 지연은 서버 응답 보류(래치)로만 만들고 sleep 은 쓰지 않는다.
 */
class HttpPaymentGatewayContractTest {

    private static final String USER_ID = "135135";
    private static final String CALLBACK_URL = "http://127.0.0.1:8080/api/v1/payments/callback";
    private static final String ORDER_ID = "LP-ORD-0000000042";
    private static final String CARD_TYPE = "TEST";
    private static final String CARD_NO = "0000-0000-0000-0000";
    private static final long AMOUNT = 7000L;
    private static final String TRANSACTION_KEY = "TEST-TX-0042";
    private static final String REJECTED_CODE = "TEST_PG_REJECTED_BEFORE_CREATE";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofMillis(500);
    /**
     * 서킷 테스트 설정 (설계 5-7 결정 ㉜ "테스트 설정은 작게"). 최근 4회 중 4회가 모이면 판정, 실패율 50% 이상이면 OPEN, 10초 뒤 시험 2회.
     */
    private static final Duration WAIT_IN_OPEN = Duration.ofSeconds(10);
    private static final PaymentGatewayProperties.CircuitBreaker CIRCUIT =
        new PaymentGatewayProperties.CircuitBreaker("COUNT_BASED", 4, 4, 50f, WAIT_IN_OPEN, 2);

    private static TestPaymentPgServer server;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private HttpPaymentGateway gateway;

    @BeforeAll
    static void startServer() throws IOException {
        server = new TestPaymentPgServer();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @BeforeEach
    void setUp() {
        server.reset();
        gateway = gatewayFor(server.baseUrl(), true);
    }

    @AfterEach
    void tearDown() {
        server.releaseResponses();
    }

    @DisplayName("create 를 보내면, ")
    @Nested
    class Create {
        @DisplayName("POST /api/v1/payments 에 X-USER-ID 135135·JSON 본문(orderId·cardType·cardNo·정수 amount·설정값 callbackUrl)을 보낸다.")
        @Test
        void sendsCreateInContractShape() throws IOException {
            // arrange
            server.respondToCreate(202, transactionJson(TRANSACTION_KEY, ORDER_ID, AMOUNT, "PENDING"));

            // act
            gateway.create(command());

            // assert
            TestPaymentPgServer.ReceivedRequest request = onlyRequest();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/api/v1/payments");
            assertThat(request.header("X-USER-ID")).isEqualTo(USER_ID);
            assertThat(request.header("Content-Type")).startsWith("application/json");
            JsonNode body = objectMapper.readTree(request.body());
            assertThat(body.get("orderId").asText()).isEqualTo(ORDER_ID);
            assertThat(body.get("cardType").asText()).isEqualTo(CARD_TYPE);
            assertThat(body.get("cardNo").asText()).isEqualTo(CARD_NO);
            assertThat(body.get("amount").isIntegralNumber()).isTrue();
            assertThat(body.get("amount").asLong()).isEqualTo(AMOUNT);
            assertThat(body.get("callbackUrl").asText()).isEqualTo(CALLBACK_URL);
        }

        @DisplayName("202 + PENDING 이면, ACCEPTED 와 transactionKey 를 돌려준다.")
        @Test
        void acceptsPending_when202Pending() {
            // arrange
            server.respondToCreate(202, transactionJson(TRANSACTION_KEY, ORDER_ID, AMOUNT, "PENDING"));

            // act
            PaymentCreateResult result = gateway.create(command());

            // assert
            assertThat(result.outcome()).isEqualTo(PaymentCreateResult.Outcome.ACCEPTED);
            assertThat(result.transactionKey()).isEqualTo(TRANSACTION_KEY);
            assertThat(result.status()).isEqualTo(PaymentTransaction.Status.PENDING);
            assertThat(result.conflictReason()).isNull();
        }

        @DisplayName("422 + 정확한 code + transactionCreated boolean false + orderId·amount 일치면, REJECTED_NO_EFFECT 이고 key 는 없다.")
        @Test
        void rejectsNoEffect_when422MeetsAllFourConditions() {
            // arrange
            server.respondToCreate(422, rejectedJson(REJECTED_CODE, "false", ORDER_ID, AMOUNT));

            // act
            PaymentCreateResult result = gateway.create(command());

            // assert
            assertThat(result.outcome()).isEqualTo(PaymentCreateResult.Outcome.REJECTED_NO_EFFECT);
            assertThat(result.transactionKey()).isNull();
            assertThat(result.conflictReason()).isNull();
        }

        @DisplayName("본문 없는 422 는 증거가 아니라 UNKNOWN 이다.")
        @Test
        void unknown_when422HasNoBody() {
            server.respondToCreate(422, null);

            assertUnknownWithoutConflict(gateway.create(command()));
        }

        @DisplayName("422 인데 code 가 다르면 UNKNOWN 이다.")
        @Test
        void unknown_when422CodeDiffers() {
            server.respondToCreate(422, rejectedJson("SOMETHING_ELSE", "false", ORDER_ID, AMOUNT));

            assertUnknownWithoutConflict(gateway.create(command()));
        }

        @DisplayName("422 인데 transactionCreated 가 문자열 \"false\" 면 boolean 이 아니므로 UNKNOWN 이다.")
        @Test
        void unknown_when422TransactionCreatedIsStringFalse() {
            server.respondToCreate(422, rejectedJson(REJECTED_CODE, "\"false\"", ORDER_ID, AMOUNT));

            assertUnknownWithoutConflict(gateway.create(command()));
        }

        @DisplayName("422 인데 transactionCreated 가 true 면 UNKNOWN 이다.")
        @Test
        void unknown_when422TransactionCreatedIsTrue() {
            server.respondToCreate(422, rejectedJson(REJECTED_CODE, "true", ORDER_ID, AMOUNT));

            assertUnknownWithoutConflict(gateway.create(command()));
        }

        @DisplayName("422 + 정확한 code + false 인데 orderId 가 다르면, UNKNOWN + 충돌 사유 ORDER_MISMATCH 다 (결정 ⑱).")
        @Test
        void unknownWithOrderConflict_when422OrderIdDiffers() {
            server.respondToCreate(422, rejectedJson(REJECTED_CODE, "false", "LP-ORD-0000000099", AMOUNT));

            assertUnknownWithConflict(gateway.create(command()), PaymentAttempt.ConflictReason.ORDER_MISMATCH);
        }

        @DisplayName("422 + 정확한 code + false 인데 amount 가 다르면, UNKNOWN + 충돌 사유 AMOUNT_MISMATCH 다 (결정 ⑱).")
        @Test
        void unknownWithAmountConflict_when422AmountDiffers() {
            server.respondToCreate(422, rejectedJson(REJECTED_CODE, "false", ORDER_ID, AMOUNT + 1));

            assertUnknownWithConflict(gateway.create(command()), PaymentAttempt.ConflictReason.AMOUNT_MISMATCH);
        }

        @DisplayName("202 인데 본문 orderId 가 다르면, UNKNOWN + 충돌 사유 ORDER_MISMATCH 이고 transactionKey 는 돌려주지 않는다 (결정 ㉕).")
        @Test
        void unknownWithOrderConflict_when202OrderIdDiffers() {
            server.respondToCreate(202, transactionJson(TRANSACTION_KEY, "LP-ORD-0000000099", AMOUNT, "PENDING"));

            assertUnknownWithConflict(gateway.create(command()), PaymentAttempt.ConflictReason.ORDER_MISMATCH);
        }

        @DisplayName("202 인데 본문 amount 가 다르면, UNKNOWN + 충돌 사유 AMOUNT_MISMATCH 이고 transactionKey 는 돌려주지 않는다 (결정 ㉕).")
        @Test
        void unknownWithAmountConflict_when202AmountDiffers() {
            server.respondToCreate(202, transactionJson(TRANSACTION_KEY, ORDER_ID, AMOUNT + 1, "PENDING"));

            assertUnknownWithConflict(gateway.create(command()), PaymentAttempt.ConflictReason.AMOUNT_MISMATCH);
        }

        @DisplayName("그 밖의 4xx·5xx 는 UNKNOWN 이다.")
        @Test
        void unknown_whenOtherClientOrServerError() {
            server.respondToCreate(500, "{\"message\":\"boom\"}");
            assertUnknownWithoutConflict(gateway.create(command()));

            server.respondToCreate(400, null);
            assertUnknownWithoutConflict(gateway.create(command()));
        }

        @DisplayName("잘못된 JSON 이면 UNKNOWN 이다.")
        @Test
        void unknown_whenJsonIsMalformed() {
            server.respondToCreate(202, "{\"transactionKey\": ");

            assertUnknownWithoutConflict(gateway.create(command()));
        }

        @DisplayName("응답이 읽기 timeout 을 넘기면 UNKNOWN 이고, 서버가 받은 create 는 정확히 1회다 (숨은 재시도 없음).")
        @Test
        void unknown_andSendsOnlyOnce_whenResponseExceedsReadTimeout() {
            // arrange
            server.holdResponses();

            // act
            PaymentCreateResult result = gateway.create(command());

            // assert
            assertUnknownWithoutConflict(result);
            assertThat(server.createCount()).isEqualTo(1);
        }

        @DisplayName("연결이 거부되면 UNKNOWN 이다.")
        @Test
        void unknown_whenConnectionIsRefused() throws IOException {
            // arrange - 열었다 닫은 포트
            HttpPaymentGateway refused = gatewayFor("http://127.0.0.1:" + closedPort(), true);

            // act
            PaymentCreateResult result = refused.create(command());

            // assert
            assertUnknownWithoutConflict(result);
            assertThat(server.receivedRequests()).isEmpty();
        }
    }

    @DisplayName("transactionKey 로 단건 조회하면, ")
    @Nested
    class FindByTransactionKey {
        @DisplayName("GET /api/v1/payments/{transactionKey} 에 X-USER-ID 를 보내고, 200 이면 FOUND + 거래 1건이다.")
        @Test
        void sendsGetWithHeader_andMapsTransaction() {
            // arrange
            server.respondToTransaction(TRANSACTION_KEY, 200, transactionJson(TRANSACTION_KEY, ORDER_ID, AMOUNT, "SUCCESS"));

            // act
            PaymentLookupResult result = gateway.findByTransactionKey(TRANSACTION_KEY);

            // assert
            TestPaymentPgServer.ReceivedRequest request = onlyRequest();
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.path()).isEqualTo("/api/v1/payments/" + TRANSACTION_KEY);
            assertThat(request.header("X-USER-ID")).isEqualTo(USER_ID);
            assertThat(result.outcome()).isEqualTo(PaymentLookupResult.Outcome.FOUND);
            assertThat(result.transactions())
                .containsExactly(new PaymentTransaction(TRANSACTION_KEY, ORDER_ID, AMOUNT, PaymentTransaction.Status.SUCCESS));
        }

        @DisplayName("404 면 FOUND + 0건이다. UNAVAILABLE 이 아니다.")
        @Test
        void returnsEmptyFound_when404() {
            PaymentLookupResult result = gateway.findByTransactionKey("TEST-TX-NONE");

            assertThat(result.outcome()).isEqualTo(PaymentLookupResult.Outcome.FOUND);
            assertThat(result.transactions()).isEmpty();
        }

        @DisplayName("5xx 면 UNAVAILABLE 이다.")
        @Test
        void unavailable_when5xx() {
            server.respondToTransaction(TRANSACTION_KEY, 503, null);

            assertThat(gateway.findByTransactionKey(TRANSACTION_KEY).isUnavailable()).isTrue();
        }

        @DisplayName("잘못된 JSON 이면 UNAVAILABLE 이다.")
        @Test
        void unavailable_whenJsonIsMalformed() {
            server.respondToTransaction(TRANSACTION_KEY, 200, "{\"transactionKey\":");

            assertThat(gateway.findByTransactionKey(TRANSACTION_KEY).isUnavailable()).isTrue();
        }

        @DisplayName("응답이 읽기 timeout 을 넘기면 UNAVAILABLE 이다.")
        @Test
        void unavailable_whenResponseExceedsReadTimeout() {
            server.holdResponses();

            assertThat(gateway.findByTransactionKey(TRANSACTION_KEY).isUnavailable()).isTrue();
        }
    }

    @DisplayName("orderId 로 목록 조회하면, ")
    @Nested
    class FindAllByOrderId {
        @DisplayName("GET /api/v1/payments?orderId= 에 X-USER-ID 를 보내고, 특수문자가 든 orderId 도 인코딩되어 서버가 원래 값으로 받는다.")
        @Test
        void sendsGetWithEncodedQuery() {
            // arrange
            String specialOrderId = "LP ORD/00&42=x";

            // act
            PaymentLookupResult result = gateway.findAllByOrderId(specialOrderId);

            // assert
            TestPaymentPgServer.ReceivedRequest request = onlyRequest();
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.path()).isEqualTo("/api/v1/payments");
            assertThat(request.header("X-USER-ID")).isEqualTo(USER_ID);
            assertThat(request.query().get("orderId")).isEqualTo(specialOrderId);
            assertThat(request.rawQuery()).doesNotContain(" ").contains("%26");
            assertThat(result.outcome()).isEqualTo(PaymentLookupResult.Outcome.FOUND);
        }

        @DisplayName("200 목록이면 거래들을 돌려준다.")
        @Test
        void returnsTransactions_when200List() {
            // arrange
            server.respondToList(ORDER_ID, 200, "["
                + transactionJson("TEST-TX-0001", ORDER_ID, AMOUNT, "FAILED") + ","
                + transactionJson("TEST-TX-0002", ORDER_ID, AMOUNT, "PENDING") + "]");

            // act
            PaymentLookupResult result = gateway.findAllByOrderId(ORDER_ID);

            // assert
            assertThat(result.outcome()).isEqualTo(PaymentLookupResult.Outcome.FOUND);
            assertThat(result.transactions()).containsExactly(
                new PaymentTransaction("TEST-TX-0001", ORDER_ID, AMOUNT, PaymentTransaction.Status.FAILED),
                new PaymentTransaction("TEST-TX-0002", ORDER_ID, AMOUNT, PaymentTransaction.Status.PENDING));
        }

        @DisplayName("빈 목록이면 FOUND + 0건이다. UNAVAILABLE 이 아니다.")
        @Test
        void returnsEmptyFound_whenListIsEmpty() {
            server.respondToList(ORDER_ID, 200, "[]");

            PaymentLookupResult result = gateway.findAllByOrderId(ORDER_ID);

            assertThat(result.outcome()).isEqualTo(PaymentLookupResult.Outcome.FOUND);
            assertThat(result.transactions()).isEmpty();
        }

        @DisplayName("5xx 면 UNAVAILABLE 이다.")
        @Test
        void unavailable_when5xx() {
            server.respondToList(ORDER_ID, 503, null);

            assertThat(gateway.findAllByOrderId(ORDER_ID).isUnavailable()).isTrue();
        }

        @DisplayName("잘못된 JSON 이면 UNAVAILABLE 이다.")
        @Test
        void unavailable_whenJsonIsMalformed() {
            server.respondToList(ORDER_ID, 200, "[{\"transactionKey\":");

            assertThat(gateway.findAllByOrderId(ORDER_ID).isUnavailable()).isTrue();
        }

        @DisplayName("응답이 읽기 timeout 을 넘기면 UNAVAILABLE 이다.")
        @Test
        void unavailable_whenResponseExceedsReadTimeout() {
            server.holdResponses();

            assertThat(gateway.findAllByOrderId(ORDER_ID).isUnavailable()).isTrue();
        }
    }

    @DisplayName("조회 서킷브레이커는 (설계 5-7), ")
    @Nested
    class CircuitBreakerOnLookups {
        @DisplayName("최소 호출 수(4) 미만에서는 실패해도 요청이 서버에 가고, 4회 중 3회 실패(75%)가 되면 열려 다음 조회는 CIRCUIT_OPEN 이고 서버에 가지 않는다.")
        @Test
        void opens_whenFailureRateExceedsThresholdAfterMinimumCalls() {
            // arrange & act - 실패·실패·성공 (3회, 최소 호출 수 미달)
            failLookups();
            PaymentLookupResult first = gateway.findAllByOrderId(ORDER_ID);
            gateway.findAllByOrderId(ORDER_ID);
            serveEmptyList();
            PaymentLookupResult third = gateway.findAllByOrderId(ORDER_ID);

            // assert - 아직 전부 서버에 갔다
            assertThat(first.reason()).isEqualTo(PaymentLookupResult.UnavailableReason.CALL_FAILED);
            assertThat(third.outcome()).isEqualTo(PaymentLookupResult.Outcome.FOUND);
            assertThat(lookupsReceived()).isEqualTo(3);

            // act - 4회째 실패로 4회 중 3회 실패
            failLookups();
            gateway.findAllByOrderId(ORDER_ID);
            PaymentLookupResult blocked = gateway.findAllByOrderId(ORDER_ID);

            // assert
            assertThat(lookupsReceived()).isEqualTo(4);
            assertThat(blocked.isUnavailable()).isTrue();
            assertThat(blocked.reason()).isEqualTo(PaymentLookupResult.UnavailableReason.CIRCUIT_OPEN);
            assertThat(blocked.isCircuitOpen()).isTrue();
        }

        @DisplayName("OPEN 동안은 조회마다 CIRCUIT_OPEN 을 돌려주고 서버가 받은 조회 요청 수는 늘지 않는다.")
        @Test
        void returnsCircuitOpenWithoutCalling_whileOpen() {
            // arrange
            openCircuit();

            // act
            PaymentLookupResult a = gateway.findAllByOrderId(ORDER_ID);
            PaymentLookupResult b = gateway.findAllByOrderId(ORDER_ID);
            PaymentLookupResult c = gateway.findAllByOrderId(ORDER_ID);

            // assert
            assertThat(List.of(a, b, c)).allMatch(PaymentLookupResult::isCircuitOpen);
            assertThat(lookupsReceived()).isEqualTo(4);
        }

        @DisplayName("OPEN 대기 시간(10초)이 지나면 HALF_OPEN 이 되어 시험 호출이 서버에 간다.")
        @Test
        void allowsTrialCall_afterWaitDurationInOpenState() {
            // arrange
            openCircuit();
            serveEmptyList();

            // act
            clock.advance(WAIT_IN_OPEN.plusSeconds(1));
            PaymentLookupResult trial = gateway.findAllByOrderId(ORDER_ID);

            // assert
            assertThat(trial.outcome()).isEqualTo(PaymentLookupResult.Outcome.FOUND);
            assertThat(lookupsReceived()).isEqualTo(5);
        }

        @DisplayName("HALF_OPEN 의 시험 호출 2회가 성공하면 CLOSED 가 되어 그 다음 조회도 서버에 간다.")
        @Test
        void closes_whenTrialCallsSucceed() {
            // arrange
            openCircuit();
            serveEmptyList();
            clock.advance(WAIT_IN_OPEN.plusSeconds(1));

            // act
            gateway.findAllByOrderId(ORDER_ID);
            gateway.findAllByOrderId(ORDER_ID);
            PaymentLookupResult afterTrials = gateway.findAllByOrderId(ORDER_ID);

            // assert - HALF_OPEN 이었다면 세 번째는 거부됐을 호출이다
            assertThat(afterTrials.outcome()).isEqualTo(PaymentLookupResult.Outcome.FOUND);
            assertThat(lookupsReceived()).isEqualTo(7);
        }

        @DisplayName("HALF_OPEN 의 시험 호출이 실패하면 다시 OPEN 이 되어 다음 조회는 CIRCUIT_OPEN 이다.")
        @Test
        void reopens_whenTrialCallsFail() {
            // arrange - 서버는 계속 503
            openCircuit();
            clock.advance(WAIT_IN_OPEN.plusSeconds(1));

            // act
            PaymentLookupResult trial1 = gateway.findAllByOrderId(ORDER_ID);
            PaymentLookupResult trial2 = gateway.findAllByOrderId(ORDER_ID);
            PaymentLookupResult blocked = gateway.findAllByOrderId(ORDER_ID);

            // assert
            assertThat(trial1.reason()).isEqualTo(PaymentLookupResult.UnavailableReason.CALL_FAILED);
            assertThat(trial2.reason()).isEqualTo(PaymentLookupResult.UnavailableReason.CALL_FAILED);
            assertThat(blocked.isCircuitOpen()).isTrue();
            assertThat(lookupsReceived()).isEqualTo(6);
        }

        @DisplayName("빈 목록과 단건 404 는 실패로 세지 않아, 번갈아 8회 반복해도 열리지 않고 전부 서버에 간다.")
        @Test
        void doesNotCountEmptyListOrNotFoundAsFailure() {
            // act
            for (int i = 0; i < 4; i++) {
                assertThat(gateway.findAllByOrderId(ORDER_ID).outcome()).isEqualTo(PaymentLookupResult.Outcome.FOUND);
                assertThat(gateway.findByTransactionKey("TEST-TX-NONE").outcome()).isEqualTo(PaymentLookupResult.Outcome.FOUND);
            }

            // assert
            assertThat(lookupsReceived()).isEqualTo(8);
        }

        @DisplayName("읽기 timeout 도 실패로 세어, 4회 연속 timeout 뒤의 조회는 즉시 CIRCUIT_OPEN 이다.")
        @Test
        void countsReadTimeoutAsFailure() {
            // arrange
            server.holdResponses();

            // act
            for (int i = 0; i < 4; i++) {
                assertThat(gateway.findAllByOrderId(ORDER_ID).reason()).isEqualTo(PaymentLookupResult.UnavailableReason.CALL_FAILED);
            }
            PaymentLookupResult blocked = gateway.findAllByOrderId(ORDER_ID);

            // assert
            assertThat(blocked.isCircuitOpen()).isTrue();
        }

        @DisplayName("서킷이 OPEN 이어도 create 는 영향 없이 서버에 1회 나가고 응답대로 ACCEPTED 다.")
        @Test
        void doesNotAffectCreate_whileOpen() {
            // arrange
            openCircuit();
            server.respondToCreate(202, transactionJson(TRANSACTION_KEY, ORDER_ID, AMOUNT, "PENDING"));

            // act
            PaymentCreateResult result = gateway.create(command());

            // assert
            assertThat(result.outcome()).isEqualTo(PaymentCreateResult.Outcome.ACCEPTED);
            assertThat(server.createCount()).isEqualTo(1);
        }

        @DisplayName("목록 조회 실패로 열린 서킷은 단건 조회도 막는다 (같은 서킷 하나).")
        @Test
        void sharesOneCircuit_betweenListAndSingleLookup() {
            // arrange
            openCircuit();

            // act
            PaymentLookupResult single = gateway.findByTransactionKey(TRANSACTION_KEY);

            // assert
            assertThat(single.isCircuitOpen()).isTrue();
            assertThat(lookupsReceived()).isEqualTo(4);
        }

        /**
         * 503 을 4회 받아 서킷을 열고, 다음 조회가 차단되는 것까지 확인한다. 서버가 받은 조회는 4회다.
         */
        private void openCircuit() {
            failLookups();
            for (int i = 0; i < 4; i++) {
                gateway.findAllByOrderId(ORDER_ID);
            }
            assertThat(gateway.findAllByOrderId(ORDER_ID).isCircuitOpen()).as("4회 실패 뒤 서킷이 열려야 한다").isTrue();
            assertThat(lookupsReceived()).isEqualTo(4);
        }

        private void failLookups() {
            server.respondToList(ORDER_ID, 503, null);
        }

        private void serveEmptyList() {
            server.respondToList(ORDER_ID, 200, "[]");
        }
    }

    @DisplayName("공통으로, ")
    @Nested
    class Common {
        @DisplayName("create·조회를 거쳐도 어떤 로그에도 요청·응답 본문(카드 번호)이 남지 않는다.")
        @Test
        void doesNotLogRequestOrResponseBody() {
            // arrange - 루트 로거를 TRACE 로 올려 RestClient 계층의 로그까지 잡는다
            Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            Level before = root.getLevel();
            ListAppender<ILoggingEvent> logs = new ListAppender<>();
            logs.start();
            root.addAppender(logs);
            root.setLevel(Level.TRACE);
            server.respondToCreate(202, transactionJson(TRANSACTION_KEY, ORDER_ID, AMOUNT, "PENDING"));
            server.respondToTransaction(TRANSACTION_KEY, 200, transactionJson(TRANSACTION_KEY, ORDER_ID, AMOUNT, "PENDING"));

            try {
                // act
                gateway.create(command());
                gateway.findAllByOrderId(ORDER_ID);
                gateway.findByTransactionKey(TRANSACTION_KEY);

                // assert
                assertThat(logs.list).noneMatch(event -> mentionsCardNo(event));
            } finally {
                root.detachAppender(logs);
                root.setLevel(before);
            }
        }

        @DisplayName("enabled=false 면 세 호출 모두 IllegalStateException 을 던지고 서버에는 아무 요청도 가지 않는다.")
        @Test
        void throwsIllegalState_whenDisabled() {
            // arrange
            HttpPaymentGateway disabled = gatewayFor(server.baseUrl(), false);

            // act & assert
            assertThrows(IllegalStateException.class, () -> disabled.create(command()));
            assertThrows(IllegalStateException.class, () -> disabled.findAllByOrderId(ORDER_ID));
            assertThrows(IllegalStateException.class, () -> disabled.findByTransactionKey(TRANSACTION_KEY));
            assertThat(server.receivedRequests()).isEmpty();
        }

        private boolean mentionsCardNo(ILoggingEvent event) {
            if (event.getFormattedMessage() != null && event.getFormattedMessage().contains(CARD_NO)) {
                return true;
            }
            Object[] arguments = event.getArgumentArray();
            return arguments != null && Arrays.stream(arguments).anyMatch(arg -> String.valueOf(arg).contains(CARD_NO));
        }
    }

    private HttpPaymentGateway gatewayFor(String baseUrl, boolean enabled) {
        return new HttpPaymentGateway(
            new PaymentGatewayProperties(enabled, baseUrl, CALLBACK_URL, USER_ID, CONNECT_TIMEOUT, READ_TIMEOUT, CIRCUIT),
            objectMapper,
            clock);
    }

    private static long lookupsReceived() {
        return server.receivedRequests().stream().filter(request -> "GET".equals(request.method())).count();
    }

    /**
     * 앞당길 수 있는 시계. 서킷의 OPEN 대기 판정이 이 시계를 읽으므로 sleep 없이 시간을 진행한다.
     */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        private void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private static PaymentCreateCommand command() {
        return new PaymentCreateCommand(ORDER_ID, CARD_TYPE, CARD_NO, AMOUNT);
    }

    private static TestPaymentPgServer.ReceivedRequest onlyRequest() {
        List<TestPaymentPgServer.ReceivedRequest> requests = server.receivedRequests();
        assertThat(requests).hasSize(1);
        return requests.get(0);
    }

    private static void assertUnknownWithoutConflict(PaymentCreateResult result) {
        assertThat(result.outcome()).isEqualTo(PaymentCreateResult.Outcome.UNKNOWN);
        assertThat(result.transactionKey()).isNull();
        assertThat(result.conflictReason()).isNull();
    }

    private static void assertUnknownWithConflict(PaymentCreateResult result, PaymentAttempt.ConflictReason reason) {
        assertThat(result.outcome()).isEqualTo(PaymentCreateResult.Outcome.UNKNOWN);
        assertThat(result.transactionKey()).isNull();
        assertThat(result.conflictReason()).isEqualTo(reason);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String transactionJson(String transactionKey, String orderId, long amount, String status) {
        return "{\"transactionKey\":\"" + transactionKey + "\",\"orderId\":\"" + orderId
            + "\",\"amount\":" + amount + ",\"status\":\"" + status + "\"}";
    }

    /**
     * transactionCreated 는 JSON 조각 그대로 넣는다 (false, true, "false").
     */
    private static String rejectedJson(String code, String transactionCreated, String orderId, long amount) {
        return "{\"code\":\"" + code + "\",\"orderId\":\"" + orderId + "\",\"amount\":" + amount
            + ",\"transactionCreated\":" + transactionCreated + "}";
    }
}