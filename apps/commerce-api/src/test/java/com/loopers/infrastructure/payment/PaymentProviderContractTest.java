package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.domain.payment.PgCreateResult;
import com.loopers.domain.payment.PgLookupResult;
import com.loopers.domain.payment.PgPaymentRequest;
import com.loopers.domain.payment.PgTransaction;
import com.loopers.domain.payment.PgTransactionStatus;
import com.loopers.domain.payment.UnavailableReason;
import com.loopers.domain.payment.UnknownReason;
import com.loopers.support.pg.TestPgContract;
import com.loopers.support.pg.TestPgLookupFailure;
import com.loopers.support.pg.TestPgRequest;
import com.loopers.support.pg.TestPgServer;
import com.loopers.support.pg.TestPgStatus;
import com.loopers.support.pg.TestPgTransaction;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 adapter → 실제 HTTP → 테스트 PG fixture로 계약(docs/week5/pg-contract.md 3·4절, 9절 표)을 확인한다.
 * Spring 컨텍스트와 DB 없이 adapter를 직접 만든다. 응답 본문 해석의 세부 규칙은 {@link TestPgResponsesTest}가 맡는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class PaymentProviderContractTest {

    private static final String ORDER_ID = "LP-ORD-0000000042";
    private static final long AMOUNT = 7000L;
    private static final String CARD_NO = "0000-0000-0000-0000";
    private static final String CALLBACK_URL = "http://127.0.0.1:8080/api/v1/payments/callback";
    private static final Duration CREATE_READ_TIMEOUT = Duration.ofMillis(500);
    // 재시도 대기 합(100ms + 200ms)보다 길어야 한다. 짧으면 같은 조회의 재시도가 OPEN 대기를 넘겨 HALF_OPEN 탐색으로 나간다
    private static final Duration OPEN_STATE_WAIT = Duration.ofMillis(500);
    private static final PgPaymentRequest REQUEST = new PgPaymentRequest(ORDER_ID, "TEST", CARD_NO, AMOUNT);

    private static TestPgServer pg;
    private CircuitBreakerRegistry circuitBreakers;
    private TestPgPaymentGateway gateway;

    @BeforeAll
    static void startPg() {
        pg = TestPgServer.start();
    }

    @AfterAll
    static void stopPg() {
        pg.close();
    }

    // 테스트마다 새 registry를 써서 앞 테스트의 실패 표본이 회로를 열지 않게 한다
    @BeforeEach
    void setUp() {
        circuitBreakers = CircuitBreakerRegistry.ofDefaults();
        gateway = new TestPgPaymentGateway(properties(pg.baseUrl(), TestPgContract.PROVIDER_ACCOUNT), circuitBreakers);
    }

    @AfterEach
    void tearDown() {
        assertThat(pg.handlerErrors()).isEmpty();
        pg.reset();
    }

    @DisplayName("거래 생성을 요청할 때, ")
    @Nested
    class Create {

        @DisplayName("POST /api/v1/payments에 제공자 계정 헤더와 계약의 JSON 타입으로 보내고, callbackUrl은 설정값이다. (#1)")
        @Test
        void sendsContractRequest() {
            // act
            gateway.requestPayment(REQUEST);

            // assert
            TestPgRequest request = pg.requests().get(0);
            JsonNode body = request.json();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo(TestPgContract.PAYMENTS_PATH);
            assertThat(request.header(TestPgContract.ACCOUNT_HEADER)).isEqualTo(TestPgContract.PROVIDER_ACCOUNT);
            assertThat(request.header("Content-Type")).startsWith("application/json");
            assertThat(request.header("Accept")).isEqualTo("application/json");
            assertThat(body.get("orderId").isTextual()).isTrue();
            assertThat(body.get("orderId").asText()).isEqualTo(ORDER_ID);
            assertThat(body.get("cardType").isTextual()).isTrue();
            assertThat(body.get("cardNo").isTextual()).isTrue();
            assertThat(body.get("amount").isIntegralNumber()).isTrue();
            assertThat(body.get("amount").asLong()).isEqualTo(AMOUNT);
            assertThat(body.get("callbackUrl").asText()).isEqualTo(CALLBACK_URL);
        }

        @DisplayName("202를 받으면 PG가 만든 거래 키로 접수 결과를 돌려준다. 결제 성공이 아니다. (#2)")
        @Test
        void returnsAccepted_on202() {
            // act
            PgCreateResult result = gateway.requestPayment(REQUEST);

            // assert
            TestPgTransaction created = pg.transactionsOf(ORDER_ID).get(0);
            assertThat(result).isEqualTo(new PgCreateResult.Accepted(created.transactionKey()));
            assertThat(created.status()).isEqualTo(TestPgStatus.PENDING);
            assertThat(pg.createCount()).isEqualTo(1);
        }

        @DisplayName("계약의 정확한 422를 받으면 효과 0건 거절이고, PG에는 거래가 없다. (#3)")
        @Test
        void returnsRejectedNoEffect_onExact422() {
            // arrange
            pg.rejectNextCreate();

            // act
            PgCreateResult result = gateway.requestPayment(REQUEST);

            // assert
            assertThat(result).isEqualTo(new PgCreateResult.RejectedNoEffect());
            assertThat(pg.transactionCount()).isZero();
        }

        @DisplayName("422의 변형은 모두 결과 미확인이고 효과 0건 거절이 아니다. (#4)")
        @Test
        void treats422VariantsAsUnknown() {
            // arrange
            String exact = "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"" + ORDER_ID
                + "\",\"amount\":7000,\"transactionCreated\":false}";
            List<String> variants = List.of(
                "",
                exact.replace("TEST_PG_REJECTED_BEFORE_CREATE", "OTHER_CODE"),
                exact.replace("false", "true"),
                exact.replace("false", "\"false\""),
                exact.replace(",\"transactionCreated\":false", ""),
                exact.replace("7000", "\"7000\""),
                exact.replace(ORDER_ID, "LP-ORD-0000000099"),
                exact.replace("7000", "9000")
            );

            // act & assert
            for (String body : variants) {
                pg.respondNextCreate(422, body, false);
                assertThat(gateway.requestPayment(REQUEST)).as(body).isInstanceOf(PgCreateResult.Unknown.class);
            }
            assertThat(pg.createCount()).isEqualTo(variants.size());
        }

        @DisplayName("400·500, 깨진 JSON이나 다른 금액·문자열 금액의 202는 모두 결과 미확인이다. (#5)")
        @Test
        void treatsOtherResponsesAsUnknown() {
            // arrange
            String accepted = "{\"transactionKey\":\"TEST-TX-9999\",\"orderId\":\"" + ORDER_ID + "\",\"amount\":7000,\"status\":\"PENDING\"}";
            List<Object[]> responses = List.of(
                new Object[] {400, "{\"code\":\"" + TestPgContract.INVALID_REQUEST + "\"}"},
                new Object[] {500, "{\"code\":\"BOOM\"}"},
                new Object[] {202, "{"},
                new Object[] {202, accepted.replace("7000", "9000")},
                new Object[] {202, accepted.replace("7000", "\"7000\"")}
            );

            // act & assert
            for (Object[] response : responses) {
                pg.respondNextCreate((int) response[0], (String) response[1], false);
                assertThat(gateway.requestPayment(REQUEST)).as(response[0] + " " + response[1])
                    .isInstanceOf(PgCreateResult.Unknown.class);
            }
        }

        @DisplayName("500을 받았지만 PG에는 거래가 생긴 경우도 결과 미확인이다. (#6)")
        @Test
        void treats500WithCreatedTransactionAsUnknown() {
            // arrange
            pg.respondNextCreate(500, "{\"code\":\"BOOM\"}", true);

            // act
            PgCreateResult result = gateway.requestPayment(REQUEST);

            // assert
            assertThat(result).isEqualTo(new PgCreateResult.Unknown(UnknownReason.UNEXPECTED_STATUS));
            assertThat(pg.transactionsOf(ORDER_ID)).hasSize(1);
        }

        @DisplayName("거래가 생긴 뒤 응답이 늦어 timeout되면 TIMEOUT 결과 미확인이고, create를 다시 보내지 않는다. (#7)")
        @Test
        void returnsUnknownTimeout_withoutResending_whenResponseIsHeld() {
            // arrange
            pg.holdNextCreateResponse();

            // act
            long startedAt = System.nanoTime();
            PgCreateResult result = gateway.requestPayment(REQUEST);
            Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

            // assert
            assertThat(result).isEqualTo(new PgCreateResult.Unknown(UnknownReason.TIMEOUT));
            assertThat(pg.transactionsOf(ORDER_ID)).hasSize(1);
            assertThat(pg.createCount()).isEqualTo(1);
            assertThat(waited).isGreaterThanOrEqualTo(CREATE_READ_TIMEOUT).isLessThan(TestPgServer.MAX_HOLD);
        }

        @DisplayName("거래가 생긴 뒤 응답 없이 연결이 끊기면 CONNECTION_LOST 결과 미확인이고, create를 다시 보내지 않는다. (#8)")
        @Test
        void returnsUnknownConnectionLost_withoutResending_whenResponseIsDropped() {
            // arrange
            pg.dropNextCreateResponse();

            // act
            PgCreateResult result = gateway.requestPayment(REQUEST);

            // assert
            assertThat(result).isEqualTo(new PgCreateResult.Unknown(UnknownReason.CONNECTION_LOST));
            assertThat(pg.transactionsOf(ORDER_ID)).hasSize(1);
            assertThat(pg.createCount()).isEqualTo(1);
        }

        @DisplayName("PG에 연결하지 못해도 실패로 단정하지 않고 CONNECTION_LOST 결과 미확인으로 둔다. (명세서 11절 열린 결정 1)")
        @Test
        void returnsUnknownConnectionLost_whenPgIsDown() {
            // arrange
            TestPgServer stopped = TestPgServer.start();
            String deadUrl = stopped.baseUrl();
            stopped.close();
            TestPgPaymentGateway toDeadPg = new TestPgPaymentGateway(
                properties(deadUrl, TestPgContract.PROVIDER_ACCOUNT), CircuitBreakerRegistry.ofDefaults());

            // act
            PgCreateResult result = toDeadPg.requestPayment(REQUEST);

            // assert
            assertThat(result).isEqualTo(new PgCreateResult.Unknown(UnknownReason.CONNECTION_LOST));
        }
    }

    @DisplayName("거래를 조회할 때, ")
    @Nested
    class Lookup {

        @DisplayName("단건 조회는 GET /api/v1/payments/{key}로 묻고, 200은 거래 1건, 404는 0건이다. (#9)")
        @Test
        void findsByKey() {
            // arrange
            TestPgTransaction seeded = pg.seedTransaction(TestPgContract.PROVIDER_ACCOUNT, ORDER_ID, AMOUNT, TestPgStatus.SUCCESS);

            // act
            PgLookupResult found = gateway.findTransaction(seeded.transactionKey());
            PgLookupResult missing = gateway.findTransaction("TEST-TX-NONE");

            // assert
            TestPgRequest request = pg.requests().get(0);
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.path()).isEqualTo(TestPgContract.PAYMENTS_PATH + "/" + seeded.transactionKey());
            assertThat(request.header(TestPgContract.ACCOUNT_HEADER)).isEqualTo(TestPgContract.PROVIDER_ACCOUNT);
            assertThat(found).isEqualTo(new PgLookupResult.Transactions(List.of(
                new PgTransaction(seeded.transactionKey(), ORDER_ID, AMOUNT, PgTransactionStatus.SUCCESS))));
            assertThat(missing).isEqualTo(new PgLookupResult.Transactions(List.of()));
        }

        @DisplayName("주문별 조회는 0건·1건·여러 건을 그대로 돌려주고, query 값을 인코딩해 보낸다. (#10)")
        @Test
        void listsByOrder_andEncodesQuery() {
            // arrange
            TestPgTransaction first = pg.seedTransaction(TestPgContract.PROVIDER_ACCOUNT, ORDER_ID, AMOUNT, TestPgStatus.FAILED);
            TestPgTransaction second = pg.seedTransaction(TestPgContract.PROVIDER_ACCOUNT, ORDER_ID, AMOUNT, TestPgStatus.PENDING);
            TestPgTransaction special = pg.seedTransaction(TestPgContract.PROVIDER_ACCOUNT, "A B&C+D", AMOUNT, TestPgStatus.PENDING);

            // act
            PgLookupResult none = gateway.findTransactionsByOrder("LP-ORD-0000000099");
            PgLookupResult two = gateway.findTransactionsByOrder(ORDER_ID);
            PgLookupResult encoded = gateway.findTransactionsByOrder("A B&C+D");

            // assert
            assertThat(none).isEqualTo(new PgLookupResult.Transactions(List.of()));
            assertThat(keysOf(two)).containsExactly(first.transactionKey(), second.transactionKey());
            assertThat(keysOf(encoded)).containsExactly(special.transactionKey());
            assertThat(pg.requests().get(2).rawQuery()).isEqualTo("orderId=A%20B%26C%2BD");
        }

        @DisplayName("다른 제공자 계정의 거래는 단건·주문별 조회 모두에서 보이지 않는다. (#11)")
        @Test
        void doesNotSeeOtherAccountsTransactions() {
            // arrange
            TestPgTransaction others = pg.seedTransaction("999999", ORDER_ID, AMOUNT, TestPgStatus.SUCCESS);

            // act & assert
            assertThat(gateway.findTransaction(others.transactionKey())).isEqualTo(new PgLookupResult.Transactions(List.of()));
            assertThat(gateway.findTransactionsByOrder(ORDER_ID)).isEqualTo(new PgLookupResult.Transactions(List.of()));
        }
    }

    @DisplayName("조회 재시도와 회로 차단기를 거칠 때, ")
    @Nested
    class LookupResilience {

        @DisplayName("다시 물으면 달라질 수 있는 실패(timeout·500·깨진 JSON)는 HTTP 3번 뒤 확인 불가다. (#12)")
        @Test
        void retriesRetryableFailuresUpToThreeTimes() {
            // arrange
            TestPgTransaction seeded = seed();
            Map<TestPgLookupFailure, UnavailableReason> failures = Map.of(
                TestPgLookupFailure.HOLD, UnavailableReason.TIMEOUT,
                TestPgLookupFailure.SERVER_ERROR, UnavailableReason.SERVER_ERROR,
                TestPgLookupFailure.INVALID_JSON, UnavailableReason.INVALID_BODY
            );

            // act & assert
            failures.forEach((failure, reason) -> {
                TestPgPaymentGateway fresh = freshGateway();
                int before = pg.lookupByKeyCount();
                pg.failLookups(failure);
                assertThat(fresh.findTransaction(seeded.transactionKey())).as(failure.name())
                    .isEqualTo(new PgLookupResult.Unavailable(reason));
                assertThat(pg.lookupByKeyCount() - before).as(failure.name()).isEqualTo(3);
                pg.healLookups();
                pg.releaseHeld();
            });
        }

        @DisplayName("응답 없는 연결 끊김은 시도 3번이지만, JDK가 GET을 한 번씩 다시 보내 PG는 6번 받는다. (#12)")
        @Test
        void countsJdkGetResend_whenConnectionDrops() {
            // arrange
            TestPgTransaction seeded = seed();
            pg.failLookups(TestPgLookupFailure.DROP);

            // act
            PgLookupResult result = gateway.findTransaction(seeded.transactionKey());

            // assert
            assertThat(result).isEqualTo(new PgLookupResult.Unavailable(UnavailableReason.CONNECTION_LOST));
            assertThat(pg.lookupByKeyCount()).isEqualTo(6);
        }

        @DisplayName("404는 정상 응답(0건)이고, 404 외의 4xx는 다시 묻지 않는다. 둘 다 HTTP 1번이다. (#12)")
        @Test
        void doesNotRetryNotFoundOrClientError() {
            // arrange: 제공자 계정이 비어 있으면 PG가 400으로 응답한다
            TestPgPaymentGateway blankAccount = new TestPgPaymentGateway(properties(pg.baseUrl(), ""), CircuitBreakerRegistry.ofDefaults());

            // act
            PgLookupResult notFound = gateway.findTransaction("TEST-TX-NONE");
            int afterNotFound = pg.lookupByKeyCount();
            PgLookupResult clientError = blankAccount.findTransaction("TEST-TX-NONE");

            // assert
            assertThat(notFound).isEqualTo(new PgLookupResult.Transactions(List.of()));
            assertThat(afterNotFound).isEqualTo(1);
            assertThat(clientError).isEqualTo(new PgLookupResult.Unavailable(UnavailableReason.UNEXPECTED_STATUS));
            assertThat(pg.lookupByKeyCount()).isEqualTo(2);
        }

        @DisplayName("한두 번 실패한 뒤 재시도 안에서 성공하면 거래를 돌려주고, HTTP 수는 실패 수 + 1이다. (#13)")
        @Test
        void recoversWithinRetries() {
            // arrange
            TestPgTransaction seeded = seed();
            pg.failNextLookups(2, TestPgLookupFailure.SERVER_ERROR);

            // act
            PgLookupResult result = gateway.findTransaction(seeded.transactionKey());

            // assert
            assertThat(keysOf(result)).containsExactly(seeded.transactionKey());
            assertThat(pg.lookupByKeyCount()).isEqualTo(3);
        }

        @DisplayName("500이 계속되면 1회차 조회는 HTTP 3번, 2회차는 2번 만에 회로가 열리고, 3회차는 PG에 묻지 않는다. (#14)")
        @Test
        void opensCircuitAfterMinimumFailedSamples() {
            // arrange
            TestPgTransaction seeded = seed();
            pg.failLookups(TestPgLookupFailure.SERVER_ERROR);

            // act & assert
            assertThat(gateway.findTransaction(seeded.transactionKey())).isEqualTo(new PgLookupResult.Unavailable(UnavailableReason.SERVER_ERROR));
            assertThat(pg.lookupByKeyCount()).isEqualTo(3);
            assertThat(lookupCircuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);

            assertThat(gateway.findTransaction(seeded.transactionKey())).isEqualTo(new PgLookupResult.Unavailable(UnavailableReason.CIRCUIT_OPEN));
            assertThat(pg.lookupByKeyCount()).isEqualTo(5);
            assertThat(lookupCircuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);

            assertThat(gateway.findTransaction(seeded.transactionKey())).isEqualTo(new PgLookupResult.Unavailable(UnavailableReason.CIRCUIT_OPEN));
            assertThat(pg.lookupByKeyCount()).isEqualTo(5);
        }

        @DisplayName("열린 뒤 대기 시간이 지나고 PG가 회복되면, 탐색 조회 2건이 모두 성공해 회로가 닫힌다. (#15)")
        @Test
        void closesCircuit_whenProbesSucceed() throws InterruptedException {
            // arrange
            TestPgTransaction seeded = openCircuit();
            pg.healLookups();
            waitPastOpenState();

            // act
            PgLookupResult firstProbe = gateway.findTransaction(seeded.transactionKey());
            CircuitBreaker.State afterFirst = lookupCircuit().getState();
            PgLookupResult secondProbe = gateway.findTransaction(seeded.transactionKey());

            // assert
            assertThat(keysOf(firstProbe)).containsExactly(seeded.transactionKey());
            assertThat(afterFirst).isEqualTo(CircuitBreaker.State.HALF_OPEN);
            assertThat(keysOf(secondProbe)).containsExactly(seeded.transactionKey());
            assertThat(lookupCircuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @DisplayName("대기 뒤에도 실패가 이어지면 다시 열리고, 탐색 2건 중 1건만 실패해도 실패율 50%라 다시 열린다. (#16)")
        @Test
        void reopensCircuit_whenAnyProbeFails() throws InterruptedException {
            // arrange
            TestPgTransaction seeded = openCircuit();
            waitPastOpenState();

            // act & assert: 계속 실패
            assertThat(gateway.findTransaction(seeded.transactionKey())).isEqualTo(new PgLookupResult.Unavailable(UnavailableReason.CIRCUIT_OPEN));
            assertThat(lookupCircuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);

            // act & assert: 첫 탐색은 실패, 재시도한 둘째 탐색은 성공 → 조회는 성공했지만 회로는 다시 열린다
            pg.healLookups();
            pg.failNextLookups(1, TestPgLookupFailure.SERVER_ERROR);
            waitPastOpenState();
            assertThat(keysOf(gateway.findTransaction(seeded.transactionKey()))).containsExactly(seeded.transactionKey());
            assertThat(lookupCircuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        @DisplayName("조회 회로가 열려 있어도 create는 회로를 쓰지 않아 PG에 도달한다. (#17)")
        @Test
        void createIgnoresLookupCircuit() {
            // arrange
            openCircuit();

            // act
            PgCreateResult result = gateway.requestPayment(REQUEST);

            // assert
            assertThat(result).isInstanceOf(PgCreateResult.Accepted.class);
            assertThat(pg.createCount()).isEqualTo(1);
        }

        private TestPgTransaction openCircuit() {
            TestPgTransaction seeded = seed();
            pg.failLookups(TestPgLookupFailure.SERVER_ERROR);
            gateway.findTransaction(seeded.transactionKey());
            gateway.findTransaction(seeded.transactionKey());
            assertThat(lookupCircuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);
            return seeded;
        }

        // OPEN → HALF_OPEN 자동 전환을 꺼 두었으므로, 대기 시간이 지난 뒤 들어온 호출에서 전환된다
        private void waitPastOpenState() throws InterruptedException {
            Thread.sleep(OPEN_STATE_WAIT.plusMillis(100).toMillis());
        }

        private CircuitBreaker lookupCircuit() {
            return circuitBreakers.circuitBreaker(TestPgPaymentGateway.LOOKUP_CIRCUIT_BREAKER);
        }

        private TestPgTransaction seed() {
            return pg.seedTransaction(TestPgContract.PROVIDER_ACCOUNT, ORDER_ID, AMOUNT, TestPgStatus.PENDING);
        }

        private TestPgPaymentGateway freshGateway() {
            return new TestPgPaymentGateway(properties(pg.baseUrl(), TestPgContract.PROVIDER_ACCOUNT), CircuitBreakerRegistry.ofDefaults());
        }
    }

    @DisplayName("생성·거절·timeout·연결 끊김을 거치는 동안 로그에 카드번호가 남지 않는다. (#18)")
    @Test
    void neverLogsCardNumber(CapturedOutput output) {
        // act
        gateway.requestPayment(REQUEST);
        pg.rejectNextCreate();
        gateway.requestPayment(REQUEST);
        pg.respondNextCreate(500, "{\"code\":\"BOOM\"}", true);
        gateway.requestPayment(REQUEST);
        pg.holdNextCreateResponse();
        gateway.requestPayment(REQUEST);
        pg.dropNextCreateResponse();
        gateway.requestPayment(REQUEST);

        // assert
        assertThat(output.getAll()).contains("PG 거래 생성 결과를 확인하지 못했다").doesNotContain(CARD_NO);
        assertThat(REQUEST.toString()).doesNotContain(CARD_NO);
    }

    private static List<String> keysOf(PgLookupResult result) {
        assertThat(result).isInstanceOf(PgLookupResult.Transactions.class);
        return ((PgLookupResult.Transactions) result).transactions().stream().map(PgTransaction::transactionKey).toList();
    }

    // 명세서 5절의 테스트 값: create 500ms, 조회 300ms, 재시도 3회(100ms → 200ms), 회로 창 10·최소 5·50%·OPEN 500ms·탐색 2
    private static PgProperties properties(String baseUrl, String providerAccount) {
        return new PgProperties(
            baseUrl, CALLBACK_URL, providerAccount,
            Duration.ofMillis(500), CREATE_READ_TIMEOUT, Duration.ofMillis(300),
            new PgProperties.LookupRetry(3, Duration.ofMillis(100), 2.0),
            new PgProperties.LookupCircuitBreaker(10, 5, 50f, OPEN_STATE_WAIT, 2)
        );
    }
}
