package com.loopers.domains.payment.out;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domains.payment.out.TestPgServer.CreateBehavior;
import com.loopers.domains.payment.out.TestPgServer.StoredTransaction;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

// 사람 흐름 리허설에서 test-pg를 고정 포트로 띄우고 실제 HTTP로 장애를 지정하는 제어 endpoint를 확인한다.
class TestPgControlServerTest {
    private static final String USER_ID = "135135";
    private static final String ORDER_ID = "LP-ORD-0000000077";
    private static final long AMOUNT = 5_000;
    private static final Duration SERVER_DELAY = Duration.ofMillis(1_500);
    private static final Duration CLIENT_TIMEOUT = Duration.ofMillis(400);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private TestPgServer testPg;
    private TestPgControlServer control;

    @BeforeEach
    void setUp() throws IOException {
        testPg = new TestPgServer(USER_ID, SERVER_DELAY, 0);
        control = new TestPgControlServer(testPg, 0);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        control.close();
        testPg.close();
    }

    @Test
    @DisplayName("주문 단위 유실을 지정하면 create 응답은 제한 시간 안에 오지 않고 거래는 SUCCESS로 저장된다")
    void losesResponseAfterSuccessWhenMarked() throws Exception {
        HttpResponse<String> marked = control("PUT", "/control/lost-responses/" + ORDER_ID, null);

        assertThat(marked.statusCode()).isEqualTo(204);
        assertThatThrownBy(() -> create(ORDER_ID)).isInstanceOf(HttpTimeoutException.class);
        assertThat(testPg.transactions())
            .extracting(StoredTransaction::orderId, StoredTransaction::status)
            .containsExactly(tuple(ORDER_ID, "SUCCESS"));
    }

    @Test
    @DisplayName("유실 지정을 해제하면 create는 전역 동작대로 즉시 PENDING을 돌려준다")
    void clearsLostResponse() throws Exception {
        control("PUT", "/control/lost-responses/" + ORDER_ID, null);

        HttpResponse<String> cleared = control("DELETE", "/control/lost-responses/" + ORDER_ID, null);
        HttpResponse<String> created = create(ORDER_ID);

        assertThat(cleared.statusCode()).isEqualTo(204);
        assertThat(created.statusCode()).isEqualTo(202);
        assertThat(objectMapper.readTree(created.body()).path("status").asText()).isEqualTo("PENDING");
        assertThat(testPg.lostResponseOrderIds()).isEmpty();
    }

    @Test
    @DisplayName("전역 create 동작을 지정하면 이후 create가 그 동작으로 답한다")
    void changesCreateBehavior() throws Exception {
        HttpResponse<String> changed = control("PUT", "/control/create-behavior", "{\"behavior\":\"ACCEPT_SUCCESS\"}");
        HttpResponse<String> created = create(ORDER_ID);

        assertThat(changed.statusCode()).isEqualTo(204);
        assertThat(testPg.createBehavior()).isEqualTo(CreateBehavior.ACCEPT_SUCCESS);
        assertThat(objectMapper.readTree(created.body()).path("status").asText()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("없는 create 동작 이름이면 400으로 거절하고 기존 동작을 유지한다")
    void rejectsUnknownCreateBehavior() throws Exception {
        HttpResponse<String> changed = control("PUT", "/control/create-behavior", "{\"behavior\":\"NOT_A_BEHAVIOR\"}");

        assertThat(changed.statusCode()).isEqualTo(400);
        assertThat(testPg.createBehavior()).isEqualTo(CreateBehavior.ACCEPT_PENDING);
    }

    @Test
    @DisplayName("상태 조회는 create·조회 호출 수, 현재 create 동작, 유실 지정 주문 목록을 돌려준다")
    void reportsStatus() throws Exception {
        control("PUT", "/control/lost-responses/LP-ORD-0000000002", null);
        control("PUT", "/control/lost-responses/LP-ORD-0000000001", null);
        create(ORDER_ID);
        lookup(ORDER_ID);

        HttpResponse<String> status = control("GET", "/control/status", null);

        assertThat(status.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(status.body());
        assertThat(body.path("createCalls").asLong()).isEqualTo(1);
        assertThat(body.path("lookupCalls").asLong()).isEqualTo(1);
        assertThat(body.path("createBehavior").asText()).isEqualTo("ACCEPT_PENDING");
        assertThat(objectMapper.convertValue(body.path("lostResponseOrderIds"), List.class))
            .containsExactly("LP-ORD-0000000001", "LP-ORD-0000000002");
    }

    @Test
    @DisplayName("없는 제어 경로는 404로 답한다")
    void rejectsUnknownControlPath() throws Exception {
        HttpResponse<String> response = control("POST", "/control/unknown", null);

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("결제 서버와 제어 서버는 지정한 포트에서 127.0.0.1에만 묶인다")
    void bindsLoopbackOnGivenPorts() throws Exception {
        int paymentPort = freePort();
        int controlPort = freePort();
        try (TestPgServer fixedPg = new TestPgServer(USER_ID, SERVER_DELAY, paymentPort);
             TestPgControlServer fixedControl = new TestPgControlServer(fixedPg, controlPort)) {
            assertThat(fixedPg.baseUrl()).isEqualTo(URI.create("http://127.0.0.1:" + paymentPort));
            assertThat(fixedControl.baseUrl()).isEqualTo(URI.create("http://127.0.0.1:" + controlPort));
            assertThat(fixedControl.address().getAddress().isLoopbackAddress()).isTrue();
        }
    }

    private HttpResponse<String> control(String method, String path, String body) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(control.baseUrl().resolve(path))
            .timeout(Duration.ofSeconds(2))
            .header("Content-Type", "application/json")
            .method(method, publisher)
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> create(String orderId) throws Exception {
        String body = "{\"orderId\":\"" + orderId + "\",\"amount\":" + AMOUNT + ",\"cardType\":\"SAMSUNG\"}";
        HttpRequest request = HttpRequest.newBuilder(testPg.baseUrl().resolve(TestPgServer.PAYMENTS_PATH))
            .timeout(CLIENT_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("X-USER-ID", USER_ID)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> lookup(String orderId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                testPg.baseUrl().resolve(TestPgServer.PAYMENTS_PATH + "?orderId=" + orderId))
            .timeout(Duration.ofSeconds(2))
            .header("X-USER-ID", USER_ID)
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
