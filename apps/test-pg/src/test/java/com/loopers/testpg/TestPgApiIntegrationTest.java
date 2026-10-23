package com.loopers.testpg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("테스트 PG HTTP 계약")
class TestPgApiIntegrationTest {

    private static final String CONFIRM_PATH = "/v1/payments/confirm";
    private static final String CANCEL_PATH = "/v1/payments/[^/]+/cancel";
    private static final String PAYMENT_LOOKUP_PATH = "/v1/payments/";
    private static final String ORDER_LOOKUP_PATH = "/v1/payments/orders/";
    private static final String SECRET_KEY = "local-secret";
    private static final String AUTHORIZATION = "Basic " + Base64.getEncoder()
        .encodeToString((SECRET_KEY + ":").getBytes(StandardCharsets.UTF_8));
    private static final String REQUEST_BODY = """
        {
          "paymentKey": "test-payment-42",
          "orderId": "LP-ORD-0000000042",
          "amount": 7000
        }
        """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private WireMockServer server;

    @BeforeEach
    void setUp() {
        var store = new TransactionStore();
        server = new WireMockServer(WireMockConfiguration.options()
            .dynamicPort()
            .extensions(new TestPgResponseTransformer(store, objectMapper, SECRET_KEY)));
        server.start();
        server.stubFor(post(urlEqualTo(CONFIRM_PATH))
            .willReturn(aResponse().withStatus(404).withTransformers("local-test-pg-api")));
        server.stubFor(post(urlPathMatching(CANCEL_PATH))
            .willReturn(aResponse().withStatus(404).withTransformers("local-test-pg-api")));
        server.stubFor(get(urlPathMatching(PAYMENT_LOOKUP_PATH + "[^/]+"))
            .willReturn(aResponse().withStatus(404).withTransformers("local-test-pg-api")));
        server.stubFor(get(urlPathMatching(ORDER_LOOKUP_PATH + "[^/]+"))
            .willReturn(aResponse().withStatus(404).withTransformers("local-test-pg-api")));
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    @DisplayName("정상 결제 확인 요청은 거래를 생성하고 DONE으로 승인한다")
    void createsAndApprovesTransaction() throws Exception {
        HttpResponse<String> response = confirmPayment("test-payment-42", "attempt-42");

        assertThat(response.statusCode()).isEqualTo(200);
        var body = objectMapper.readTree(response.body());
        assertThat(body.get("paymentKey").asText()).isEqualTo("test-payment-42");
        assertThat(body.get("orderId").asText()).isEqualTo("LP-ORD-0000000042");
        assertThat(body.get("totalAmount").asLong()).isEqualTo(7000);
        assertThat(body.get("status").asText()).isEqualTo("DONE");
        assertThat(OffsetDateTime.parse(body.get("approvedAt").asText())).isNotNull();

        var savedTransactions = transactionsForOrder("LP-ORD-0000000042");
        assertThat(savedTransactions).hasSize(1);
        assertThat(savedTransactions.get(0).get("paymentKey").asText()).isEqualTo("test-payment-42");
        assertThat(savedTransactions.get(0).get("orderId").asText()).isEqualTo("LP-ORD-0000000042");
        assertThat(savedTransactions.get(0).get("amount").asLong()).isEqualTo(7000);
        assertThat(savedTransactions.get(0).get("status").asText()).isEqualTo("SUCCESS");

        assertPaymentTransaction("test-payment-42", savedTransactions.get(0).get("transactionKey").asText(),
            "SUCCESS");
    }

    @Test
    @DisplayName("시작 시 준비된 성공·실패 거래를 paymentKey로 조회한다")
    void findsSeededSuccessfulAndFailedTransactionsByPaymentKey() throws Exception {
        assertPaymentTransaction("test-payment-success-1", "local-txn-success-1", "SUCCESS");
        assertPaymentTransaction("test-payment-success-2", "local-txn-success-2", "SUCCESS");
        assertPaymentTransaction("test-payment-failed-1", "local-txn-failed-1", "FAILED");
        assertPaymentTransaction("test-payment-failed-2", "local-txn-failed-2", "FAILED");
    }

    @Test
    @DisplayName("paymentKey로 단건 거래를 조회한다")
    void findsOneTransactionByPaymentKey() throws Exception {
        HttpResponse<String> response = paymentByKey("test-payment-success-1");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode transaction = objectMapper.readTree(response.body());
        assertThat(transaction.isObject()).isTrue();
        assertThat(transaction.get("paymentKey").asText()).isEqualTo("test-payment-success-1");
        assertThat(transaction.get("transactionKey").asText()).isEqualTo("local-txn-success-1");
        assertThat(transaction.get("status").asText()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("존재하지 않는 paymentKey 조회는 404를 반환한다")
    void returnsNotFoundForUnknownPaymentKey() throws Exception {
        HttpResponse<String> response = paymentByKey("missing-payment-key");

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("최초 카드 승인 거절은 403으로 응답하고 거래를 생성하지 않는다")
    void rejectsCardPaymentWithoutCreatingTransaction() throws Exception {
        HttpResponse<String> response = confirmPayment("test-payment-rejected", "attempt-rejected");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(objectMapper.readTree(response.body())).isEqualTo(objectMapper.readTree("""
            {
              "code": "REJECT_CARD_PAYMENT",
              "message": "한도초과 혹은 잔액부족으로 결제에 실패했습니다."
            }
            """));

        assertThat(transactionsForOrder("LP-ORD-0000000042")).isEmpty();
    }

    @DisplayName("[INV-PAYMENT-50] 같은 결제 시도 재요청은 최초 응답을 재사용해 중복 거래를 막는다.")
    @Nested
    class RepeatedIdempotencyKey {

        @Test
        @DisplayName("승인 성공 후 같은 멱등키로 재요청하면 최초 응답을 반환하고 거래를 추가 생성하지 않는다")
        void reusesSuccessfulResponseForSameIdempotencyKey() throws Exception {
            HttpResponse<String> first = confirmPayment("test-payment-42", "attempt-42");
            HttpResponse<String> repeated = confirmPayment("test-payment-42", "attempt-42");

            assertThat(first.statusCode()).isEqualTo(200);
            assertThat(repeated.statusCode()).isEqualTo(200);
            assertThat(repeated.body()).isEqualTo(first.body());
            assertThat(transactionsForOrder("LP-ORD-0000000042")).hasSize(1);
        }

        @Test
        @DisplayName("카드 승인 거절 후 같은 멱등키로 재요청하면 최초 거절 응답을 반환한다")
        void reusesRejectedResponseForSameIdempotencyKey() throws Exception {
            HttpResponse<String> first = confirmPayment("test-payment-rejected", "attempt-rejected");
            HttpResponse<String> repeated = confirmPayment("test-payment-rejected", "attempt-rejected");

            assertThat(first.statusCode()).isEqualTo(403);
            assertThat(repeated.statusCode()).isEqualTo(403);
            assertThat(repeated.body()).isEqualTo(first.body());
            assertThat(objectMapper.readTree(first.body()).get("code").asText())
                .isEqualTo("REJECT_CARD_PAYMENT");
            assertThat(transactionsForOrder("LP-ORD-0000000042")).isEmpty();
        }
    }

    @Test
    @DisplayName("PG 내부 500 응답은 FAILED_INTERNAL_SYSTEM_PROCESSING 오류로 반환한다")
    void returnsTossServerErrorResponse() throws Exception {
        HttpResponse<String> response = confirmPayment("test-payment-server-error", "attempt-server-error");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(objectMapper.readTree(response.body())).isEqualTo(objectMapper.readTree("""
            {
              "code": "FAILED_INTERNAL_SYSTEM_PROCESSING",
              "message": "내부 시스템 처리 작업이 실패했습니다. 잠시 후 다시 시도해주세요."
            }
            """));
    }

    @Test
    @DisplayName("승인된 결제를 paymentKey와 취소 사유로 전액 취소하고 취소 결과를 조회한다")
    void cancelsApprovedPaymentAndReturnsCanceledTransaction() throws Exception {
        confirmPayment("test-payment-42", "attempt-42");

        HttpResponse<String> canceled = cancelPayment("test-payment-42", "취소 사유", "attempt-42-cancel");
        JsonNode cancelBody = objectMapper.readTree(canceled.body());
        HttpResponse<String> repeated = cancelPayment("test-payment-42", "취소 사유", "attempt-42-cancel");

        assertThat(canceled.statusCode()).isEqualTo(200);
        assertThat(cancelBody.get("paymentKey").asText()).isEqualTo("test-payment-42");
        assertThat(cancelBody.get("orderId").asText()).isEqualTo("LP-ORD-0000000042");
        assertThat(cancelBody.get("totalAmount").asLong()).isEqualTo(7000);
        assertThat(cancelBody.get("status").asText()).isEqualTo("CANCELED");
        assertThat(cancelBody.get("cancels").get(0).get("cancelAmount").asLong()).isEqualTo(7000);
        assertThat(cancelBody.get("cancels").get(0).get("cancelReason").asText()).isEqualTo("취소 사유");
        assertThat(repeated.statusCode()).isEqualTo(200);
        assertThat(repeated.body()).isEqualTo(canceled.body());
        assertThat(objectMapper.readTree(paymentByKey("test-payment-42").body()).get("status").asText())
            .isEqualTo("CANCELED");
        assertThat(transactionsForOrder("LP-ORD-0000000042").get(0).get("cancels").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("결제 취소 중 PG 내부 500 응답은 실패 오류로 반환하고 거래 취소를 기록하지 않는다")
    void returnsTossServerErrorWhenCancelingPayment() throws Exception {
        String paymentKey = "test-payment-cancel-server-error";
        confirmPayment(paymentKey, "attempt-cancel-server-error");

        HttpResponse<String> response = cancelPayment(paymentKey, "취소 사유", "attempt-cancel-server-error-cancel");
        HttpResponse<String> repeated = cancelPayment(paymentKey, "취소 사유", "attempt-cancel-server-error-cancel");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(objectMapper.readTree(response.body())).isEqualTo(objectMapper.readTree("""
            {
              "code": "FAILED_INTERNAL_SYSTEM_PROCESSING",
              "message": "내부 시스템 처리 작업이 실패했습니다. 잠시 후 다시 시도해주세요."
            }
            """));
        assertThat(repeated.statusCode()).isEqualTo(500);
        assertThat(repeated.body()).isEqualTo(response.body());

        JsonNode transaction = objectMapper.readTree(paymentByKey(paymentKey).body());
        assertThat(transaction.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(transaction.get("cancels")).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 paymentKey의 취소 요청은 거래 없음 오류를 반환한다")
    void returnsNotFoundWhenCancelingUnknownPayment() throws Exception {
        HttpResponse<String> response = cancelPayment("missing-payment-key", "취소 사유", "cancel-missing");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(objectMapper.readTree(response.body()).get("code").asText()).isEqualTo("NOT_FOUND_PAYMENT");
    }

    private JsonNode transactionsForOrder(String orderId) throws Exception {
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + ORDER_LOOKUP_PATH + orderId))
                .header("X-USER-ID", "135135")
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    private void assertPaymentTransaction(String paymentKey, String transactionKey, String status) throws Exception {
        HttpResponse<String> response = paymentByKey(paymentKey);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode transaction = objectMapper.readTree(response.body());
        assertThat(transaction.get("paymentKey").asText()).isEqualTo(paymentKey);
        assertThat(transaction.get("transactionKey").asText()).isEqualTo(transactionKey);
        assertThat(transaction.get("status").asText()).isEqualTo(status);
    }

    private HttpResponse<String> paymentByKey(String paymentKey) throws Exception {
        return httpClient.send(HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + PAYMENT_LOOKUP_PATH + paymentKey))
                .header("X-USER-ID", "135135")
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> confirmPayment(String paymentKey, String idempotencyKey) throws Exception {
        String body = REQUEST_BODY.replace("test-payment-42", paymentKey);
        return httpClient.send(HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + CONFIRM_PATH))
                .header("Authorization", AUTHORIZATION)
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> cancelPayment(String paymentKey, String cancelReason, String idempotencyKey)
        throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of("cancelReason", cancelReason));
        return httpClient.send(HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/v1/payments/" + paymentKey + "/cancel"))
                .header("Authorization", AUTHORIZATION)
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
