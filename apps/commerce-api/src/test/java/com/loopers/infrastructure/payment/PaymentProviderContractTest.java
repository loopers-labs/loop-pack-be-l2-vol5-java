package com.loopers.infrastructure.payment;

import com.loopers.domain.common.Money;
import com.loopers.domain.payment.CardNumber;
import com.loopers.domain.payment.CardType;
import com.loopers.domain.payment.CreateObservation;
import com.loopers.domain.payment.PaymentCreation;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.ProviderOrderId;
import com.loopers.domain.payment.TransactionKey;
import com.loopers.support.pg.PgFixture;
import org.junit.jupiter.api.AfterAll;
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

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = PaymentProviderContractTest.Context.class)
@ActiveProfiles("test")
class PaymentProviderContractTest {

    private static final PgFixture PG = PgFixture.start();

    @Autowired
    private PaymentGateway gateway;

    @DynamicPropertySource
    static void pgBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("pg.base-url", PG::baseUrl);
        registry.add("pg.create-timeout", () -> "300ms");
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
