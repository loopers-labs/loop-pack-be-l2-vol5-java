package com.loopers.testpg.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.testpg.TestPgApplication;
import com.loopers.testpg.application.PaymentScheduler;
import com.sun.net.httpserver.HttpServer;
import org.apache.coyote.AbstractProtocol;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = {TestPgApplication.class, PaymentHttpTest.ControlledCompletion.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class PaymentHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final BlockingQueue<ReceivedCallback> CALLBACKS = new LinkedBlockingQueue<>();
    private static final AtomicInteger CALLBACK_STATUS = new AtomicInteger(200);
    private static HttpServer receiver;
    private static ExecutorService receiverThreads;
    private static volatile int pgPort;
    private static volatile CallbackBlock callbackBlock;

    @LocalServerPort
    private int port;
    @Autowired
    private ManualScheduler scheduler;
    @Autowired
    private ServletWebServerApplicationContext serverContext;
    private String orderId;

    @DisplayName("기본 PG HTTP 서버는 127.0.0.1에만 바인딩한다.")
    @Test
    void bindsDefaultServerToLoopback() {
        TomcatWebServer webServer = (TomcatWebServer) serverContext.getWebServer();
        AbstractProtocol<?> protocol = (AbstractProtocol<?>) webServer.getTomcat().getConnector().getProtocolHandler();

        assertThat(protocol.getAddress()).isNotNull();
        assertThat(protocol.getAddress().getHostAddress()).isEqualTo("127.0.0.1");
    }

    @BeforeAll
    static void startCallbackReceiver() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiverThreads = Executors.newVirtualThreadPerTaskExecutor();
        receiver.setExecutor(receiverThreads);
        receiver.createContext("/callback", exchange -> {
            try (exchange) {
                CallbackBlock block = callbackBlock;
                JsonNode body = JSON.readTree(exchange.getRequestBody());
                boolean successVisible;
                try {
                    JsonNode stored = JSON.readTree(get("/" + body.path("transactionKey").asText(), "135135").body());
                    successVisible = stored.path("status").asText().equals("SUCCESS");
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                CALLBACKS.add(new ReceivedCallback(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("X-USER-ID"), body, successVisible));
                if (block != null) {
                    block.arrived().countDown();
                    try {
                        if (!block.release().await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("콜백 응답 대기 해제 실패");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }
                exchange.sendResponseHeaders(CALLBACK_STATUS.get(), -1);
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
    void isolateScenario() {
        pgPort = port;
        orderId = "LP-ORD-" + UUID.randomUUID();
        scheduler.reset();
        CALLBACKS.clear();
        CALLBACK_STATUS.set(200);
        callbackBlock = null;
    }

    @DisplayName("콜백 수신 서버가 응답을 보류해도 결제 완료 작업은 반환하고 성공 결과는 조회할 수 있다.")
    @Test
    void completesWithoutWaitingForCallbackResponse() throws Exception {
        CallbackBlock block = new CallbackBlock(new CountDownLatch(1), new CountDownLatch(1));
        callbackBlock = block;
        String key = JSON.readTree(create(validRequest(), "135135").body()).path("transactionKey").asText();

        try (var worker = Executors.newSingleThreadExecutor()) {
            var completion = worker.submit(scheduler::runNext);
            try {
                assertThat(block.arrived().await(3, TimeUnit.SECONDS)).isTrue();
                completion.get(500, TimeUnit.MILLISECONDS);

                assertThat(JSON.readTree(get("/" + key, "135135").body()).path("status").asText()).isEqualTo("SUCCESS");
            } finally {
                block.release().countDown();
            }
        }
    }

    private record CallbackBlock(CountDownLatch arrived, CountDownLatch release) {}

    @DisplayName("실제 HTTP로 PENDING 접수·조회와 3초 예약 뒤 SUCCESS 저장·웹훅을 검증한다.")
    @Test
    void acceptsQueriesAndSendsCallbackAfterSavingSuccess() throws Exception {
        HttpResponse<String> response = create(validRequest(), "135135");
        assertThat(response.statusCode()).isEqualTo(202);
        JsonNode accepted = JSON.readTree(response.body());
        String key = accepted.path("transactionKey").asText();
        assertThat(accepted.properties().stream().map(entry -> entry.getKey()).toList())
            .containsExactlyInAnyOrder("transactionKey", "orderId", "amount", "status");
        assertThat(accepted.path("orderId").asText()).isEqualTo(orderId);
        assertThat(accepted.path("amount").isIntegralNumber()).isTrue();
        assertThat(accepted.path("amount").asLong()).isEqualTo(7_000L);
        assertThat(accepted.path("status").asText()).isEqualTo("PENDING");
        assertThat(scheduler.delay).isEqualTo(Duration.ofSeconds(3));
        assertThat(CALLBACKS).isEmpty();
        assertThat(JSON.readTree(get("/" + key, "135135").body()).path("status").asText()).isEqualTo("PENDING");

        HttpResponse<String> byOrder = get(orderQuery(orderId), "135135");
        assertThat(byOrder.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(byOrder.body())).hasSize(1);
        assertThat(JSON.readTree(byOrder.body()).get(0)).isEqualTo(accepted);

        scheduler.runNext();

        ReceivedCallback callback = CALLBACKS.poll(3, TimeUnit.SECONDS);
        assertThat(callback).isNotNull();
        assertThat(callback.method()).isEqualTo("POST");
        assertThat(callback.path()).isEqualTo("/callback");
        assertThat(callback.contentType()).startsWith("application/json");
        assertThat(callback.customerHeader()).isNull();
        assertThat(callback.successVisible()).isTrue();
        assertThat(callback.body().properties().stream().map(entry -> entry.getKey()).toList())
            .containsExactlyInAnyOrder("providerOrderId", "transactionKey", "status");
        assertThat(callback.body().path("providerOrderId").asText()).isEqualTo(orderId);
        assertThat(callback.body().path("transactionKey").asText()).isEqualTo(key);
        assertThat(callback.body().path("status").asText()).isEqualTo("SUCCESS");
        HttpResponse<String> completed = get("/" + key, "135135");
        assertThat(completed.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(completed.body()).path("status").asText()).isEqualTo("SUCCESS");
        assertThat(JSON.readTree(get(orderQuery(orderId), "135135").body()).get(0).path("status").asText())
            .isEqualTo("SUCCESS");
        assertThat(scheduler.tasks).isEmpty();
        assertThat(CALLBACKS).isEmpty();
    }

    @DisplayName("없는 거래 키는 404이고 주문의 거래 0건은 200 빈 배열이다.")
    @Test
    void distinguishesMissingTransactionFromEmptyOrder() throws Exception {
        assertThat(get("/missing-key", "135135").statusCode()).isEqualTo(404);
        HttpResponse<String> empty = get(orderQuery(orderId), "135135");
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(empty.body()).isArray()).isTrue();
        assertThat(JSON.readTree(empty.body())).isEmpty();
    }

    @DisplayName("금액의 타입·범위 오류는 400이고 거래와 예약 작업을 남기지 않는다.")
    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "-1", "7000.5", "7000.0", "\"7000\"", "9223372036854775808"})
    void rejectsInvalidAmountsWithoutEffects(String amountJson) throws Exception {
        ObjectNode request = validRequest();
        request.set("amount", JSON.readTree(amountJson));

        HttpResponse<String> response = create(request, "135135");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("0000-0000-0000-0000");
        assertNoEffects();
    }

    @DisplayName("잘못된 제공자 계정은 세 API에서 401이고 거래를 생성하지 않는다.")
    @Test
    void rejectsOtherProviderAccount() throws Exception {
        assertThat(create(validRequest(), "other-account").statusCode()).isEqualTo(401);
        assertThat(get("/some-key", "other-account").statusCode()).isEqualTo(401);
        assertThat(get(orderQuery(orderId), "other-account").statusCode()).isEqualTo(401);
        assertNoEffects();
    }

    @DisplayName("헤더 누락·주문 ID·카드 입력·callback 오류는 400이고 기존 값을 유지한다.")
    @Test
    void rejectsMissingOrMalformedInput() throws Exception {
        assertThat(create(validRequest(), null).statusCode()).isEqualTo(400);
        for (String field : new String[]{"orderId", "cardType", "cardNo", "amount", "callbackUrl"}) {
            ObjectNode request = validRequest();
            request.remove(field);
            assertThat(create(request, "135135").statusCode()).as(field).isEqualTo(400);
        }
        ObjectNode shortOrder = validRequest().put("orderId", "12345");
        assertThat(create(shortOrder, "135135").statusCode()).isEqualTo(400);
        ObjectNode longOrder = validRequest().put("orderId", "a".repeat(65));
        assertThat(create(longOrder, "135135").statusCode()).isEqualTo(400);
        ObjectNode badCharacterOrder = validRequest().put("orderId", "LP-ORD/0001");
        assertThat(create(badCharacterOrder, "135135").statusCode()).isEqualTo(400);
        ObjectNode badCallback = validRequest().put("callbackUrl", "file:///tmp/callback");
        assertThat(create(badCallback, "135135").statusCode()).isEqualTo(400);
        ObjectNode badCard = validRequest().put("cardNo", 1234567890);
        assertThat(create(badCard, "135135").statusCode()).isEqualTo(400);
        assertNoEffects();
    }

    @DisplayName("주문 단위 조회도 주문번호 규칙을 벗어나면 400이다.")
    @ParameterizedTest
    @ValueSource(strings = {"12345", "LP ORD 0001", "LP-ORD/0001", "주문번호000001"})
    void rejectsOrderQueryOutsideRule(String invalidOrderId) throws Exception {
        assertThat(get(orderQuery(invalidOrderId), "135135").statusCode()).isEqualTo(400);
        assertThat(get(orderQuery("a".repeat(65)), "135135").statusCode()).isEqualTo(400);
    }

    @DisplayName("기본 설정에서는 테스트 제어 API를 등록하지 않는다.")
    @Test
    void hidesControlApiByDefault() throws Exception {
        HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + pgPort + "/test-control/v1/orders/" + orderId + "/stats"))
            .timeout(Duration.ofSeconds(5)).header("X-USER-ID", "135135").GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        HttpResponse<String> lookupControl = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + pgPort + "/test-control/v1/lookup"))
            .timeout(Duration.ofSeconds(5)).header("X-USER-ID", "135135").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(lookupControl.statusCode()).isEqualTo(404);
    }

    @DisplayName("웹훅 수신 서버가 실패해도 PG 성공은 유지되고 전송을 자동 반복하지 않는다.")
    @Test
    void keepsSuccessWhenWebhookDeliveryFails() throws Exception {
        CALLBACK_STATUS.set(503);
        JsonNode accepted = JSON.readTree(create(validRequest(), "135135").body());

        scheduler.runNext();

        assertThat(CALLBACKS.poll(3, TimeUnit.SECONDS)).isNotNull();
        assertThat(JSON.readTree(get("/" + accepted.path("transactionKey").asText(), "135135").body())
            .path("status").asText()).isEqualTo("SUCCESS");
        assertThat(scheduler.tasks).isEmpty();
        assertThat(CALLBACKS).isEmpty();
    }

    private ObjectNode validRequest() {
        return JSON.createObjectNode().put("orderId", orderId).put("cardType", "TEST")
            .put("cardNo", "0000-0000-0000-0000").put("amount", 7_000L)
            .put("callbackUrl", "http://127.0.0.1:" + receiver.getAddress().getPort() + "/callback");
    }

    private HttpResponse<String> create(ObjectNode request, String accountId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl()))
            .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(request)));
        if (accountId != null) {
            builder.header("X-USER-ID", accountId);
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String suffix, String accountId) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(baseUrl() + suffix))
            .timeout(Duration.ofSeconds(5)).header("X-USER-ID", accountId).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static String orderQuery(String orderId) {
        return "?orderId=" + URLEncoder.encode(orderId, StandardCharsets.UTF_8);
    }

    private static String baseUrl() {
        return "http://127.0.0.1:" + pgPort + "/api/v1/payments";
    }

    private void assertNoEffects() throws Exception {
        assertThat(JSON.readTree(get(orderQuery(orderId), "135135").body())).isEmpty();
        assertThat(scheduler.tasks).isEmpty();
        assertThat(CALLBACKS).isEmpty();
    }

    record ReceivedCallback(String method, String path, String contentType, String customerHeader,
                            JsonNode body, boolean successVisible) {}

    @TestConfiguration
    static class ControlledCompletion {
        @Bean
        @Primary
        ManualScheduler manualScheduler() {
            return new ManualScheduler();
        }
    }

    static class ManualScheduler implements PaymentScheduler {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private Duration delay;

        @Override
        public synchronized void schedule(Runnable task, Duration delay) {
            this.delay = delay;
            tasks.add(task);
        }

        synchronized void reset() {
            tasks.clear();
            delay = null;
        }

        synchronized void runNext() {
            tasks.remove().run();
        }
    }
}
