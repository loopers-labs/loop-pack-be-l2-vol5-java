package com.loopers.testpg.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "test-pg.control.enabled=true",
        "test-pg.processing-mode=MANUAL",
        "test-pg.drop-response-delay=2s"
    }
)
class LookupControlHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final String CONTROL = "/test-control/v1/lookup";

    @LocalServerPort
    private int port;
    private String orderId;

    @BeforeEach
    void startNormally() throws Exception {
        orderId = "LP-ORD-" + UUID.randomUUID();
        assertThat(configure("{\"delayMillis\":0}", "135135").statusCode()).isEqualTo(204);
    }

    @AfterEach
    void restoreNormal() throws Exception {
        configure("{\"delayMillis\":0}", "135135");
    }

    @AfterAll
    static void closeClient() {
        CLIENT.close();
    }

    @DisplayName("조회 지연의 기본값은 0ms이고 두 조회 API의 기존 계약을 유지한다.")
    @Test
    void startsWithNormalLookupResponses() throws Exception {
        assertThat(setting()).isZero();
        String key = create();

        assertThat(get("/api/v1/payments/" + key, Duration.ofSeconds(1)).statusCode()).isEqualTo(200);
        JsonNode byOrder = JSON.readTree(get("/api/v1/payments?orderId=" + orderId, Duration.ofSeconds(1)).body());
        assertThat(byOrder).hasSize(1);
        assertThat(byOrder.get(0).path("transactionKey").asText()).isEqualTo(key);
        assertThat(byOrder.get(0).path("status").asText()).isEqualTo("PENDING");
    }

    @DisplayName("두 조회 API를 지연시켜 timeout을 만들고, 제어 API로 정상 복구해도 거래·금액·결과를 유지한다.")
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void delaysLookupsAndRecoversWithoutChangingTransactions(boolean byOrder) throws Exception {
        String key = create();
        assertThat(configure("{\"delayMillis\":1500}", "135135").statusCode()).isEqualTo(204);
        assertThat(setting()).isEqualTo(1500);
        String path = byOrder ? "/api/v1/payments?orderId=" + orderId : "/api/v1/payments/" + key;

        assertThatThrownBy(() -> get(path, Duration.ofMillis(200))).isInstanceOf(HttpTimeoutException.class);
        JsonNode inFlightStats = JSON.readTree(get("/test-control/v1/orders/" + orderId + "/stats", Duration.ofSeconds(1)).body());
        assertThat(inFlightStats.path("lookupRequests").asInt()).isEqualTo(1);
        assertThat(send("POST", "/test-control/v1/orders/" + orderId + "/complete", null, "135135",
            Duration.ofSeconds(1)).statusCode()).isEqualTo(200);

        // 제어 API는 조회 지연의 영향을 받지 않으며, PG 재시작 없이 새 조회를 정상으로 되돌린다.
        assertThat(configure("{\"delayMillis\":0}", "135135").statusCode()).isEqualTo(204);
        JsonNode restored = JSON.readTree(get("/api/v1/payments/" + key, Duration.ofSeconds(1)).body());
        assertThat(restored.path("transactionKey").asText()).isEqualTo(key);
        assertThat(restored.path("amount").asLong()).isEqualTo(7000);
        assertThat(restored.path("status").asText()).isEqualTo("SUCCESS");
        JsonNode stats = JSON.readTree(get("/test-control/v1/orders/" + orderId + "/stats", Duration.ofSeconds(1)).body());
        assertThat(stats.path("transactions").asInt()).isEqualTo(1);
        assertThat(stats.path("createRequests").asInt()).isEqualTo(1);

    }

    @DisplayName("조회 지연 중에도 새 결제 접수는 지연하지 않는다.")
    @Test
    void doesNotDelayCreation() throws Exception {
        configure("{\"delayMillis\":1500}", "135135");

        assertThat(create()).isNotBlank();
    }

    @DisplayName("지정한 횟수만 조회 503을 반환한 뒤 같은 거래 조회가 회복된다.")
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failsConfiguredLookupsThenRecovers(boolean byOrder) throws Exception {
        String key = create();
        assertThat(configure("{\"delayMillis\":0,\"failureCount\":2}", "135135").statusCode()).isEqualTo(204);
        String path = byOrder ? "/api/v1/payments?orderId=" + orderId : "/api/v1/payments/" + key;

        assertThat(get(path, Duration.ofSeconds(1)).statusCode()).isEqualTo(503);
        assertThat(get(path, Duration.ofSeconds(1)).statusCode()).isEqualTo(503);
        HttpResponse<String> recovered = get(path, Duration.ofSeconds(1));
        assertThat(recovered.statusCode()).isEqualTo(200);
        JsonNode transaction = JSON.readTree(recovered.body());
        if (byOrder) {
            transaction = transaction.get(0);
        }
        assertThat(transaction.path("transactionKey").asText()).isEqualTo(key);
        assertThat(transaction.path("status").asText()).isEqualTo("PENDING");
        JsonNode stats = JSON.readTree(get("/test-control/v1/orders/" + orderId + "/stats", Duration.ofSeconds(1)).body());
        assertThat(stats.path("lookupRequests").asInt()).isEqualTo(3);
        assertThat(stats.path("createRequests").asInt()).isEqualTo(1);
        assertThat(stats.path("transactions").asInt()).isEqualTo(1);
    }

    @DisplayName("조회 실패 설정의 잘못된 횟수와 상태는 기존 설정을 바꾸지 않는다.")
    @ParameterizedTest
    @ValueSource(strings = {"{\"delayMillis\":0,\"failureCount\":-1}", "{\"delayMillis\":0,\"failureCount\":10001}",
        "{\"delayMillis\":0,\"failureCount\":1.5}", "{\"delayMillis\":0,\"failureCount\":\"1\"}",
        "{\"delayMillis\":0,\"failureCount\":1,\"failureStatus\":200}"})
    void rejectsInvalidFailureSetting(String body) throws Exception {
        configure("{\"delayMillis\":100}", "135135");

        assertThat(configure(body, "135135").statusCode()).isEqualTo(400);
        assertThat(setting()).isEqualTo(100);
    }

    @DisplayName("지연 값 누락·타입·범위 오류는 400이고 기존 지연 설정을 유지한다.")
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"delayMillis\":null}", "{\"delayMillis\":-1}", "{\"delayMillis\":60001}",
        "{\"delayMillis\":1.5}", "{\"delayMillis\":\"100\"}"})
    void rejectsInvalidDelayWithoutChangingSettings(String body) throws Exception {
        configure("{\"delayMillis\":100}", "135135");

        assertThat(configure(body, "135135").statusCode()).isEqualTo(400);
        assertThat(setting()).isEqualTo(100);
    }

    @DisplayName("조회 제어 API도 제공자 헤더를 검사하고 거절 시 설정을 바꾸지 않는다.")
    @Test
    void rejectsUnauthorizedControlRequests() throws Exception {
        assertThat(configure("{\"delayMillis\":1500}", "other").statusCode()).isEqualTo(401);
        assertThat(configure("{\"delayMillis\":1500}", null).statusCode()).isEqualTo(400);
        assertThat(send("GET", CONTROL, null, "other", Duration.ofSeconds(1)).statusCode()).isEqualTo(401);
        assertThat(setting()).isZero();
    }

    private String create() throws Exception {
        String body = "{\"orderId\":\"%s\",\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\","
            + "\"amount\":7000,\"callbackUrl\":\"http://127.0.0.1:9/callback\"}";
        HttpResponse<String> response = send("POST", "/api/v1/payments", body.formatted(orderId), "135135", Duration.ofSeconds(1));
        assertThat(response.statusCode()).isEqualTo(202);
        return JSON.readTree(response.body()).path("transactionKey").asText();
    }

    private long setting() throws Exception {
        HttpResponse<String> response = get(CONTROL, Duration.ofSeconds(1));
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body()).path("delayMillis").asLong();
    }

    private HttpResponse<String> configure(String body, String account) throws Exception {
        return send("PUT", CONTROL, body, account, Duration.ofSeconds(1));
    }

    private HttpResponse<String> get(String path, Duration timeout) throws Exception {
        return send("GET", path, null, "135135", timeout);
    }

    private HttpResponse<String> send(String method, String path, String body, String account, Duration timeout) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(timeout);
        if (account != null) {
            builder.header("X-USER-ID", account);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return CLIENT.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
