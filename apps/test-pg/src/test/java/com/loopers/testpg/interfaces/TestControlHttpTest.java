package com.loopers.testpg.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
class TestControlHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final BlockingQueue<JsonNode> CALLBACKS = new LinkedBlockingQueue<>();
    private static HttpServer receiver;
    private static ExecutorService receiverThreads;

    @LocalServerPort
    private int port;
    private String orderId;

    @BeforeAll
    static void startCallbackReceiver() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiverThreads = Executors.newVirtualThreadPerTaskExecutor();
        receiver.setExecutor(receiverThreads);
        receiver.createContext("/callback", exchange -> {
            try (exchange) {
                CALLBACKS.add(JSON.readTree(exchange.getRequestBody()));
                exchange.sendResponseHeaders(200, -1);
            }
        });
        receiver.start();
    }

    @AfterAll
    static void stopCallbackReceiver() {
        receiver.stop(0);
        receiverThreads.close();
        CLIENT.close();
    }

    @BeforeEach
    void isolateOrder() {
        orderId = "LP-ORD-0000000042-" + UUID.randomUUID().toString().substring(0, 8);
        CALLBACKS.clear();
    }

    @DisplayName("수동 처리 모드는 접수만 하고, complete 요청 때 기본 성공으로 처리해 callback을 한 번 보낸다.")
    @Test
    void completesOnlyWhenRequestedInManualMode() throws Exception {
        String key = JSON.readTree(create().body()).path("transactionKey").asText();
        assertThat(status(key)).isEqualTo("PENDING");
        assertThat(CALLBACKS.poll(300, TimeUnit.MILLISECONDS)).isNull();

        HttpResponse<String> completed = send("POST", "/test-control/v1/orders/" + orderId + "/complete", null, "135135");

        assertThat(completed.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(completed.body()).path("completed").asInt()).isEqualTo(1);
        JsonNode callback = CALLBACKS.poll(3, TimeUnit.SECONDS);
        assertThat(callback.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(status(key)).isEqualTo("SUCCESS");
        assertThat(CALLBACKS.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @DisplayName("FAIL 시나리오는 complete 때 거래를 FAILED로 처리하고 같은 결과를 callback과 조회로 제공한다.")
    @Test
    void failsTransactionByScenario() throws Exception {
        assertThat(register("{\"scenario\":\"FAIL\"}").statusCode()).isEqualTo(204);
        String key = JSON.readTree(create().body()).path("transactionKey").asText();

        complete();

        assertThat(CALLBACKS.poll(3, TimeUnit.SECONDS).path("status").asText()).isEqualTo("FAILED");
        assertThat(status(key)).isEqualTo("FAILED");
    }

    @DisplayName("생성 전 확정 거절은 계약의 422 본문을 반환하고 거래를 만들지 않는다.")
    @Test
    void rejectsBeforeCreatingWithContractBody() throws Exception {
        register("{\"scenario\":\"REJECT_BEFORE_CREATE\"}");

        HttpResponse<String> response = create();

        assertThat(response.statusCode()).isEqualTo(422);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo("TEST_PG_REJECTED_BEFORE_CREATE");
        assertThat(body.path("orderId").asText()).isEqualTo(orderId);
        assertThat(body.path("amount").isIntegralNumber()).isTrue();
        assertThat(body.path("amount").asLong()).isEqualTo(7_000L);
        assertThat(body.path("transactionCreated").isBoolean()).isTrue();
        assertThat(body.path("transactionCreated").asBoolean()).isFalse();
        assertThat(stats().path("transactions").asInt()).isZero();
        assertThat(stats().path("createRequests").asInt()).isEqualTo(1);
    }

    @DisplayName("응답 유실은 거래를 저장한 뒤 응답을 늦춰 호출자의 timeout을 만들고, 주문 조회로 거래를 찾을 수 있다.")
    @Test
    void dropsResponseAfterSavingTransaction() throws Exception {
        register("{\"scenario\":\"DROP_RESPONSE\"}");

        assertThatThrownBy(() -> CLIENT.send(createRequest(Duration.ofMillis(500)), HttpResponse.BodyHandlers.ofString()))
            .isInstanceOf(HttpTimeoutException.class);

        JsonNode found = JSON.readTree(get("?orderId=" + orderId).body());
        assertThat(found).hasSize(1);
        assertThat(found.get(0).path("status").asText()).isEqualTo("PENDING");
    }

    @DisplayName("응답 대체는 지정한 상태와 원문 본문을 그대로 반환하고, 거래 생성 여부를 따른다.")
    @Test
    void overridesCreateResponse() throws Exception {
        register("{\"scenario\":\"CREATE_RESPONSE_OVERRIDE\",\"responseStatus\":422,\"responseBody\":\"\","
            + "\"createTransaction\":false}");
        HttpResponse<String> emptyRejection = create();
        assertThat(emptyRejection.statusCode()).isEqualTo(422);
        assertThat(emptyRejection.body()).isEmpty();
        assertThat(stats().path("transactions").asInt()).isZero();

        register("{\"scenario\":\"CREATE_RESPONSE_OVERRIDE\",\"responseStatus\":202,"
            + "\"responseBody\":\"{\\\"broken\\\"\",\"createTransaction\":true}");
        HttpResponse<String> broken = create();
        assertThat(broken.statusCode()).isEqualTo(202);
        assertThat(broken.body()).isEqualTo("{\"broken\"");
        assertThat(stats().path("transactions").asInt()).isEqualTo(1);
    }

    @DisplayName("SKIP_CALLBACK은 결과만 저장하고 callback을 보내지 않으며, DUPLICATE_CALLBACK은 같은 callback을 두 번 보낸다.")
    @Test
    void controlsCallbackDelivery() throws Exception {
        register("{\"scenario\":\"SKIP_CALLBACK\"}");
        String skippedKey = JSON.readTree(create().body()).path("transactionKey").asText();
        complete();
        assertThat(CALLBACKS.poll(500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(status(skippedKey)).isEqualTo("SUCCESS");

        orderId = "LP-ORD-0000000043-" + UUID.randomUUID().toString().substring(0, 8);
        register("{\"scenario\":\"DUPLICATE_CALLBACK\"}");
        String duplicatedKey = JSON.readTree(create().body()).path("transactionKey").asText();
        complete();
        assertThat(CALLBACKS.poll(3, TimeUnit.SECONDS).path("transactionKey").asText()).isEqualTo(duplicatedKey);
        assertThat(CALLBACKS.poll(3, TimeUnit.SECONDS).path("transactionKey").asText()).isEqualTo(duplicatedKey);
        assertThat(stats().path("callbackAttempts").asInt()).isEqualTo(2);
    }

    @DisplayName("주문의 생성·조회 요청 수, callback 시도 수와 거래 수를 반환한다.")
    @Test
    void returnsOrderStats() throws Exception {
        String transactionKey = JSON.readTree(create().body()).path("transactionKey").asText();
        get("?orderId=" + orderId);
        get("/" + transactionKey);
        complete();
        CALLBACKS.poll(3, TimeUnit.SECONDS);

        JsonNode stats = stats();

        assertThat(stats.path("createRequests").asInt()).isEqualTo(1);
        assertThat(stats.path("lookupRequests").asInt()).isEqualTo(2);
        assertThat(stats.path("callbackAttempts").asInt()).isEqualTo(1);
        assertThat(stats.path("transactions").asInt()).isEqualTo(1);
    }

    @DisplayName("제어 API도 제공자 헤더가 필요하고, 주문번호 규칙과 시나리오 입력을 검사한다.")
    @Test
    void validatesControlRequests() throws Exception {
        assertThat(send("PUT", "/test-control/v1/orders/" + orderId + "/scenario", "{\"scenario\":\"FAIL\"}", "other")
            .statusCode()).isEqualTo(401);
        assertThat(send("PUT", "/test-control/v1/orders/bad id/scenario".replace(" ", "%20"),
            "{\"scenario\":\"FAIL\"}", "135135").statusCode()).isEqualTo(400);
        assertThat(register("{\"scenario\":\"UNKNOWN_SCENARIO\"}").statusCode()).isEqualTo(400);
        assertThat(register("{\"scenario\":\"CREATE_RESPONSE_OVERRIDE\"}").statusCode()).isEqualTo(400);
        assertThat(send("POST", "/test-control/v1/orders/" + orderId + "/complete", null, "other").statusCode())
            .isEqualTo(401);
        assertThat(send("GET", "/test-control/v1/orders/" + orderId + "/stats", null, "other").statusCode())
            .isEqualTo(401);
    }

    private HttpResponse<String> register(String body) throws Exception {
        return send("PUT", "/test-control/v1/orders/" + orderId + "/scenario", body, "135135");
    }

    private void complete() throws Exception {
        assertThat(send("POST", "/test-control/v1/orders/" + orderId + "/complete", null, "135135").statusCode())
            .isEqualTo(200);
    }

    private JsonNode stats() throws Exception {
        return JSON.readTree(send("GET", "/test-control/v1/orders/" + orderId + "/stats", null, "135135").body());
    }

    private String status(String transactionKey) throws Exception {
        return JSON.readTree(get("/" + transactionKey).body()).path("status").asText();
    }

    private HttpResponse<String> create() throws Exception {
        return CLIENT.send(createRequest(Duration.ofSeconds(5)), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest createRequest(Duration timeout) throws Exception {
        ObjectNode body = JSON.createObjectNode().put("orderId", orderId).put("cardType", "TEST")
            .put("cardNo", "0000-0000-0000-0000").put("amount", 7_000L)
            .put("callbackUrl", "http://127.0.0.1:" + receiver.getAddress().getPort() + "/callback");
        return HttpRequest.newBuilder(URI.create(base() + "/api/v1/payments")).timeout(timeout)
            .header("Content-Type", "application/json").header("X-USER-ID", "135135")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
    }

    private HttpResponse<String> get(String suffix) throws Exception {
        return send("GET", "/api/v1/payments" + suffix, null, "135135");
    }

    private HttpResponse<String> send(String method, String path, String body, String accountId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(5))
            .header("X-USER-ID", accountId);
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }
}
