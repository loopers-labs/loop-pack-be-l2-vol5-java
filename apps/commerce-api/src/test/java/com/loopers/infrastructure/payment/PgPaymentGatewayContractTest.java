package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.loopers.application.payment.CreatePaymentRequest;
import com.loopers.application.payment.CreateResult;
import com.loopers.application.payment.LookupRetry;
import com.loopers.application.payment.PaymentGatewayUnavailableException;
import com.loopers.domain.payment.PaymentObservation;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * adapter가 진짜 HTTP로 테스트 PG 계약을 지키는지 확인한다(docs/week5/pg-contract.md, D-025·D-028·D-030, INV-004·005).
 * timeout은 테스트 시간을 줄이려고 운영값(create 2초, 조회 1초)보다 짧게 둔다 — 확인하는 건 "설정한 timeout이 실제로 걸린다"이다.
 */
class PgPaymentGatewayContractTest {

    private static final String PROVIDER_ORDER_ID = "LP-ORD-0000000042";
    private static final String TX_KEY = "TEST-TX-0042";
    private static final long AMOUNT = 7000L;
    private static final String CALLBACK_URL = "http://127.0.0.1:8080/api/v1/payments/callback";
    private static final Duration CREATE_READ_TIMEOUT = Duration.ofMillis(500);
    private static final Duration LOOKUP_READ_TIMEOUT = Duration.ofMillis(300);

