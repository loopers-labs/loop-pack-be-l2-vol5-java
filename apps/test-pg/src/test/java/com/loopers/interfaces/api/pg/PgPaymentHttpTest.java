package com.loopers.interfaces.api.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.support.PgDatabaseTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PgPaymentHttpTest extends PgDatabaseTest {
    @LocalServerPort
    int port;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    @DisplayName("생성은 202 PENDING이며 같은 주문의 두 거래와 없는 거래를 실제 HTTP로 조회한다")
    void threeEndpoints() throws Exception {
        String orderId = "HTTP-" + UUID.randomUUID();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> created = client.send(createRequest(orderId, "", Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(created.statusCode()).isEqualTo(202);
            JsonNode payment = objectMapper.readTree(created.body());
            assertThat(payment.get("status").asText()).isEqualTo("PENDING");
            assertThat(payment.get("amount").isIntegralNumber()).isTrue();
            assertThat(payment.size()).isEqualTo(4);
            String key = payment.get("transactionKey").asText();
            assertThat(get(client, "/" + key).body()).isEqualTo(created.body());
            assertThat(client.send(createRequest(orderId, "", Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(202);
            assertThat(objectMapper.readTree(get(client, "?orderId=" + orderId).body()).size()).isEqualTo(2);
            assertThat(get(client, "?orderId=NO-TRANSACTION").body()).isEqualTo("[]");
            assertThat(get(client, "/NO-TRANSACTION").statusCode()).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("생성 전 거절은 정확한 422 계약과 거래 0건을 반환한다")
    void rejectsWithoutTransaction() throws Exception {
        String orderId = "REJECT-" + UUID.randomUUID();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(createRequest(orderId,
                    ",\"testOptions\":{\"preset\":\"REJECT_BEFORE_CREATE\"}", Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode rejection = objectMapper.readTree(response.body());
            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(rejection.get("code").asText()).isEqualTo("TEST_PG_REJECTED_BEFORE_CREATE");
            assertThat(rejection.get("transactionCreated").isBoolean()).isTrue();
            assertThat(rejection.get("transactionCreated").asBoolean()).isFalse();
            assertThat(rejection.get("orderId").asText()).isEqualTo(orderId);
            assertThat(rejection.get("amount").asLong()).isEqualTo(7000);
            assertThat(get(client, "?orderId=" + orderId).body()).isEqualTo("[]");
        }
    }

    @Test
    @DisplayName("생성 응답이 유실되어도 거래는 저장되어 조회할 수 있다")
    void lostResponseStillPersists() throws Exception {
        String orderId = "LOST-" + UUID.randomUUID();
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThatThrownBy(() -> client.send(createRequest(orderId,
                            ",\"testOptions\":{\"preset\":\"LOST_SUCCESS\"}", Duration.ofSeconds(2)),
                    HttpResponse.BodyHandlers.ofString())).isInstanceOf(HttpTimeoutException.class);
            assertThat(objectMapper.readTree(get(client, "?orderId=" + orderId).body()).size()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("잘못된 계정과 외부 callback 주소는 거래를 만들기 전에 거절한다")
    void rejectsInvalidBoundaryInputs() throws Exception {
        String orderId = "INVALID-" + UUID.randomUUID();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest wrongAccount = HttpRequest.newBuilder(URI.create(baseUrl()))
                    .header("X-USER-ID", "1").GET().build();
            assertThat(client.send(wrongAccount, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
            HttpRequest badCallback = HttpRequest.newBuilder(URI.create(baseUrl()))
                    .header("X-USER-ID", "135135").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body(orderId, "").replace(
                            "http://127.0.0.1:8080/api/v1/payments/callback", "http://example.com/callback")))
                    .build();
            assertThat(client.send(badCallback, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
            assertThat(get(client, "?orderId=" + orderId).body()).isEqualTo("[]");
        }
    }

    private HttpRequest createRequest(String orderId, String options, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(baseUrl())).timeout(timeout)
                .header("X-USER-ID", "135135").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(orderId, options))).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"7000.9", "\"7000\""})
    @DisplayName("소수나 문자열 금액을 정수로 바꿔 수락하지 않는다")
    void rejectsCoercedAmount(String amount) throws Exception {
        String orderId = "AMOUNT-" + UUID.randomUUID();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl()))
                    .header("X-USER-ID", "135135").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body(orderId, "").replace(
                            "\"amount\":7000", "\"amount\":" + amount))).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(get(client, "?orderId=" + orderId).body()).isEqualTo("[]");
        }
    }

    @Test
    @DisplayName("테스트 옵션의 오타를 무시하고 정상 성공으로 실행하지 않는다")
    void rejectsUnknownScenarioField() throws Exception {
        String orderId = "TYPO-" + UUID.randomUUID();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(createRequest(orderId,
                    ",\"testOptions\":{\"presett\":\"LOST_SUCCESS\"}", Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(get(client, "?orderId=" + orderId).body()).isEqualTo("[]");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "\"1000\""})
    @DisplayName("중첩된 테스트 지연 조건도 소수나 문자열을 정수로 바꾸지 않는다")
    void rejectsCoercedDelay(String delay) throws Exception {
        String orderId = "DELAY-TYPE-" + UUID.randomUUID();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(createRequest(orderId,
                    ",\"testOptions\":{\"overrides\":{\"processingDelayMs\":" + delay + "}}",
                    Duration.ofSeconds(5)), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(get(client, "?orderId=" + orderId).body()).isEqualTo("[]");
        }
    }

    private String body(String orderId, String options) {
        return "{\"orderId\":\"" + orderId + "\",\"amount\":7000,\"cardType\":\"TEST\","
                + "\"cardNo\":\"0000-0000-0000-0000\","
                + "\"callbackUrl\":\"http://127.0.0.1:8080/api/v1/payments/callback\"" + options + "}";
    }

    private HttpResponse<String> get(HttpClient client, String suffix) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(baseUrl() + suffix)).timeout(Duration.ofSeconds(5))
                .header("X-USER-ID", "135135").GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port + "/api/v1/payments";
    }
}
