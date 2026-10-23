package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.PaymentCreateCommand;
import com.loopers.application.payment.PaymentCreateObservation;
import com.loopers.application.payment.PaymentLookupObservation;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TestPgPaymentGatewayRequestContractTest {
    private static final String ORDER_ID = "LP-ORD-0000000042";
    private static final String CALLBACK_URL = "http://127.0.0.1:8080/api/v1/payments/callback";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BlockingQueue<CapturedRequest> requests = new LinkedBlockingQueue<>();
    private HttpServer server;
    private ExecutorService executor;
    private TestPgPaymentGateway gateway;
    private volatile int responseStatus = 200;
    private volatile String responseBody = "[]";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/api/v1/payments", exchange -> {
            try {
                requests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI(),
                    exchange.getRequestHeaders().getFirst("X-USER-ID"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        gateway = new TestPgPaymentGateway(new PgProperties("http://127.0.0.1:" + server.getAddress().getPort(),
            CALLBACK_URL, "135135", Duration.ofSeconds(1), Duration.ofSeconds(2)), objectMapper);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    @DisplayName("생성은 POST, 고정 제공자 헤더와 callback URL, 문자열 주문 ID, 정수 금액을 정확히 전송한다.")
    @Test
    void sendsCreateContractAndMapsAcceptedTransaction() throws Exception {
        responseStatus = 202;
        responseBody = transaction("TEST-TX-0042");

        PaymentCreateObservation result = gateway.create(
            new PaymentCreateCommand(ORDER_ID, 7_000L, "TEST", "0000-0000-0000-0000"));

        CapturedRequest request = request();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().getRawPath()).isEqualTo("/api/v1/payments");
        assertThat(request.uri().getRawQuery()).isNull();
        assertThat(MediaType.parseMediaType(request.contentType())).isEqualTo(MediaType.APPLICATION_JSON);
        JsonNode body = objectMapper.readTree(request.body());
        assertThat(body.size()).isEqualTo(5);
        assertThat(body.path("orderId").isTextual()).isTrue();
        assertThat(body.path("orderId").asText()).isEqualTo(ORDER_ID);
        assertThat(body.path("amount").isIntegralNumber()).isTrue();
        assertThat(body.path("amount").longValue()).isEqualTo(7_000L);
        assertThat(body.path("cardType").asText()).isEqualTo("TEST");
        assertThat(body.path("cardNo").asText()).isEqualTo("0000-0000-0000-0000");
        assertThat(body.path("callbackUrl").asText()).isEqualTo(CALLBACK_URL);
        assertThat(result).isEqualTo(new PaymentCreateObservation.Accepted("TEST-TX-0042"));
        assertThat(requests).isEmpty();
    }

    @DisplayName("단건 조회는 GET과 같은 제공자 헤더를 사용하고 거래 키를 하나의 경로 변수로 인코딩한다.")
    @ParameterizedTest
    @ValueSource(strings = {"TEST-TX-0042", "TEST TX/+?#%"})
    void sendsTransactionLookupContract(String transactionKey) throws Exception {
        responseBody = transaction(transactionKey);

        PaymentLookupObservation result = gateway.lookup(transactionKey);

        CapturedRequest request = request();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.uri().getPath()).isEqualTo("/api/v1/payments/" + transactionKey);
        if (transactionKey.contains("/")) {
            assertThat(request.uri().getRawPath()).isEqualTo("/api/v1/payments/TEST%20TX%2F%2B%3F%23%25");
        }
        assertThat(request.uri().getRawQuery()).isNull();
        assertThat(request.body()).isEmpty();
        assertThat(result).isInstanceOfSatisfying(PaymentLookupObservation.Found.class,
            found -> assertThat(found.transactions()).singleElement().satisfies(transaction ->
                assertThat(transaction.transactionKey()).isEqualTo(transactionKey)));
        assertThat(requests).isEmpty();
    }

    @DisplayName("주문 조회는 GET과 같은 제공자 헤더를 사용하고 특수 문자가 있는 주문 ID도 단일 query 값으로 보낸다.")
    @Test
    void encodesOrderLookupQueryWithoutChangingIdentifier() throws Exception {
        String providerOrderId = "LP-ORD-42 +&?/=#%한글";

        PaymentLookupObservation result = gateway.lookupByOrder(providerOrderId);

        CapturedRequest request = request();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.uri().getRawPath()).isEqualTo("/api/v1/payments");
        assertThat(request.uri().getRawQuery())
            .isEqualTo("orderId=LP-ORD-42%20%2B%26%3F%2F%3D%23%25%ED%95%9C%EA%B8%80");
        assertThat(request.uri().getQuery()).isEqualTo("orderId=" + providerOrderId);
        assertThat(request.body()).isEmpty();
        assertThat(result).isInstanceOfSatisfying(PaymentLookupObservation.Found.class,
            found -> assertThat(found.transactions()).isEmpty());
        assertThat(requests).isEmpty();
    }

    private CapturedRequest request() throws InterruptedException {
        CapturedRequest request = requests.poll(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.providerAccount()).isEqualTo("135135");
        return request;
    }

    private String transaction(String transactionKey) {
        return objectMapper.createObjectNode().put("transactionKey", transactionKey).put("orderId", ORDER_ID)
            .put("amount", 7_000L).put("status", "PENDING").toString();
    }

    private record CapturedRequest(String method, URI uri, String providerAccount, String contentType, String body) {
        @Override
        public String toString() {
            return "CapturedRequest[method=" + method + ", uri=" + uri + ", body=redacted]";
        }
    }
}
