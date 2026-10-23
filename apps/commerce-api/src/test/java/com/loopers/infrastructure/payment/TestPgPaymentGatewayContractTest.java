package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.PaymentCreateCommand;
import com.loopers.application.payment.PaymentCreateObservation;
import com.loopers.application.payment.PaymentLookupObservation;
import com.loopers.application.payment.PgTransaction;
import com.loopers.application.payment.PgTransactionStatus;
import com.loopers.support.testpg.TestPgContainer;
import com.loopers.support.testpg.TestPgControl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 adapter와 로컬 테스트 PG 컨테이너를 HTTP로 연결해 계약과 관찰 결과 매핑을 확인한다.
class TestPgPaymentGatewayContractTest {
    private static final long AMOUNT = 7_000L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TestPgPaymentGateway gateway = gateway(TestPgContainer.baseUrl(), Duration.ofSeconds(1));
    private String providerOrderId;

    @BeforeEach
    void isolateOrder() {
        providerOrderId = "LP-ORD-0000000042-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Nested
    class Create {

        @DisplayName("PG가 접수하면 ACCEPTED와 거래 키를 넘기고, PG에는 요청한 주문 ID·금액의 PENDING 거래 1건이 생긴다.")
        @Test
        void acceptsAndCreatesOneTransaction() {
            PaymentCreateObservation observation = gateway.create(command());

            assertThat(observation).isInstanceOfSatisfying(PaymentCreateObservation.Accepted.class,
                accepted -> assertThat(accepted.transactionKey()).isNotBlank());
            String key = ((PaymentCreateObservation.Accepted) observation).transactionKey();
            assertThat(gateway.lookup(key)).isEqualTo(new PaymentLookupObservation.Found(
                java.util.List.of(new PgTransaction(key, providerOrderId, AMOUNT, PgTransactionStatus.PENDING))
            ));
            assertThat(TestPgControl.stats(providerOrderId).createRequests()).isEqualTo(1);
        }

        @DisplayName("생성 전 확정 거절은 REJECTED_NO_EFFECT이고 거래는 생기지 않는다.")
        @Test
        void mapsRejectionBeforeCreate() {
            TestPgControl.scenario(providerOrderId, "{\"scenario\":\"REJECT_BEFORE_CREATE\"}");

            assertThat(gateway.create(command())).isInstanceOf(PaymentCreateObservation.RejectedNoEffect.class);
            assertThat(TestPgControl.stats(providerOrderId).transactions()).isZero();
        }

        @DisplayName("생성 응답이 timeout되면 UNKNOWN이고, 거래는 1건 남으며 생성 요청은 한 번만 보낸다.")
        @Test
        void mapsDroppedResponseToUnknownWithoutRetry() {
            TestPgControl.scenario(providerOrderId, "{\"scenario\":\"DROP_RESPONSE\"}");

            assertThat(gateway.create(command())).isInstanceOf(PaymentCreateObservation.Unknown.class);

            TestPgControl.Stats stats = TestPgControl.stats(providerOrderId);
            assertThat(stats.transactions()).isEqualTo(1);
            assertThat(stats.createRequests()).isEqualTo(1);
        }

        @DisplayName("거절 조건을 하나라도 어긴 응답이나 접수 형식이 아닌 응답은 UNKNOWN이고, 생성 요청은 한 번만 보낸다.")
        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.loopers.infrastructure.payment.TestPgPaymentGatewayContractTest#unknownResponses")
        void mapsMalformedResponsesToUnknown(String description, int status, String bodyTemplate, boolean createTransaction)
            throws Exception {
            String body = bodyTemplate.replace("{orderId}", providerOrderId);
            TestPgControl.scenario(providerOrderId, JSON.writeValueAsString(java.util.Map.of(
                "scenario", "CREATE_RESPONSE_OVERRIDE", "responseStatus", status,
                "responseBody", body, "createTransaction", createTransaction
            )));

            assertThat(gateway.create(command())).isInstanceOf(PaymentCreateObservation.Unknown.class);
            assertThat(TestPgControl.stats(providerOrderId).createRequests()).isEqualTo(1);
        }
    }

    @Nested
    class Lookup {

        @DisplayName("없는 거래 키의 단건 조회는 NOT_FOUND다.")
        @Test
        void mapsMissingTransactionToNotFound() {
            assertThat(gateway.lookup("TEST-TX-missing")).isInstanceOf(PaymentLookupObservation.NotFound.class);
        }

        @DisplayName("주문 단위 조회는 거래가 없으면 빈 목록, 한 건 생성하면 해당 거래를 FOUND로 넘긴다.")
        @Test
        void listsTransactionsByOrder() {
            assertThat(gateway.lookupByOrder(providerOrderId)).isEqualTo(new PaymentLookupObservation.Found(java.util.List.of()));

            gateway.create(command());

            assertThat(gateway.lookupByOrder(providerOrderId)).isInstanceOfSatisfying(PaymentLookupObservation.Found.class,
                found -> assertThat(found.transactions()).hasSize(1)
                    .allSatisfy(transaction -> {
                        assertThat(transaction.providerOrderId()).isEqualTo(providerOrderId);
                        assertThat(transaction.amount()).isEqualTo(AMOUNT);
                        assertThat(transaction.status()).isEqualTo(PgTransactionStatus.PENDING);
                    }));
        }

        @DisplayName("PG가 처리한 결과를 조회로 확인한다.")
        @Test
        void observesCompletedResult() {
            TestPgControl.scenario(providerOrderId, "{\"scenario\":\"FAIL\"}");
            String key = ((PaymentCreateObservation.Accepted) gateway.create(command())).transactionKey();

            TestPgControl.complete(providerOrderId);

            assertThat(gateway.lookup(key)).isInstanceOfSatisfying(PaymentLookupObservation.Found.class,
                found -> assertThat(found.transactions()).singleElement()
                    .extracting(PgTransaction::status).isEqualTo(PgTransactionStatus.FAILED));
        }

        @DisplayName("PG가 다른 제공자 계정을 401로 거절하면 생성은 UNKNOWN, 조회는 UNAVAILABLE이다.")
        @Test
        void mapsUnauthorizedProvider() {
            TestPgPaymentGateway otherAccount = new TestPgPaymentGateway(new PgProperties(
                TestPgContainer.baseUrl(), "http://127.0.0.1:9/api/v1/payments/callback", "999999",
                Duration.ofSeconds(1), Duration.ofSeconds(1)
            ), new ObjectMapper());

            assertThat(otherAccount.create(command())).isInstanceOf(PaymentCreateObservation.Unknown.class);
            assertThat(otherAccount.lookup("TEST-TX-1")).isInstanceOf(PaymentLookupObservation.Unavailable.class);
            assertThat(otherAccount.lookupByOrder(providerOrderId)).isInstanceOf(PaymentLookupObservation.Unavailable.class);
            assertThat(TestPgControl.stats(providerOrderId).transactions()).isZero();
        }

        @DisplayName("PG에 접속할 수 없으면 생성은 UNKNOWN, 조회는 UNAVAILABLE이다.")
        @Test
        void mapsConnectionFailure() {
            TestPgPaymentGateway unreachable = gateway("http://127.0.0.1:1", Duration.ofSeconds(1));

            assertThat(unreachable.create(command())).isInstanceOf(PaymentCreateObservation.Unknown.class);
            assertThat(unreachable.lookup("TEST-TX-1")).isInstanceOf(PaymentLookupObservation.Unavailable.class);
            assertThat(unreachable.lookupByOrder(providerOrderId)).isInstanceOf(PaymentLookupObservation.Unavailable.class);
        }
    }

    static Stream<Arguments> unknownResponses() {
        String rejection = "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"{orderId}\",\"amount\":7000,"
            + "\"transactionCreated\":false}";
        String acceptance = "{\"transactionKey\":\"TX-1\",\"orderId\":\"{orderId}\",\"amount\":7000,\"status\":\"PENDING\"}";
        return Stream.of(
            Arguments.of("본문 없는 422", 422, "", false),
            Arguments.of("공백뿐인 본문", 422, " \r\n\t ", false),
            Arguments.of("다른 code의 422", 422, rejection.replace("TEST_PG_REJECTED_BEFORE_CREATE", "OTHER"), false),
            Arguments.of("문자열 false", 422, rejection.replace("false}", "\"false\"}"), false),
            Arguments.of("transactionCreated 누락", 422, rejection.replace(",\"transactionCreated\":false", ""), false),
            Arguments.of("잘못된 JSON", 422, "{\"code\":", false),
            Arguments.of("거절 JSON 뒤 추가 JSON", 422, rejection + " {}", false),
            Arguments.of("거절 JSON 뒤 잘못된 문자열", 422, rejection + " NOT_JSON", false),
            Arguments.of("거절의 주문 ID 불일치", 422, rejection.replace("{orderId}", "LP-ORD-0000000001-00000000"), false),
            Arguments.of("거절의 금액 불일치", 422, rejection.replace("7000", "7001"), false),
            Arguments.of("거래 키 없는 202", 202, "{\"orderId\":\"{orderId}\",\"amount\":7000,\"status\":\"PENDING\"}", true),
            Arguments.of("접수 JSON 뒤 추가 JSON", 202, acceptance + " {}", true),
            Arguments.of("접수 JSON 뒤 잘못된 문자열", 202, acceptance + " NOT_JSON", true),
            Arguments.of("SUCCESS인 202", 202,
                "{\"transactionKey\":\"TX-1\",\"orderId\":\"{orderId}\",\"amount\":7000,\"status\":\"SUCCESS\"}", true),
            Arguments.of("접수의 금액 불일치", 202,
                "{\"transactionKey\":\"TX-1\",\"orderId\":\"{orderId}\",\"amount\":7001,\"status\":\"PENDING\"}", true),
            Arguments.of("문자열 금액", 202,
                "{\"transactionKey\":\"TX-1\",\"orderId\":\"{orderId}\",\"amount\":\"7000\",\"status\":\"PENDING\"}", true),
            Arguments.of("202가 아닌 2xx", 200,
                "{\"transactionKey\":\"TX-1\",\"orderId\":\"{orderId}\",\"amount\":7000,\"status\":\"PENDING\"}", true),
            Arguments.of("500", 500, "{\"code\":\"INTERNAL\"}", true)
        );
    }

    private PaymentCreateCommand command() {
        return new PaymentCreateCommand(providerOrderId, AMOUNT, "TEST", "0000-0000-0000-0000");
    }

    private static TestPgPaymentGateway gateway(String baseUrl, Duration readTimeout) {
        return new TestPgPaymentGateway(new PgProperties(
            baseUrl, "http://127.0.0.1:9/api/v1/payments/callback", TestPgContainer.PROVIDER_ACCOUNT_ID,
            Duration.ofSeconds(1), readTimeout
        ), new ObjectMapper());
    }
}