    @RegisterExtension
    static WireMockExtension pg = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    private PgPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = gatewayFor(pg.baseUrl());
    }

    private static PgPaymentGateway gatewayFor(String baseUrl) {
        PgClientSettings settings = new PgClientSettings(baseUrl, "135135", CALLBACK_URL,
            Duration.ofMillis(300), CREATE_READ_TIMEOUT, Duration.ofMillis(300), LOOKUP_READ_TIMEOUT, Duration.ofMillis(10));
        return new PgPaymentGateway(settings, new ObjectMapper(), CircuitBreaker.ofDefaults("contract-test"));
    }

    private static CreatePaymentRequest request() {
        return new CreatePaymentRequest(PROVIDER_ORDER_ID, "TEST", "0000-0000-0000-0000", AMOUNT);
    }

    /**
     * 응답 지연(withFixedDelay)이 걸린 요청은 지연이 끝나야 WireMock 요청 기록에 남는다. 그래서 클라이언트가 timeout으로
     * 포기한 직후에 세면 아직 안 보인다 — 기대 횟수가 보일 때까지 기다리고, 지연 시간만큼 더 지켜본 뒤 정확한 횟수를 확인한다
     * (늦게 기록되는 숨은 재시도까지 잡기 위해서다).
     */
    private static void verifyExactlyAfterDelays(int expected, RequestPatternBuilder pattern, Duration responseDelay)
        throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (pg.findAll(pattern).size() < expected && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        TimeUnit.MILLISECONDS.sleep(responseDelay.toMillis());
        pg.verify(exactly(expected), pattern);
    }

    private static String transactionJson(String status) {
        return "{\"transactionKey\":\"" + TX_KEY + "\",\"orderId\":\"" + PROVIDER_ORDER_ID
            + "\",\"amount\":" + AMOUNT + ",\"status\":\"" + status + "\"}";
    }

    @DisplayName("거래를 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("POST /api/v1/payments에 X-USER-ID 헤더와 계약된 JSON을 보내고, 202 PENDING이면 transactionKey와 함께 수락으로 본다.")
        @Test
        void sendsContractRequest_andAcceptsPending() {
            // arrange
            pg.stubFor(post(urlEqualTo("/api/v1/payments"))
                .willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json")
                    .withBody(transactionJson("PENDING"))));

            // act
            CreateResult result = gateway.create(request());

            // assert
            assertThat(result).isEqualTo(new CreateResult.Accepted(TX_KEY));
            pg.verify(exactly(1), postRequestedFor(urlEqualTo("/api/v1/payments"))
                .withHeader("X-USER-ID", equalTo("135135"))
                .withRequestBody(equalToJson("{\"orderId\":\"" + PROVIDER_ORDER_ID + "\",\"cardType\":\"TEST\","
                    + "\"cardNo\":\"0000-0000-0000-0000\",\"amount\":" + AMOUNT + ",\"callbackUrl\":\"" + CALLBACK_URL + "\"}")));
        }

        @DisplayName("code·transactionCreated:false·orderId·amount가 모두 맞는 422만, 효과 없는 거절로 본다.")
        @Test
        void treatsExactRejectionAsNoEffect() {
            // arrange
            pg.stubFor(post(urlEqualTo("/api/v1/payments"))
                .willReturn(aResponse().withStatus(422).withHeader("Content-Type", "application/json")
                    .withBody("{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"" + PROVIDER_ORDER_ID
                        + "\",\"amount\":" + AMOUNT + ",\"transactionCreated\":false}")));

            // act & assert
            assertThat(gateway.create(request())).isInstanceOf(CreateResult.RejectedNoEffect.class);
        }

        @DisplayName("조건이 하나라도 다른 422는, 효과 0건의 증거가 아니라서 UNKNOWN이다. (INV-004)")
        @ParameterizedTest
        @ValueSource(strings = {
            "",
            "{\"code\":\"OTHER\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":7000,\"transactionCreated\":false}",
            "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":7000,\"transactionCreated\":true}",
            "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":7000}",
            "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":9000,\"transactionCreated\":false}",
            "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"LP-ORD-0000000043\",\"amount\":7000,\"transactionCreated\":false}"
        })
        void treatsInexactRejectionAsUnknown(String body) {
            // arrange
            pg.stubFor(post(urlEqualTo("/api/v1/payments"))
                .willReturn(aResponse().withStatus(422).withHeader("Content-Type", "application/json").withBody(body)));

            // act & assert
            assertThat(gateway.create(request())).isInstanceOf(CreateResult.Unknown.class);
        }

        @DisplayName("5xx나 해석할 수 없는 202는, UNKNOWN이다.")
        @ParameterizedTest
        @ValueSource(ints = {500, 503})
        void treatsServerErrorAsUnknown(int status) {
            // arrange
            pg.stubFor(post(urlEqualTo("/api/v1/payments")).willReturn(aResponse().withStatus(status)));

            // act & assert
            assertThat(gateway.create(request())).isInstanceOf(CreateResult.Unknown.class);
        }

        @DisplayName("202인데 본문이 깨졌으면, 거래가 생겼는지 모르므로 UNKNOWN이다.")
        @Test
        void treatsMalformedAcceptedAsUnknown() {
            // arrange
            pg.stubFor(post(urlEqualTo("/api/v1/payments"))
                .willReturn(aResponse().withStatus(202).withBody("{not json")));

            // act & assert
            assertThat(gateway.create(request())).isInstanceOf(CreateResult.Unknown.class);
        }

        @DisplayName("응답이 응답 timeout보다 늦으면, 기다리지 않고 UNKNOWN이며 PG가 받은 POST는 정확히 1건이다. (D-030, INV-005)")
        @Test
        void givesUpAfterReadTimeout_withoutResending() throws InterruptedException {
            // arrange
            pg.stubFor(post(urlEqualTo("/api/v1/payments"))
                .willReturn(aResponse().withStatus(202).withBody(transactionJson("PENDING")).withFixedDelay(2000)));

            // act
            long started = System.nanoTime();
            CreateResult result = gateway.create(request());
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            // assert
            assertAll(
                () -> assertThat(result).isInstanceOf(CreateResult.Unknown.class),
                () -> assertThat(elapsed).isLessThan(Duration.ofMillis(1500))
            );
            verifyExactlyAfterDelays(1, postRequestedFor(urlEqualTo("/api/v1/payments")), Duration.ofMillis(2000));
        }

        @DisplayName("요청을 받은 뒤 응답 없이 연결이 닫히면, UNKNOWN이고 다시 보내지 않는다. (장면 4의 바탕)")
        @Test
        void treatsEmptyResponseAsUnknown_withoutResending() {
            // arrange
            pg.stubFor(post(urlEqualTo("/api/v1/payments"))
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

            // act
            CreateResult result = gateway.create(request());

            // assert
            assertThat(result).isInstanceOf(CreateResult.Unknown.class);
            pg.verify(exactly(1), postRequestedFor(urlEqualTo("/api/v1/payments")));
        }

        @DisplayName("연결 자체가 안 돼도, 실패가 아니라 UNKNOWN이다. (D-025)")
        @Test
        void treatsConnectionFailureAsUnknown() throws Exception {
            // arrange — 비어 있는 포트를 찾아 아무도 듣지 않는 주소로 보낸다
            int closedPort;
            try (ServerSocket socket = new ServerSocket(0)) {
                closedPort = socket.getLocalPort();
            }
            PgPaymentGateway unreachable = gatewayFor("http://127.0.0.1:" + closedPort);

            // act & assert
            assertThat(unreachable.create(request())).isInstanceOf(CreateResult.Unknown.class);
        }
    }

    @DisplayName("거래를 조회할 때, ")
    @Nested
    class Lookup {
        @DisplayName("단건 조회가 200이면, 관찰 결과로 바꾼다.")
        @Test
        void returnsObservation_whenFound() {
            // arrange
            pg.stubFor(get(urlEqualTo("/api/v1/payments/" + TX_KEY))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody(transactionJson("SUCCESS"))));

            // act
            Optional<PaymentObservation> result = gateway.findByTransactionKey(TX_KEY, LookupRetry.NONE);

            // assert
            assertThat(result).contains(
                new PaymentObservation(PROVIDER_ORDER_ID, TX_KEY, PaymentObservation.Status.SUCCESS, AMOUNT));
            pg.verify(exactly(1), getRequestedFor(urlEqualTo("/api/v1/payments/" + TX_KEY))
                .withHeader("X-USER-ID", equalTo("135135")));
        }

        @DisplayName("단건 조회가 404면, 예외가 아니라 빈 값이다. (없음과 물어보지 못함은 다르다)")
        @Test
        void returnsEmpty_whenNotFound() {
            // arrange
            pg.stubFor(get(urlEqualTo("/api/v1/payments/" + TX_KEY)).willReturn(aResponse().withStatus(404)));

            // act & assert
            assertThat(gateway.findByTransactionKey(TX_KEY, LookupRetry.CUSTOMER)).isEmpty();
            pg.verify(exactly(1), getRequestedFor(urlEqualTo("/api/v1/payments/" + TX_KEY)));
        }

        @DisplayName("목록 조회는 0건·2건을 그대로 돌려준다. 고르거나 실패로 바꾸지 않는다.")
        @Test
        void returnsAllTransactions_includingEmptyAndMultiple() {
            // arrange
            pg.stubFor(get(urlPathEqualTo("/api/v1/payments")).withQueryParam("orderId", equalTo(PROVIDER_ORDER_ID))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody("[" + transactionJson("PENDING") + "," + transactionJson("SUCCESS") + "]")));
            pg.stubFor(get(urlPathEqualTo("/api/v1/payments")).withQueryParam("orderId", equalTo("LP-ORD-0000000099"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]")));

            // act
            List<PaymentObservation> multiple = gateway.findByProviderOrderId(PROVIDER_ORDER_ID, LookupRetry.NONE);
            List<PaymentObservation> none = gateway.findByProviderOrderId("LP-ORD-0000000099", LookupRetry.NONE);

            // assert
            assertAll(
                () -> assertThat(multiple).hasSize(2),
                () -> assertThat(none).isEmpty()
            );
        }

        @DisplayName("목록 조회의 orderId는 인코딩되어 그대로 전달된다.")
        @Test
        void encodesQueryParameter() {
            // arrange
            String unusual = "LP ORD&42=x";
            pg.stubFor(get(urlPathEqualTo("/api/v1/payments")).withQueryParam("orderId", equalTo(unusual))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]")));

            // act
            List<PaymentObservation> result = gateway.findByProviderOrderId(unusual, LookupRetry.NONE);

            // assert
            assertThat(result).isEmpty();
            pg.verify(exactly(1), getRequestedFor(urlPathEqualTo("/api/v1/payments")).withQueryParam("orderId", equalTo(unusual)));
        }

        @DisplayName("조회가 계속 timeout이면, 경로별 횟수만큼만 묻고 '확인 불가' 예외를 던진다. (D-028)")
        @ParameterizedTest
        @EnumSource(LookupRetry.class)
        void retriesOnlyAsManyAsPolicyAllows(LookupRetry policy) throws InterruptedException {
            // arrange
            pg.stubFor(get(urlEqualTo("/api/v1/payments/" + TX_KEY))
                .willReturn(aResponse().withStatus(200).withBody(transactionJson("SUCCESS")).withFixedDelay(1000)));

            // act & assert
            assertThrows(PaymentGatewayUnavailableException.class, () -> gateway.findByTransactionKey(TX_KEY, policy));
            verifyExactlyAfterDelays(policy.maxAttempts(), getRequestedFor(urlEqualTo("/api/v1/payments/" + TX_KEY)),
                Duration.ofMillis(1000));
        }

        @DisplayName("첫 조회가 5xx여도 재시도 안에서 성공하면, 그 결과를 돌려준다.")
        @Test
        void recoversWithinRetry() {
            // arrange
            pg.stubFor(get(urlEqualTo("/api/v1/payments/" + TX_KEY)).inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED).willSetStateTo("recovered")
                .willReturn(aResponse().withStatus(503)));
            pg.stubFor(get(urlEqualTo("/api/v1/payments/" + TX_KEY)).inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody(transactionJson("FAILED"))));

            // act
            Optional<PaymentObservation> result = gateway.findByTransactionKey(TX_KEY, LookupRetry.CUSTOMER);

            // assert
            assertThat(result).get().extracting(PaymentObservation::status).isEqualTo(PaymentObservation.Status.FAILED);
            pg.verify(exactly(2), getRequestedFor(urlEqualTo("/api/v1/payments/" + TX_KEY)));
        }

        @DisplayName("모르는 상태값이 오면, 결과를 정하지 않고 '확인 불가'로 둔다. (INV-004)")
        @Test
        void refusesUnknownStatus() {
            // arrange
            pg.stubFor(get(urlEqualTo("/api/v1/payments/" + TX_KEY))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody(transactionJson("CANCELED"))));

            // act & assert
            assertThrows(PaymentGatewayUnavailableException.class,
                () -> gateway.findByTransactionKey(TX_KEY, LookupRetry.NONE));
        }
    }

    /**
     * 조회 circuit breaker(D-029). 운영 수치가 아니라 동작을 본다 — 작은 값(창 4, 최소 표본 4, 실패율 50%, OPEN 300ms, 탐색 1건)으로 전이를 확인한다.
     */
    @DisplayName("조회 circuit breaker는, ")
    @Nested
    class LookupCircuitBreaker {
        private CircuitBreaker circuitBreaker;
        private PgPaymentGateway guarded;

        @BeforeEach
        void setUp() {
            circuitBreaker = CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(300))
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordExceptions(PaymentGatewayUnavailableException.class)
                .build());
            PgClientSettings settings = new PgClientSettings(pg.baseUrl(), "135135", CALLBACK_URL,
                Duration.ofMillis(300), CREATE_READ_TIMEOUT, Duration.ofMillis(300), LOOKUP_READ_TIMEOUT, Duration.ofMillis(10));
            guarded = new PgPaymentGateway(settings, new ObjectMapper(), circuitBreaker);
        }

        private void failLookups(int times) {
            for (int i = 0; i < times; i++) {
                assertThrows(PaymentGatewayUnavailableException.class, () -> guarded.findByTransactionKey(TX_KEY, LookupRetry.NONE));
            }
        }

        @DisplayName("물어보지 못한 조회가 최소 표본만큼 쌓여 실패율을 넘으면 열리고, 열린 동안은 PG를 부르지 않고 \"물어보지 못함\"을 돌려준다.")
        @Test
        void opens_andStopsCallingPg() {
            // arrange
            pg.stubFor(get(urlEqualTo("/api/v1/payments/" + TX_KEY)).willReturn(aResponse().withStatus(500)));
            failLookups(4);
            pg.resetRequests();

            // act
            assertThrows(PaymentGatewayUnavailableException.class, () -> guarded.findByTransactionKey(TX_KEY, LookupRetry.CUSTOMER));

            // assert
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            pg.verify(exactly(0), getRequestedFor(urlEqualTo("/api/v1/payments/" + TX_KEY)));
        }

        @DisplayName("404와 빈 목록은 정상 응답이라 아무리 쌓여도 열리지 않는다.")
        @Test
        void staysClosed_forNotFoundAndEmptyList() {
            // arrange
            pg.stubFor(get(urlEqualTo("/api/v1/payments/" + TX_KEY)).willReturn(aResponse().withStatus(404)));
            pg.stubFor(get(urlPathEqualTo("/api/v1/payments")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("[]")));

            // act
            for (int i = 0; i < 5; i++) {
                assertThat(guarded.findByTransactionKey(TX_KEY, LookupRetry.NONE)).isEmpty();
                assertThat(guarded.findByProviderOrderId(PROVIDER_ORDER_ID, LookupRetry.NONE)).isEmpty();
            }

            // assert
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @DisplayName("재시도까지 끝난 조회 한 번이 표본 하나다 — 고객 경로 3회 재시도 실패 3번(HTTP 9번)으로는 최소 표본(4)에 닿지 않는다.")
        @Test
        void countsRetriedLookupAsOneSample() {
            // arrange
            pg.stubFor(get(urlEqualTo("/api/v1/payments/" + TX_KEY)).willReturn(aResponse().withStatus(500)));

            // act
            for (int i = 0; i < 3; i++) {
                assertThrows(PaymentGatewayUnavailableException.class, () -> guarded.findByTransactionKey(TX_KEY, LookupRetry.CUSTOMER));
            }

            // assert
            assertAll(
                () -> assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(3),
                () -> assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED)
            );
            pg.verify(exactly(9), getRequestedFor(urlEqualTo("/api/v1/payments/" + TX_KEY)));
        }

        @DisplayName("열린 뒤 대기 시간이 지나면 탐색 조회를 한 번 보내고, 성공하면 다시 닫힌다.")
        @Test
        void halfOpensAfterWait_andClosesOnSuccess() throws InterruptedException {
            // arrange
            pg.stubFor(get(urlEqualTo("/api/v1/payments/" + TX_KEY)).willReturn(aResponse().withStatus(500)));
            failLookups(4);
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            pg.stubFor(get(urlEqualTo("/api/v1/payments/" + TX_KEY)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(transactionJson("SUCCESS"))));
            TimeUnit.MILLISECONDS.sleep(400);

            // act
            Optional<PaymentObservation> probe = guarded.findByTransactionKey(TX_KEY, LookupRetry.NONE);

            // assert
            assertAll(
                () -> assertThat(probe).isPresent(),
                () -> assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED)
            );
        }

        @DisplayName("열려 있어도 거래 생성(create)은 막지 않는다 — circuit breaker는 조회 경로에만 건다.")
        @Test
        void doesNotGuardCreate() {
            // arrange
            circuitBreaker.transitionToOpenState();
            pg.stubFor(post(urlEqualTo("/api/v1/payments")).willReturn(aResponse().withStatus(202)
                .withHeader("Content-Type", "application/json").withBody(transactionJson("PENDING"))));

            // act
            CreateResult result = guarded.create(request());

            // assert
            assertThat(result).isEqualTo(new CreateResult.Accepted(TX_KEY));
            pg.verify(exactly(1), postRequestedFor(urlEqualTo("/api/v1/payments")));
        }
    }
}
