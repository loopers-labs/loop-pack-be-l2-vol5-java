package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.PaymentGatewayResult;
import com.loopers.application.order.PaymentGatewayResult.Outcome;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PgClient 의 HTTP 응답 → 결제 결과 변환. PG 서버는 JDK HttpServer 로 띄운 가짜 (Spring 없음).
 * 결제 요청은 연결 실패만 NOT_SENT·5xx 와 read timeout 은 ERROR, 거래 조회는 어떤 실패도 UNKNOWN 인지 본다.
 */
class PgClientTest {
    private static final Duration READ_TIMEOUT = Duration.ofMillis(600);
    private static final Duration LOOKUP_READ_TIMEOUT = Duration.ofMillis(200);
    private static final String CALLBACK_URL = "http://localhost:8080/api/v1/payments/callback";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HttpServer server;
    private ExecutorService serverExecutor;
    private PgClient client;
    private final AtomicReference<Received> received = new AtomicReference<>();
    private volatile Responder responder;

    /** 가짜 PG 가 받은 요청. */
    private record Received(String method, String path, String query, String userId, String body) {
    }

    @FunctionalInterface
    private interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/api/v1/payments", exchange -> {
            received.set(new Received(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getQuery(),
                exchange.getRequestHeaders().getFirst("X-USER-ID"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            responder.respond(exchange);
        });
        server.start();
        client = clientFor(server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    private static PgClient clientFor(int port) {
        return new PgClient(RestClient.builder(),
            new PgProperties("http://localhost:" + port, CALLBACK_URL, Duration.ofSeconds(1), READ_TIMEOUT, LOOKUP_READ_TIMEOUT));
    }

    /** 아무도 듣지 않는 포트의 클라이언트 (연결 거부). */
    private static PgClient clientForClosedPort() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        return clientFor(port);
    }

    private void respond(int status, String body) {
        responder = exchange -> write(exchange, status, body);
    }

    private void respondAfterReadTimeout() {
        respondAfter(READ_TIMEOUT.multipliedBy(3));
    }

    /** delay 뒤 200 SUCCESS. */
    private void respondAfter(Duration delay) {
        responder = exchange -> {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            write(exchange, 200, transaction("SUCCESS"));
        };
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String transaction(String status) {
        return "{\"transactionKey\":\"tx-1\",\"orderId\":\"ORDER-7\",\"amount\":3000,\"status\":\"" + status + "\"}";
    }

    private PaymentGatewayResult request() {
        return client.request(1L, 7L, "SAMSUNG", "1234-5678-9814-1451", 3_000L);
    }

    @Nested
    @DisplayName("결제 요청 POST /api/v1/payments")
    class Request {
        @DisplayName("PG 주문 ID·카드 정보·금액·callback URL 을 본문에, 사용자 ID 를 X-USER-ID 헤더에 싣는다.")
        @Test
        void sendsPaymentRequest() throws IOException {
            respond(200, transaction("PENDING"));

            request();

            Received request = received.get();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/api/v1/payments");
            assertThat(request.userId()).isEqualTo("1");
            JsonNode body = OBJECT_MAPPER.readTree(request.body());
            assertThat(body.path("orderId").asText()).isEqualTo("ORDER-7");
            assertThat(body.path("cardType").asText()).isEqualTo("SAMSUNG");
            assertThat(body.path("cardNo").asText()).isEqualTo("1234-5678-9814-1451");
            assertThat(body.path("amount").asLong()).isEqualTo(3_000L);
            assertThat(body.path("callbackUrl").asText()).isEqualTo(CALLBACK_URL);
        }

        @DisplayName("2xx 면 응답 status(PENDING·SUCCESS·FAILED)를 그대로 쓰고 거래 키·금액을 옮긴다.")
        @ParameterizedTest
        @CsvSource({"PENDING, PENDING", "SUCCESS, SUCCESS", "FAILED, FAILED"})
        void mapsSuccessfulResponse(String status, Outcome expected) {
            respond(200, transaction(status));

            PaymentGatewayResult result = request();

            assertThat(result).isEqualTo(new PaymentGatewayResult(expected, "tx-1", 3_000L));
        }

        @DisplayName("2xx 라도 status 를 모르거나 없거나 본문을 읽을 수 없으면 UNKNOWN.")
        @ParameterizedTest
        @ValueSource(strings = {"{\"transactionKey\":\"tx-1\",\"status\":\"CANCELED\"}", "{}", "not-json"})
        void unknown_whenBodyUnreadable(String body) {
            respond(200, body);

            assertThat(request().outcome()).isEqualTo(Outcome.UNKNOWN);
        }

        @DisplayName("4xx 는 PG 가 거절한 것이라 FAILED.")
        @Test
        void failed_on4xx() {
            respond(400, "{}");

            assertThat(request().outcome()).isEqualTo(Outcome.FAILED);
        }

        @DisplayName("5xx 는 결제가 됐는지 몰라도 기다리지 않고 ERROR.")
        @Test
        void error_on5xx() {
            respond(500, "{}");

            assertThat(request().outcome()).isEqualTo(Outcome.ERROR);
        }

        @DisplayName("read timeout 은 요청이 닿았는지 몰라도 기다리지 않고 ERROR.")
        @Test
        void error_onReadTimeout() {
            respondAfterReadTimeout();

            assertThat(request().outcome()).isEqualTo(Outcome.ERROR);
        }

        @DisplayName("거래 조회 read timeout 보다 늦은 응답도 결제 요청 read timeout 안이면 받는다.")
        @Test
        void usesPaymentReadTimeout() {
            respondAfter(LOOKUP_READ_TIMEOUT.multipliedBy(2));

            assertThat(request().outcome()).isEqualTo(Outcome.SUCCESS);
        }

        @DisplayName("연결 거부는 요청이 PG 에 닿지 않은 것이 확실하므로 NOT_SENT.")
        @Test
        void notSent_onConnectionRefused() throws IOException {
            PaymentGatewayResult result = clientForClosedPort().request(1L, 7L, "SAMSUNG", "1234-5678-9814-1451", 3_000L);

            assertThat(result.outcome()).isEqualTo(Outcome.NOT_SENT);
        }
    }

    @Nested
    @DisplayName("거래 조회 GET /api/v1/payments?orderId=")
    class Find {
        @DisplayName("PG 주문 ID 를 orderId 쿼리로, 사용자 ID 를 X-USER-ID 헤더에 싣고 GET 으로 조회한다. 2xx 면 응답 그대로.")
        @Test
        void findsByPgOrderId() {
            respond(200, transaction("SUCCESS"));

            PaymentGatewayResult result = client.find(1L, 7L);

            Received request = received.get();
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.path()).isEqualTo("/api/v1/payments");
            assertThat(request.query()).isEqualTo("orderId=ORDER-7");
            assertThat(request.userId()).isEqualTo("1");
            assertThat(result).isEqualTo(new PaymentGatewayResult(Outcome.SUCCESS, "tx-1", 3_000L));
        }

        @DisplayName("2xx FAILED·PENDING 도 그대로 옮긴다.")
        @ParameterizedTest
        @CsvSource({"PENDING, PENDING", "FAILED, FAILED"})
        void mapsSuccessfulResponse(String status, Outcome expected) {
            respond(200, transaction(status));

            assertThat(client.find(1L, 7L).outcome()).isEqualTo(expected);
        }

        @DisplayName("4xx·5xx 응답은 조회 실패일 뿐이라 UNKNOWN (FAILED 로 바꾸지 않는다).")
        @ParameterizedTest
        @ValueSource(ints = {400, 404, 500})
        void unknown_onErrorStatus(int status) {
            respond(status, "{}");

            assertThat(client.find(1L, 7L).outcome()).isEqualTo(Outcome.UNKNOWN);
        }

        @DisplayName("read timeout 은 UNKNOWN.")
        @Test
        void unknown_onReadTimeout() {
            respondAfterReadTimeout();

            assertThat(client.find(1L, 7L).outcome()).isEqualTo(Outcome.UNKNOWN);
        }

        @DisplayName("결제 요청 read timeout 안의 응답이라도 거래 조회 read timeout 을 넘으면 UNKNOWN.")
        @Test
        void unknown_afterLookupReadTimeout() {
            respondAfter(LOOKUP_READ_TIMEOUT.multipliedBy(2));

            assertThat(client.find(1L, 7L).outcome()).isEqualTo(Outcome.UNKNOWN);
        }

        @DisplayName("연결 거부도 UNKNOWN. 조회가 닿지 않았다고 결제가 안 된 것은 아니므로 NOT_SENT 로 바꾸지 않는다.")
        @Test
        void unknown_onConnectionRefused() throws IOException {
            assertThat(clientForClosedPort().find(1L, 7L).outcome()).isEqualTo(Outcome.UNKNOWN);
        }
    }
}
