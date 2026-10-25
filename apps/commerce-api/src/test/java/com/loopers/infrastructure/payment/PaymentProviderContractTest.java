package com.loopers.infrastructure.payment;

import com.loopers.domain.common.Money;
import com.loopers.domain.payment.CardNumber;
import com.loopers.domain.payment.CardType;
import com.loopers.domain.payment.CreateObservation;
import com.loopers.domain.payment.LookupResult;
import com.loopers.domain.payment.PaymentCreation;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PgTransaction;
import com.loopers.domain.payment.ProviderOrderId;
import com.loopers.domain.payment.TransactionKey;
import com.loopers.support.pg.PgFixture;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = PaymentProviderContractTest.Context.class)
@ActiveProfiles("test")
class PaymentProviderContractTest {

    private static final PgFixture PG = PgFixture.start();

    private static final Duration OPEN_WAIT = Duration.ofMillis(300);

    @Autowired
    private PaymentGateway gateway;

    @Autowired
    private CircuitBreaker lookupCircuit;

    @DynamicPropertySource
    static void pgBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("pg.base-url", PG::baseUrl);
        registry.add("pg.create-timeout", () -> "300ms");
        registry.add("pg.lookup-circuit.open-wait", () -> OPEN_WAIT.toMillis() + "ms");
    }

    @BeforeEach
    void closeCircuit() {
        lookupCircuit.reset();
    }

    @AfterAll
    static void stopPg() {
        PG.close();
    }

    @Nested
    @DisplayName("PG-003, PG-004, PG-005: 생성은 계약대로 보내고 202 를 접수로 읽는다")
    class Create {

        @DisplayName("POST /api/v1/payments 에 X-USER-ID 와 JSON 본문(문자열 orderId, 정수 amount, 카드, 설정의 callbackUrl)을 보낸다")
        @Test
        void sendsContractRequest() {
            gateway.create(creation(42L, 7000));

            PgFixture.ReceivedRequest sent = sentFor("LP-ORD-0000000042");
            assertThat(sent.method()).isEqualTo("POST");
            assertThat(sent.path()).isEqualTo("/api/v1/payments");
            assertThat(sent.userId()).isEqualTo("135135");
            assertThat(sent.contentType()).startsWith("application/json");
            assertThat(sent.body().get("orderId").isTextual()).isTrue();
            assertThat(sent.body().get("amount").isIntegralNumber()).isTrue();
            assertThat(sent.body().get("amount").asLong()).isEqualTo(7000);
            assertThat(sent.body().get("cardType").asText()).isEqualTo("TEST");
            assertThat(sent.body().get("cardNo").asText()).isEqualTo("0000-0000-0000-0000");
            assertThat(sent.body().get("callbackUrl").asText()).isEqualTo("http://127.0.0.1:8080/api/v1/payments/callback");
        }

        @DisplayName("202 를 받으면 PG 가 만든 거래의 키로 접수(Accepted)를 돌려준다")
        @Test
        void acceptedWithTransactionKey() {
            CreateObservation observed = gateway.create(creation(43L, 7000));

            String created = PG.transactionsOf("LP-ORD-0000000043").get(0).transactionKey();
            assertThat(observed).isEqualTo(new CreateObservation.Accepted(new TransactionKey(created)));
        }
    }

    @Nested
    @DisplayName("PG-010: 다섯 조건이 모두 맞는 422 만 효과 0 건 거절이다")
    class Rejection {

        @DisplayName("422, code, transactionCreated:false, 같은 orderId, 같은 amount 면 RejectedNoEffect")
        @Test
        void rejectedWithNoEffect() {
            PG.rejectNextCreate();

            assertThat(gateway.create(creation(44L, 7000)))
                .isEqualTo(new CreateObservation.RejectedNoEffect());
        }

        @DisplayName("하나라도 어긋난 422 는 거절이 아니라 결과 미확인이다")
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.loopers.infrastructure.payment.PaymentProviderContractTest#brokenRejections")
        void unknownUnlessAllFive(String scene, String body) {
            PG.respondNextCreateWith(422, body);

            assertThat(gateway.create(creation(45L, 7000)))
                .isEqualTo(new CreateObservation.CreateUnknown());
        }
    }

    static Stream<Arguments> brokenRejections() {
        String order = "LP-ORD-0000000045";
        return Stream.of(
            Arguments.of("본문 없음", ""),
            Arguments.of("JSON 이 아님", "rejected"),
            Arguments.of("다른 code", rejection("OTHER", "false", order, 7000)),
            Arguments.of("transactionCreated 가 true", rejection("TEST_PG_REJECTED_BEFORE_CREATE", "true", order, 7000)),
            Arguments.of("다른 orderId", rejection("TEST_PG_REJECTED_BEFORE_CREATE", "false", "LP-ORD-0000000099", 7000)),
            Arguments.of("다른 amount", rejection("TEST_PG_REJECTED_BEFORE_CREATE", "false", order, 7001))
        );
    }

    private static String rejection(String code, String created, String orderId, long amount) {
        return """
            {"code":"%s","transactionCreated":%s,"orderId":"%s","amount":%d}"""
            .formatted(code, created, orderId, amount);
    }

    @Nested
    @DisplayName("PG-011, PG-012: 그 밖의 생성 결과는 모두 결과 미확인이고, 다시 보내지 않는다")
    class Unknown {

        @DisplayName("응답이 끊기면 CreateUnknown 이고, client 가 몰래 다시 보내지 않는다")
        @Test
        void droppedResponse() {
            int before = PG.createCount();
            PG.dropNextCreateResponse();

            assertThat(gateway.create(creation(46L, 7000)))
                .isEqualTo(new CreateObservation.CreateUnknown());
            assertThat(PG.createCount()).isEqualTo(before + 1);
        }

        @DisplayName("응답이 생성 timeout 보다 늦으면 CreateUnknown 이다")
        @Test
        void timedOut() {
            PgFixture.HeldResponse held = PG.holdNextCreateResponse();
            try {
                assertThat(gateway.create(creation(47L, 7000)))
                    .isEqualTo(new CreateObservation.CreateUnknown());
            } finally {
                held.release();
            }
        }

        @DisplayName("5xx, 다른 4xx, 식별자나 금액이 요청과 다른 202 는 CreateUnknown 이다")
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.loopers.infrastructure.payment.PaymentProviderContractTest#unknownResponses")
        void unknownResponses(String scene, int status, String body) {
            PG.respondNextCreateWith(status, body);

            assertThat(gateway.create(creation(48L, 7000)))
                .isEqualTo(new CreateObservation.CreateUnknown());
        }
    }

    static Stream<Arguments> unknownResponses() {
        String order = "LP-ORD-0000000048";
        return Stream.of(
            Arguments.of("500", 500, ""),
            Arguments.of("503", 503, ""),
            Arguments.of("다른 4xx", 400, ""),
            Arguments.of("202 인데 다른 orderId", 202, accepted("TEST-TX-9001", "LP-ORD-0000000099", 7000)),
            Arguments.of("202 인데 다른 amount", 202, accepted("TEST-TX-9002", order, 7001)),
            Arguments.of("202 인데 키 없음", 202, """
                {"orderId":"%s","amount":7000,"status":"PENDING"}""".formatted(order)),
            Arguments.of("202 인데 JSON 이 아님", 202, "accepted")
        );
    }

    private static String accepted(String key, String orderId, long amount) {
        return """
            {"transactionKey":"%s","orderId":"%s","amount":%d,"status":"PENDING"}"""
            .formatted(key, orderId, amount);
    }

    @Nested
    @DisplayName("PG-007, PG-008, PAY-008: 조회는 PG 가 준 거래를 판정 없이 옮기고, 조회 실패는 관찰로 만들지 않는다")
    class Lookup {

        @DisplayName("키 조회 200 은 그 거래 하나를 Found 로 돌려준다")
        @Test
        void foundByKey() {
            gateway.create(creation(60L, 7000));
            String key = PG.transactionsOf("LP-ORD-0000000060").get(0).transactionKey();
            PG.changeStatus(key, PgFixture.Status.SUCCESS);

            LookupResult result = gateway.lookup(new TransactionKey(key));

            assertThat(result).isEqualTo(new LookupResult.Found(List.of(new PgTransaction(
                new TransactionKey(key), ProviderOrderId.of(60L), Money.of(7000), PgTransaction.Status.SUCCESS
            ))));
        }

        @DisplayName("없는 키는 NotFound 다")
        @Test
        void notFoundByKey() {
            assertThat(gateway.lookup(new TransactionKey("TEST-TX-9999")))
                .isEqualTo(new LookupResult.NotFound());
        }

        @DisplayName("주문 조회는 그 주문의 거래를 모두 Found 로, 없으면 NotFound 로 돌려준다")
        @Test
        void byOrder() {
            gateway.create(creation(61L, 7000));
            gateway.create(creation(61L, 7000));

            LookupResult found = gateway.lookup(ProviderOrderId.of(61L));

            assertThat(found).isInstanceOfSatisfying(LookupResult.Found.class,
                result -> assertThat(result.transactions()).hasSize(2));
            assertThat(gateway.lookup(ProviderOrderId.of(62L))).isEqualTo(new LookupResult.NotFound());
        }

        @DisplayName("주문 번호는 query 값으로 인코딩해 보낸다")
        @Test
        void encodesOrderIdInQuery() {
            gateway.lookup(new ProviderOrderId("LP-ORD&x=1"));

            assertThat(lastLookup().rawQuery()).isEqualTo("orderId=LP-ORD%26x%3D1");
        }

        @DisplayName("5xx, 429, 해석할 수 없는 본문은 Unavailable 이다")
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.loopers.infrastructure.payment.PaymentProviderContractTest#unavailableLookups")
        void unavailable(String scene, int status, String body) {
            PG.respondNextLookupWith(status, body);

            assertThat(gateway.lookup(ProviderOrderId.of(64L)))
                .isEqualTo(new LookupResult.Unavailable());
        }
    }

    static Stream<Arguments> unavailableLookups() {
        return Stream.of(
            Arguments.of("500", 500, ""),
            Arguments.of("503", 503, ""),
            Arguments.of("429", 429, ""),
            Arguments.of("200 인데 JSON 이 아님", 200, "not json")
        );
    }

    @Nested
    @DisplayName("RESIL-001, RESIL-002, RESIL-003, RESIL-004: 조회 경로 하나에 회로가 있고, 열리면 PG 를 부르지 않는다")
    class LookupCircuit {

        private static final int MIN_CALLS = 10;

        private final TransactionKey unknown = new TransactionKey("TEST-TX-NOPE");

        private long lookups() {
            return PG.receivedRequests().stream().filter(request -> request.method().equals("GET")).count();
        }

        private void failLookups(int times, int status) {
            for (int i = 0; i < times; i++) {
                PG.respondNextLookupWith(status, "{}");
                assertThat(gateway.lookup(unknown)).isEqualTo(new LookupResult.Unavailable());
            }
        }

        private TransactionKey createdKey(long orderId) {
            gateway.create(creation(orderId, 7000));
            return new TransactionKey(PG.transactionsOf(ProviderOrderId.of(orderId).value()).getFirst().transactionKey());
        }

        @DisplayName("창 안의 표본이 최소 10 회 차고 모두 5xx 면 OPEN 이 되고, 그 뒤 조회는 PG 를 부르지 않고 조회 불가다")
        @Test
        void opensOnServerErrors() {
            failLookups(MIN_CALLS, 500);
            long before = lookups();

            LookupResult result = gateway.lookup(unknown);

            assertThat(lookupCircuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(result).isEqualTo(new LookupResult.Unavailable());
            assertThat(lookups()).isEqualTo(before);
        }

        @DisplayName("429 도 실패로 센다. 다시 보내지 않으므로 호출마다 PG 조회는 한 번이다")
        @Test
        void countsTooManyRequests() {
            long before = lookups();

            failLookups(MIN_CALLS, 429);

            assertThat(lookupCircuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(lookups() - before).isEqualTo(MIN_CALLS);
        }

        @DisplayName("404 는 업무 결과라 세지 않는다. 10 번 없어도 닫혀 있다")
        @Test
        void ignoresNotFound() {
            for (int i = 0; i < 10; i++) {
                assertThat(gateway.lookup(unknown)).isEqualTo(new LookupResult.NotFound());
            }

            assertThat(lookupCircuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @DisplayName("표본이 최소 10 회를 채워도 실패율이 50 % 아래면 닫혀 있다. 성공 8 회 · 실패 2 회")
        @Test
        void staysClosedUnderThreshold() {
            TransactionKey key = createdKey(301L);
            for (int i = 0; i < 8; i++) {
                gateway.lookup(key);
            }

            failLookups(2, 500);

            assertThat(lookupCircuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @DisplayName("표본이 최소 10 회에 못 미치면 모두 실패여도 판단하지 않는다. sweeper 재시도로 결제 한두 건의 실패가 표본 여러 개가 돼도 열리지 않게")
        @Test
        void waitsForMinimumCalls() {
            failLookups(MIN_CALLS - 1, 500);

            assertThat(lookupCircuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @DisplayName("응답은 오지만 500 ms 를 넘는 조회가 50 % 이상이면 실패가 없어도 OPEN 이다. 커넥션 풀이 차기 전에 끊는다")
        @Test
        void opensOnSlowCalls() {
            TransactionKey key = createdKey(306L);
            PG.delayLookups(Duration.ofMillis(600));
            try {
                for (int i = 0; i < MIN_CALLS; i++) {
                    assertThat(gateway.lookup(key)).isInstanceOf(LookupResult.Found.class);
                }
            } finally {
                PG.delayLookups(Duration.ZERO);
            }

            assertThat(lookupCircuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        @DisplayName("회로는 하나다. 키 조회로 열리면 주문 조회도 PG 를 부르지 않는다")
        @Test
        void sharedByBothLookups() {
            failLookups(MIN_CALLS, 500);
            long before = lookups();

            LookupResult result = gateway.lookup(ProviderOrderId.of(302L));

            assertThat(result).isEqualTo(new LookupResult.Unavailable());
            assertThat(lookups()).isEqualTo(before);
        }

        @DisplayName("생성은 회로를 거치지 않는다. 회로가 열려도 POST 는 나간다")
        @Test
        void createIgnoresCircuit() {
            failLookups(MIN_CALLS, 500);
            int before = PG.createCount();

            CreateObservation observed = gateway.create(creation(303L, 7000));

            assertThat(observed).isInstanceOf(CreateObservation.Accepted.class);
            assertThat(PG.createCount()).isEqualTo(before + 1);
        }

        @DisplayName("대기가 지나면 HALF_OPEN 이고, 탐색 2 회가 성공하면 CLOSED 다")
        @Test
        void closesAfterSuccessfulProbes() throws InterruptedException {
            TransactionKey key = createdKey(304L);
            failLookups(MIN_CALLS, 500);
            Thread.sleep(OPEN_WAIT.plusMillis(100).toMillis());

            gateway.lookup(key);
            assertThat(lookupCircuit.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
            gateway.lookup(key);

            assertThat(lookupCircuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @DisplayName("HALF_OPEN 의 탐색이 실패하면 다시 OPEN 이다")
        @Test
        void reopensOnFailedProbe() throws InterruptedException {
            TransactionKey key = createdKey(305L);
            failLookups(MIN_CALLS, 500);
            Thread.sleep(OPEN_WAIT.plusMillis(100).toMillis());

            gateway.lookup(key);
            PG.respondNextLookupWith(500, "{}");
            gateway.lookup(unknown);

            assertThat(lookupCircuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }
    }

    private static PgFixture.ReceivedRequest lastLookup() {
        return PG.receivedRequests().stream()
            .filter(request -> request.method().equals("GET"))
            .reduce((first, second) -> second)
            .orElseThrow();
    }

    private static PaymentCreation creation(long orderId, long amount) {
        return new PaymentCreation(
            ProviderOrderId.of(orderId),
            CardType.TEST,
            CardNumber.of("0000-0000-0000-0000"),
            Money.of(amount)
        );
    }

    private static PgFixture.ReceivedRequest sentFor(String providerOrderId) {
        return PG.receivedRequests().stream()
            .filter(request -> request.method().equals("POST"))
            .filter(request -> request.body().get("orderId").asText().equals(providerOrderId))
            .reduce((first, second) -> second)
            .orElseThrow();
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
        JacksonAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        RestClientAutoConfiguration.class,
        SslAutoConfiguration.class
    })
    @EnableConfigurationProperties(PgProperties.class)
    @Import({PgClientConfig.class, PgPaymentGateway.class})
    static class Context {
    }
}
