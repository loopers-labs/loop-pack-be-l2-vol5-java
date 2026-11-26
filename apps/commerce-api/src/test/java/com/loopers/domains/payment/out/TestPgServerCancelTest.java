package com.loopers.domains.payment.out;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domains.payment.out.TestPgServer.StoredTransaction;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// test-pg 취소 API는 과정 PG 계약에 없는 가정이다(w10s5-3): POST /api/v1/payments/{transactionKey}/cancel, 본문 {orderId, amount}.
class TestPgServerCancelTest {
    private static final String USER_ID = "135135";
    private static final String ORDER_ID = "LP-ORD-0000000201";
    private static final String OTHER_ORDER_ID = "LP-ORD-0000000202";
    private static final long AMOUNT = 5_000;
    private static final Duration SERVER_DELAY = Duration.ofMillis(1_500);
    private static final Duration CLIENT_TIMEOUT = Duration.ofMillis(400);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private TestPgServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new TestPgServer(USER_ID, SERVER_DELAY);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        server.close();
    }

    @Test
    @DisplayName("SUCCESS 거래를 같은 주문·금액으로 취소하면 200과 CANCELED 거래를 돌려주고 저장 상태를 CANCELED로 바꾼다")
    void cancelsSuccessTransaction() throws Exception {
        String key = server.addTransaction(ORDER_ID, AMOUNT, "SUCCESS");

        HttpResponse<String> response = cancel(key, ORDER_ID, AMOUNT);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("transactionKey").asText()).isEqualTo(key);
        assertThat(body.path("orderId").asText()).isEqualTo(ORDER_ID);
        assertThat(body.path("amount").isIntegralNumber()).isTrue();
        assertThat(body.path("amount").asLong()).isEqualTo(AMOUNT);
        assertThat(body.path("status").asText()).isEqualTo("CANCELED");
        assertThat(server.transactions()).containsExactly(new StoredTransaction(key, ORDER_ID, AMOUNT, "CANCELED"));
    }

    @Test
    @DisplayName("이미 CANCELED인 거래를 다시 취소하면 같은 200 응답을 돌려주고 상태는 CANCELED로 남는다")
    void repeatsSameResultForCanceledTransaction() throws Exception {
        String key = server.addTransaction(ORDER_ID, AMOUNT, "SUCCESS");
        HttpResponse<String> first = cancel(key, ORDER_ID, AMOUNT);

        HttpResponse<String> second = cancel(key, ORDER_ID, AMOUNT);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(second.body())).isEqualTo(objectMapper.readTree(first.body()));
        assertThat(server.transactions()).containsExactly(new StoredTransaction(key, ORDER_ID, AMOUNT, "CANCELED"));
    }

    @Test
    @DisplayName("없는 거래 키로 취소하면 404로 답하고 거래를 만들지 않는다")
    void returnsNotFoundForUnknownKey() throws Exception {
        HttpResponse<String> response = cancel("TR-404", ORDER_ID, AMOUNT);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(server.transactions()).isEmpty();
    }

    @ParameterizedTest(name = "요청 주문={0}, 요청 금액={1}")
    @CsvSource({"LP-ORD-0000000202, 5000", "LP-ORD-0000000201, 5001"})
    @DisplayName("주문·금액이 거래와 다르면 422 효과 없음 코드로 거절하고 거래를 SUCCESS로 둔다")
    void rejectsMismatchWithoutEffect(String requestOrderId, long requestAmount) throws Exception {
        String key = server.addTransaction(ORDER_ID, AMOUNT, "SUCCESS");

        HttpResponse<String> response = cancel(key, requestOrderId, requestAmount);

        assertThat(response.statusCode()).isEqualTo(422);
        assertRejectedNoEffect(response, key, requestOrderId, requestAmount);
        assertThat(server.transactions()).containsExactly(new StoredTransaction(key, ORDER_ID, AMOUNT, "SUCCESS"));
    }

    @ParameterizedTest(name = "거래 상태={0}")
    @ValueSource(strings = {"PENDING", "FAILED"})
    @DisplayName("승인되지 않은 거래를 취소하면 422 효과 없음 코드로 거절하고 상태를 바꾸지 않는다")
    void rejectsNotApprovedTransactionWithoutEffect(String status) throws Exception {
        String key = server.addTransaction(ORDER_ID, AMOUNT, status);

        HttpResponse<String> response = cancel(key, ORDER_ID, AMOUNT);

        assertThat(response.statusCode()).isEqualTo(422);
        assertRejectedNoEffect(response, key, ORDER_ID, AMOUNT);
        assertThat(server.transactions()).containsExactly(new StoredTransaction(key, ORDER_ID, AMOUNT, status));
    }

    @Test
    @DisplayName("취소 호출 수는 create·조회 호출 수와 섞이지 않고 주문별로도 센다")
    void countsCancelCallsSeparately() throws Exception {
        String key = server.addTransaction(ORDER_ID, AMOUNT, "SUCCESS");
        String otherKey = server.addTransaction(OTHER_ORDER_ID, AMOUNT, "SUCCESS");

        cancel(key, ORDER_ID, AMOUNT);
        cancel(key, ORDER_ID, AMOUNT);
        cancel(otherKey, OTHER_ORDER_ID, AMOUNT);

        assertThat(server.cancelCalls()).isEqualTo(3);
        assertThat(server.cancelCalls(ORDER_ID)).isEqualTo(2);
        assertThat(server.cancelCalls(OTHER_ORDER_ID)).isEqualTo(1);
        assertThat(server.createCalls()).isZero();
        assertThat(server.createCalls(ORDER_ID)).isZero();
        assertThat(server.duplicateCreateOrderIds()).isEmpty();
        assertThat(server.lookupCalls()).isZero();
    }

    @Test
    @DisplayName("취소 응답 유실을 지정한 주문은 거래를 CANCELED로 바꾼 뒤 응답이 제한 시간 안에 오지 않는다")
    void losesCancelResponseAfterCancel() throws Exception {
        String key = server.addTransaction(ORDER_ID, AMOUNT, "SUCCESS");
        server.loseCancelResponse(ORDER_ID);

        assertThatThrownBy(() -> cancel(key, ORDER_ID, AMOUNT)).isInstanceOf(HttpTimeoutException.class);
        assertThat(server.transactions()).containsExactly(new StoredTransaction(key, ORDER_ID, AMOUNT, "CANCELED"));
        assertThat(server.lostCancelOrderIds()).containsExactly(ORDER_ID);
        assertThat(server.cancelCalls(ORDER_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("취소 응답 유실을 해제하면 다음 취소는 즉시 200으로 답하고 create 응답 유실 지정에는 영향이 없다")
    void clearsLostCancelOnly() throws Exception {
        String key = server.addTransaction(ORDER_ID, AMOUNT, "SUCCESS");
        server.loseCancelResponse(ORDER_ID);
        server.loseResponseAfterSuccess(ORDER_ID);

        server.clearLostCancel(ORDER_ID);
        HttpResponse<String> response = cancel(key, ORDER_ID, AMOUNT);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(server.lostCancelOrderIds()).isEmpty();
        assertThat(server.lostResponseOrderIds()).containsExactly(ORDER_ID);
    }

    @Test
    @DisplayName("다른 주문의 취소는 유실 지정과 무관하게 즉시 200으로 답한다")
    void otherOrderIsNotAffectedByLostCancel() throws Exception {
        String otherKey = server.addTransaction(OTHER_ORDER_ID, AMOUNT, "SUCCESS");
        server.loseCancelResponse(ORDER_ID);

        HttpResponse<String> response = cancel(otherKey, OTHER_ORDER_ID, AMOUNT);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(response.body()).path("status").asText()).isEqualTo("CANCELED");
    }

    private void assertRejectedNoEffect(HttpResponse<String> response, String key, String orderId, long amount)
        throws IOException {
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo(TestPgServer.CANCEL_REJECT_CODE);
        assertThat(body.path("transactionKey").asText()).isEqualTo(key);
        assertThat(body.path("orderId").asText()).isEqualTo(orderId);
        assertThat(body.path("amount").asLong()).isEqualTo(amount);
        assertThat(body.path("canceled").isBoolean()).isTrue();
        assertThat(body.path("canceled").booleanValue()).isFalse();
    }

    private HttpResponse<String> cancel(String key, String orderId, long amount) throws Exception {
        String body = "{\"orderId\":\"" + orderId + "\",\"amount\":" + amount + "}";
        HttpRequest request = HttpRequest.newBuilder(server.baseUrl().resolve(TestPgServer.PAYMENTS_PATH + "/" + key + "/cancel"))
            .timeout(CLIENT_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("X-USER-ID", USER_ID)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
