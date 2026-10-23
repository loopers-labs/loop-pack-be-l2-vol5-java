package com.loopers.support.pg;

import com.fasterxml.jackson.databind.JsonNode;
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

    @DisplayName("callback 본문은 거래 객체 그대로다. orderId, transactionKey, amount, status")
    @Test
    void callbackBodyIsTransaction() throws Exception {
        String key = transactionKeyOf(create(ORDER_ID, 7000));
        pg.changeStatus(key, PgFixture.Status.SUCCESS);

        JsonNode body = JSON.readTree(pg.callbackBody(key));

        assertThat(body.get("transactionKey").asText()).isEqualTo(key);
        assertThat(body.get("orderId").asText()).isEqualTo(ORDER_ID);
        assertThat(body.get("amount").asLong()).isEqualTo(7000);
        assertThat(body.get("status").asText()).isEqualTo("SUCCESS");
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

    @DisplayName("공용 fixture 는 test 프로필의 PG 주소(127.0.0.1:8090)에서 하나만 뜬다")
    @Test
    void sharedListensOnTestProfileAddress() {
        assertThat(PgFixture.shared()).isSameAs(PgFixture.shared());
        assertThat(PgFixture.shared().baseUrl()).isEqualTo("https://127.0.0.1:8090");
    }

    @Nested
    @DisplayName("PG-003, PG-005: 생성 POST 는 거래를 만들고 202 PENDING 으로 접수를 알린다")
    class Create {

        @DisplayName("요청한 orderId, amount 를 그대로 돌려주고, 새 transactionKey 와 PENDING 을 준다")
        @Test
        void acceptsWithPending() throws Exception {
            HttpResponse<String> response = create(ORDER_ID, 7000);

            assertThat(response.statusCode()).isEqualTo(202);
            JsonNode body = JSON.readTree(response.body());
            assertThat(body.get("transactionKey").isTextual()).isTrue();
            assertThat(body.get("orderId").asText()).isEqualTo(ORDER_ID);
            assertThat(body.get("amount").isIntegralNumber()).isTrue();
            assertThat(body.get("amount").asLong()).isEqualTo(7000);
            assertThat(body.get("status").asText()).isEqualTo("PENDING");
            assertThat(pg.createCount()).isEqualTo(1);
        }

        @DisplayName("같은 orderId 로 다시 보내면 거래가 하나 더 생긴다 — 이 PG 는 중복을 막지 않는다")
        @Test
        void createsAnotherTransactionForSameOrderId() throws Exception {
            String first = transactionKeyOf(create(ORDER_ID, 7000));
            String second = transactionKeyOf(create(ORDER_ID, 7000));

            assertThat(second).isNotEqualTo(first);
            assertThat(pg.transactionsOf(ORDER_ID))
                .extracting(PgFixture.Transaction::transactionKey)
                .containsExactlyInAnyOrder(first, second);
            assertThat(pg.createCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("PG-007: 거래 조회는 있으면 200 거래 객체, 없으면 404 다")
    class LookupByKey {

        @DisplayName("생성한 거래를 키로 조회하면 같은 orderId, amount, status 를 돌려준다")
        @Test
        void returnsTransaction() throws Exception {
            String key = transactionKeyOf(create(ORDER_ID, 7000));

            HttpResponse<String> response = get("/api/v1/payments/" + key);

            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode body = JSON.readTree(response.body());
            assertThat(body.get("transactionKey").asText()).isEqualTo(key);
            assertThat(body.get("orderId").asText()).isEqualTo(ORDER_ID);
            assertThat(body.get("amount").isIntegralNumber()).isTrue();
            assertThat(body.get("amount").asLong()).isEqualTo(7000);
            assertThat(body.get("status").asText()).isEqualTo("PENDING");
        }

        @DisplayName("없는 키를 조회하면 404 다")
        @Test
        void unknownKeyIsNotFound() throws Exception {
            HttpResponse<String> response = get("/api/v1/payments/TEST-TX-9999");

            assertThat(response.statusCode()).isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("PG-008: 주문 조회는 같은 orderId 의 거래 배열이고, 없으면 빈 배열이다")
    class LookupByOrder {

        @DisplayName("같은 orderId 의 거래를 모두 돌려주고, 다른 주문의 거래는 섞지 않는다")
        @Test
        void returnsTransactionsOfOrder() throws Exception {
            String first = transactionKeyOf(create(ORDER_ID, 7000));
            String second = transactionKeyOf(create(ORDER_ID, 7000));
            create("LP-ORD-0000000043", 5000);

            HttpResponse<String> response = get("/api/v1/payments?orderId=" + ORDER_ID);

            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode body = JSON.readTree(response.body());
            assertThat(body.isArray()).isTrue();
            assertThat(body).extracting(node -> node.get("transactionKey").asText())
                .containsExactlyInAnyOrder(first, second);
            assertThat(body).extracting(node -> node.get("orderId").asText())
                .containsOnly(ORDER_ID);
        }

        @DisplayName("거래가 없는 주문은 404 가 아니라 200 빈 배열이다")
        @Test
        void emptyArrayWhenNoTransaction() throws Exception {
            HttpResponse<String> response = get("/api/v1/payments?orderId=LP-ORD-0000000099");

            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode body = JSON.readTree(response.body());
            assertThat(body.isArray()).isTrue();
            assertThat(body).isEmpty();
        }
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

        @DisplayName("SUCCESS 로 바꾸면 키 조회와 주문 조회가 모두 SUCCESS 를 보여 준다")
        @Test
        void lookupsShowChangedStatus() throws Exception {
            String key = transactionKeyOf(create(ORDER_ID, 7000));

            pg.changeStatus(key, PgFixture.Status.SUCCESS);

            assertThat(JSON.readTree(get("/api/v1/payments/" + key).body()).get("status").asText())
                .isEqualTo("SUCCESS");
            assertThat(JSON.readTree(get("/api/v1/payments?orderId=" + ORDER_ID).body()).get(0).get("status").asText())
                .isEqualTo("SUCCESS");
        }

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

        @DisplayName("생성 때 받은 callbackUrl 로 orderId, transactionKey, 지금 상태를 보낸다")
        @Test
        void sendsCurrentStatusToCallbackUrl() throws Exception {
            String key = transactionKeyOf(create(ORDER_ID, 7000, receiver.url()));
            pg.changeStatus(key, PgFixture.Status.SUCCESS);

            int status = pg.sendCallback(key);

            assertThat(status).isEqualTo(200);
            assertThat(receiver.bodies()).hasSize(1);
            JsonNode body = JSON.readTree(receiver.bodies().get(0));
            assertThat(body.get("orderId").asText()).isEqualTo(ORDER_ID);
            assertThat(body.get("transactionKey").asText()).isEqualTo(key);
            assertThat(body.get("status").asText()).isEqualTo("SUCCESS");
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

        @DisplayName("422 와 code, transactionCreated:false, 요청과 같은 orderId, amount 를 주고, 거래를 저장하지 않는다")
        @Test
        void rejectsWithoutTransaction() throws Exception {
            pg.rejectNextCreate();

            HttpResponse<String> response = create(ORDER_ID, 7000);

            assertThat(response.statusCode()).isEqualTo(422);
            JsonNode body = JSON.readTree(response.body());
            assertThat(body.get("code").asText()).isEqualTo("TEST_PG_REJECTED_BEFORE_CREATE");
            assertThat(body.get("transactionCreated").asBoolean()).isFalse();
            assertThat(body.get("orderId").asText()).isEqualTo(ORDER_ID);
            assertThat(body.get("amount").asLong()).isEqualTo(7000);
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

        @DisplayName("요청은 연결 오류로 끝나지만, 거래는 PENDING 으로 남아 주문 조회에서 보인다")
        @Test
        void transactionExistsWithoutResponse() throws Exception {
            pg.dropNextCreateResponse();

            assertThatThrownBy(() -> create(ORDER_ID, 7000))
                .isInstanceOf(IOException.class);

            JsonNode found = JSON.readTree(get("/api/v1/payments?orderId=" + ORDER_ID).body());
            assertThat(found).hasSize(1);
            assertThat(found.get(0).get("status").asText()).isEqualTo("PENDING");
            assertThat(pg.createCount()).isEqualTo(1);
        }

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

        @DisplayName("응답을 붙잡아 둔 동안 거래는 이미 있고, callback 을 먼저 보낼 수 있다")
        @Test
        void callbackBeforeCreateResponse() throws Exception {
            try (CallbackReceiver receiver = CallbackReceiver.start()) {
                PgFixture.HeldResponse held = pg.holdNextCreateResponse();
                CompletableFuture<HttpResponse<String>> response = createAsync(ORDER_ID, 7000, receiver.url());

                held.awaitStored();
                String key = pg.transactionsOf(ORDER_ID).get(0).transactionKey();
                int callbackStatus = pg.sendCallback(key);

                assertThat(response).isNotDone();
                assertThat(callbackStatus).isEqualTo(200);

                held.release();

                HttpResponse<String> created = response.get(5, TimeUnit.SECONDS);
                assertThat(created.statusCode()).isEqualTo(202);
                assertThat(JSON.readTree(created.body()).get("transactionKey").asText()).isEqualTo(key);
            }
        }

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

        @DisplayName("생성 요청의 메서드, 경로, X-USER-ID, Content-Type, 본문을 받은 그대로 남긴다")
        @Test
        void recordsCreateRequest() throws Exception {
            create(ORDER_ID, 7000);

            PgFixture.ReceivedRequest received = pg.receivedRequests().get(0);

            assertThat(received.method()).isEqualTo("POST");
            assertThat(received.path()).isEqualTo("/api/v1/payments");
            assertThat(received.userId()).isEqualTo(USER_ID);
            assertThat(received.contentType()).startsWith("application/json");
            assertThat(received.body().get("orderId").asText()).isEqualTo(ORDER_ID);
            assertThat(received.body().get("amount").isIntegralNumber()).isTrue();
        }

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
