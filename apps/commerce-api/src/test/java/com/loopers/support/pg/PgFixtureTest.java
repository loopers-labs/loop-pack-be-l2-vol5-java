package com.loopers.support.pg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PgFixtureTest {

    private static final String USER_ID = "135135";
    private static final String OTHER_USER_ID = "999999";
    private static final String ORDER_ID = "LP-ORD-0000000042";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
        .sslContext(PgFixture.clientSslContext())
        .build();
    private PgFixture pg;

    @BeforeEach
    void setUp() {
        pg = PgFixture.start();
    }

    @AfterEach
    void tearDown() {
        pg.close();
    }

    @DisplayName("reset 은 거래, 받은 요청, 생성 수, 다음 한 번 손잡이를 비운다. 거래 키 번호는 이어진다")
    @Test
    void resetClearsState() throws Exception {
        String before = transactionKeyOf(create(ORDER_ID, 7000));
        pg.rejectNextCreate();

        pg.reset();

        assertThat(pg.transactionsOf(ORDER_ID)).isEmpty();
        assertThat(pg.receivedRequests()).isEmpty();
        assertThat(pg.createCount()).isZero();
        String after = transactionKeyOf(create(ORDER_ID, 7000));
        assertThat(after).isNotEqualTo(before);
    }

    @Nested
    @DisplayName("PG-009: 다른 X-USER-ID 의 거래는 조회에서 보이지 않는다")
    class OtherUser {

        @DisplayName("키 조회는 404 다 — 있는 거래여도 없는 것과 같다")
        @Test
        void keyLookupIsNotFound() throws Exception {
            String key = transactionKeyOf(create(ORDER_ID, 7000));

            HttpResponse<String> response = get("/api/v1/payments/" + key, OTHER_USER_ID);

            assertThat(response.statusCode()).isEqualTo(404);
        }

        @DisplayName("주문 조회는 빈 배열이다")
        @Test
        void orderLookupIsEmpty() throws Exception {
            create(ORDER_ID, 7000);

            HttpResponse<String> response = get("/api/v1/payments?orderId=" + ORDER_ID, OTHER_USER_ID);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(response.body())).isEmpty();
        }
    }

    @Nested
    @DisplayName("PG-102: 거래 상태는 테스트가 명령할 때만 바뀐다")
    class ChangeStatus {

        @DisplayName("종결된 거래도 뒤집을 수 있다 — 종결 뒤 다른 결과를 만드는 장면이다")
        @Test
        void flipsSettledTransaction() throws Exception {
            String key = transactionKeyOf(create(ORDER_ID, 7000));
            pg.changeStatus(key, PgFixture.Status.SUCCESS);

            pg.changeStatus(key, PgFixture.Status.FAILED);

            assertThat(JSON.readTree(get("/api/v1/payments/" + key).body()).get("status").asText())
                .isEqualTo("FAILED");
        }

        @DisplayName("없는 키를 바꾸라고 하면 테스트를 잘못 쓴 것이라 바로 실패한다")
        @Test
        void unknownKeyFails() {
            assertThatThrownBy(() -> pg.changeStatus("TEST-TX-9999", PgFixture.Status.SUCCESS))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("PG-104: callback 은 테스트가 보낼 때만 한 번 가고, 받은 응답 코드를 돌려준다")
    class Callback {

        private CallbackReceiver receiver;

        @BeforeEach
        void startReceiver() throws IOException {
            receiver = CallbackReceiver.start();
        }

        @AfterEach
        void stopReceiver() {
            receiver.close();
        }

        @DisplayName("503 을 받아도 fixture 가 스스로 다시 보내지 않는다 — 재전송은 테스트가 부른다")
        @Test
        void returnsResponseCodeWithoutResending() throws Exception {
            String key = transactionKeyOf(create(ORDER_ID, 7000, receiver.url()));
            receiver.respondWith(503);

            int status = pg.sendCallback(key);

            assertThat(status).isEqualTo(503);
            assertThat(receiver.bodies()).hasSize(1);
        }

        @DisplayName("없는 키로 보내라고 하면 테스트를 잘못 쓴 것이라 바로 실패한다")
        @Test
        void unknownKeyFails() {
            assertThatThrownBy(() -> pg.sendCallback("TEST-TX-9999"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("PG-010, PG-105: 거절은 거래를 만들기 전에 끝나고, 다음 생성 한 번에만 걸린다")
    class RejectNextCreate {

        @DisplayName("422 로 거절하고 거래를 저장하지 않는다. 본문 다섯 조건은 계약 테스트가 어댑터로 본다")
        @Test
        void rejectsWithoutTransaction() throws Exception {
            pg.rejectNextCreate();

            HttpResponse<String> response = create(ORDER_ID, 7000);

            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(pg.transactionsOf(ORDER_ID)).isEmpty();
            assertThat(pg.createCount()).isEqualTo(1);
        }

        @DisplayName("거절은 한 번뿐이다. 그다음 생성은 202 로 거래를 만든다")
        @Test
        void rejectsOnlyOnce() throws Exception {
            pg.rejectNextCreate();
            create(ORDER_ID, 7000);

            HttpResponse<String> second = create(ORDER_ID, 7000);

            assertThat(second.statusCode()).isEqualTo(202);
            assertThat(pg.transactionsOf(ORDER_ID)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("PG-011: 생성 응답이 끊겨도 거래는 생겨 있을 수 있다")
    class DropNextCreateResponse {

        @DisplayName("끊김은 한 번뿐이다. 그다음 생성은 202 를 받는다")
        @Test
        void dropsOnlyOnce() throws Exception {
            pg.dropNextCreateResponse();
            assertThatThrownBy(() -> create(ORDER_ID, 7000))
                .isInstanceOf(IOException.class);

            HttpResponse<String> second = create(ORDER_ID, 7000);

            assertThat(second.statusCode()).isEqualTo(202);
            assertThat(pg.transactionsOf(ORDER_ID)).hasSize(2);
        }
    }

    @Nested
    @DisplayName("SETTLE-005: 생성 응답이 늦으면 callback 이 먼저 갈 수 있다")
    class HoldNextCreateResponse {

        @DisplayName("붙잡는 것은 한 번뿐이다. 그다음 생성은 바로 202 를 받는다")
        @Test
        void holdsOnlyOnce() throws Exception {
            PgFixture.HeldResponse held = pg.holdNextCreateResponse();
            CompletableFuture<HttpResponse<String>> first = createAsync(ORDER_ID, 7000, "http://127.0.0.1:8080/api/v1/payments/callback");
            held.awaitStored();
            held.release();
            first.get(5, TimeUnit.SECONDS);

            HttpResponse<String> second = create(ORDER_ID, 7000);

            assertThat(second.statusCode()).isEqualTo(202);
        }
    }

    @Nested
    @DisplayName("PG-103: 받은 요청을 테스트가 읽을 수 있다")
    class ReceivedRequests {

        @DisplayName("거절되거나 응답이 끊긴 생성도 받은 요청으로 남는다")
        @Test
        void recordsEvenWhenNotAccepted() throws Exception {
            pg.rejectNextCreate();
            create(ORDER_ID, 7000);

            assertThat(pg.receivedRequests()).hasSize(1);
        }
    }

    private HttpResponse<String> create(String orderId, long amount) throws Exception {
        return create(orderId, amount, "http://127.0.0.1:8080/api/v1/payments/callback");
    }

    private HttpResponse<String> create(String orderId, long amount, String callbackUrl) throws Exception {
        return http.send(createRequest(orderId, amount, callbackUrl), HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<HttpResponse<String>> createAsync(String orderId, long amount, String callbackUrl) throws Exception {
        return http.sendAsync(createRequest(orderId, amount, callbackUrl), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest createRequest(String orderId, long amount, String callbackUrl) throws Exception {
        String body = JSON.writeValueAsString(Map.of(
            "orderId", orderId,
            "cardType", "TEST",
            "cardNo", "0000-0000-0000-0000",
            "amount", amount,
            "callbackUrl", callbackUrl
        ));
        return HttpRequest.newBuilder(URI.create(pg.baseUrl() + "/api/v1/payments"))
            .header("X-USER-ID", USER_ID)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return get(path, USER_ID);
    }

    private HttpResponse<String> get(String path, String userId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(pg.baseUrl() + path))
            .header("X-USER-ID", userId)
            .GET()
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String transactionKeyOf(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(202);
        return JSON.readTree(response.body()).get("transactionKey").asText();
    }

    private static final class CallbackReceiver implements AutoCloseable {

        private final HttpServer server;
        private final List<String> bodies = new CopyOnWriteArrayList<>();
        private volatile int responseStatus = 200;

        private CallbackReceiver(HttpServer server) {
            this.server = server;
        }

        static CallbackReceiver start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            CallbackReceiver receiver = new CallbackReceiver(server);
            server.createContext("/api/v1/payments/callback", receiver::receive);
            server.start();
            return receiver;
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/payments/callback";
        }

        List<String> bodies() {
            return bodies;
        }

        void respondWith(int status) {
            responseStatus = status;
        }

        private void receive(HttpExchange exchange) throws IOException {
            try (exchange) {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(responseStatus, -1);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
