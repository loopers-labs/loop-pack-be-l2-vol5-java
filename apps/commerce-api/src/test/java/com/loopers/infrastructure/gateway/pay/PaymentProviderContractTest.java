package com.loopers.infrastructure.gateway.pay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.pay.gateway.PaymentProviderGateway;
import com.loopers.config.pay.PaymentProviderConfig;
import com.loopers.domain.pay.model.PaymentObservation;
import com.loopers.domain.pay.model.PaymentScenario;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentProviderContractTest {
    static final String ORDER = "LP-ORD-0000000042";
    static final String PAYMENT = "{\"transactionKey\":\"PG-1\",\"orderId\":\"" + ORDER + "\",\"amount\":7000,\"status\":\"PENDING\"}";
    final ObjectMapper json = new ObjectMapper();
    final AtomicReference<Reply> reply = new AtomicReference<>(new Reply(200, PAYMENT, "NORMAL"));
    final AtomicReference<Captured> captured = new AtomicReference<>();
    final AtomicInteger calls = new AtomicInteger();
    final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    HttpServer server;
    CloseableHttpClient httpClient;
    HttpPaymentProviderGateway gateway;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            try (exchange) {
                captured.set(new Captured(exchange.getRequestMethod(), exchange.getRequestURI(),
                        exchange.getRequestHeaders().getFirst("X-USER-ID"),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        json.readTree(exchange.getRequestBody())));
                Reply current = reply.get();
                if (current.mode().equals("CLOSE")) {
                    return;
                }
                if (current.mode().equals("HEADER_DELAY")) {
                    sleep();
                }
                byte[] body = current.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Location", "/redirected");
                exchange.sendResponseHeaders(current.status(), body.length);
                if (current.mode().equals("BODY_DELAY")) {
                    exchange.getResponseBody().write(body, 0, 1);
                    exchange.getResponseBody().flush();
                    sleep();
                    exchange.getResponseBody().write(body, 1, body.length - 1);
                } else {
                    exchange.getResponseBody().write(body);
                }
            }
        });
        server.start();
        PaymentProviderConfig config = new PaymentProviderConfig();
        httpClient = config.paymentProviderHttpClient();
        gateway = new HttpPaymentProviderGateway(config.paymentProviderRestClient(httpClient,
                "http://127.0.0.1:" + server.getAddress().getPort()), json,
                URI.create("http://127.0.0.1:8080/api/v1/payments/callback"));
    }

    @AfterEach
    void close() throws Exception {
        httpClient.close();
        server.stop(0);
        executor.close();
    }

    @Test
    @DisplayName("생성·거래 조회·주문 조회의 경로·계정·JSON 타입·식별 검증을 실제 HTTP로 확인한다")
    void threeContracts() {
        reply.set(new Reply(202, PAYMENT, "NORMAL"));
        PaymentObservation created = create();
        assertThat(created.kind()).isEqualTo(PaymentObservation.Kind.VERIFIED);
        assertThat(captured.get().method()).isEqualTo("POST");
        assertThat(captured.get().uri().getPath()).isEqualTo("/api/v1/payments");
        assertThat(captured.get().account()).isEqualTo("135135");
        assertThat(captured.get().contentType()).startsWith("application/json");
        assertThat(captured.get().body().get("amount").isIntegralNumber()).isTrue();
        assertThat(captured.get().body().get("cardNo").asText()).isEqualTo("0000-0000-0000-0000");
        assertThat(captured.get().body().get("testOptions").get("overrides").get("processingDelayMs").asLong()).isZero();
        reply.set(new Reply(200, PAYMENT, "NORMAL"));
        assertThat(gateway.lookup(expected("PG-1")).kind()).isEqualTo(PaymentObservation.Kind.VERIFIED);
        assertThat(captured.get().method()).isEqualTo("GET");
        assertThat(captured.get().uri().getPath()).isEqualTo("/api/v1/payments/PG-1");
        String specialOrder = "SPECIAL-ORDER&한글";
        reply.set(new Reply(200, "[" + PAYMENT.replace(ORDER, specialOrder) + "]", "NORMAL"));
        assertThat(gateway.lookup(new PaymentProviderGateway.ExpectedPayment(specialOrder, 7000, null)).kind())
                .isEqualTo(PaymentObservation.Kind.VERIFIED);
        assertThat(captured.get().uri().getRawQuery()).contains("%26", "%ED");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("정확한 생성 전 거절만 효과 0건이며 불완전한 422는 확인 불가다")
    void noEffectRejection() {
        String rejection = "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"" + ORDER
                + "\",\"amount\":7000,\"transactionCreated\":false}";
        reply.set(new Reply(422, rejection, "NORMAL"));
        assertThat(create().kind()).isEqualTo(PaymentObservation.Kind.REJECTED_NO_EFFECT);
        reply.set(new Reply(422, rejection.replace(":false", ":\"false\""), "NORMAL"));
        assertThat(create().kind()).isEqualTo(PaymentObservation.Kind.UNAVAILABLE);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("빈 조회와 없는 거래는 NOT_FOUND이며 복수 거래와 금액 불일치는 충돌이다")
    void missingAndConflictingTransactions() {
        reply.set(new Reply(200, "[]", "NORMAL"));
        assertThat(gateway.lookup(expected(null)).kind()).isEqualTo(PaymentObservation.Kind.NOT_FOUND);
        reply.set(new Reply(404, "{}", "NORMAL"));
        assertThat(gateway.lookup(expected("PG-1")).kind()).isEqualTo(PaymentObservation.Kind.NOT_FOUND);
        reply.set(new Reply(200, "[" + PAYMENT + "," + PAYMENT + "]", "NORMAL"));
        assertThat(gateway.lookup(expected(null)).reason()).isEqualTo(PaymentObservation.Reason.MULTIPLE_TRANSACTIONS);
        reply.set(new Reply(200, PAYMENT.replace("7000", "7001"), "NORMAL"));
        assertThat(gateway.lookup(expected("PG-1")).kind()).isEqualTo(PaymentObservation.Kind.CONFLICT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{broken", "{\"transactionKey\":\"PG-1\"}", "STRING_AMOUNT", "TRAILING_GARBAGE", "SECOND_JSON"})
    @DisplayName("깨진 JSON·빠진 필드·잘못된 숫자 타입은 검증된 결제 결과가 아니다")
    void invalidResponse(String body) {
        String response = switch (body) {
            case "STRING_AMOUNT" -> PAYMENT.replace(":7000", ":\"7000\"");
            case "TRAILING_GARBAGE" -> PAYMENT + " garbage";
            case "SECOND_JSON" -> PAYMENT + " {}";
            default -> body;
        };
        reply.set(new Reply(200, response, "NORMAL"));
        PaymentObservation result = gateway.lookup(expected("PG-1"));
        assertThat(result.kind()).isEqualTo(PaymentObservation.Kind.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(PaymentObservation.Reason.INVALID_RESPONSE);
    }

    @Test
    @DisplayName("redirect와 연결 종료에서 create를 자동 재발송하지 않는다")
    void noHiddenCreateRetryOrRedirect() {
        reply.set(new Reply(302, "{}", "NORMAL"));
        assertThat(create().kind()).isEqualTo(PaymentObservation.Kind.UNAVAILABLE);
        assertThat(calls.get()).isEqualTo(1);
        reply.set(new Reply(200, "{}", "CLOSE"));
        assertThat(create().kind()).isEqualTo(PaymentObservation.Kind.UNAVAILABLE);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("조회 인증 오류와 일시 서버 오류를 후속 복구가 구분할 수 있어야 한다")
    void distinguishHttpFailures() {
        reply.set(new Reply(403, "{}", "NORMAL"));
        PaymentObservation authenticationFailure = gateway.lookup(expected("PG-1"));
        reply.set(new Reply(503, "{}", "NORMAL"));
        PaymentObservation serverFailure = gateway.lookup(expected("PG-1"));
        assertThat(authenticationFailure.kind()).isEqualTo(PaymentObservation.Kind.UNAVAILABLE);
        assertThat(serverFailure.kind()).isEqualTo(PaymentObservation.Kind.UNAVAILABLE);
        assertThat(authenticationFailure.httpStatus()).isEqualTo(403);
        assertThat(serverFailure.httpStatus()).isEqualTo(503);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEADER_DELAY", "BODY_DELAY"})
    @DisplayName("실제 응답과 본문 읽기 지연에 timeout을 적용하고 추가 요청을 보내지 않는다")
    void effectiveTimeouts(String mode) {
        reply.set(new Reply(200, PAYMENT, mode));
        PaymentObservation result = gateway.lookup(expected("PG-1"));
        assertThat(result.kind()).isEqualTo(PaymentObservation.Kind.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(PaymentObservation.Reason.TIMEOUT);
        assertThat(calls.get()).isEqualTo(1);
    }

    private PaymentObservation create() {
        return gateway.create(expected(null), new PaymentProviderGateway.CardInput("TEST", "0000-0000-0000-0000"),
                PaymentScenario.resolve(null, null));
    }

    private PaymentProviderGateway.ExpectedPayment expected(String key) {
        return new PaymentProviderGateway.ExpectedPayment(ORDER, 7000, key);
    }

    private static void sleep() {
        try {
            Thread.sleep(2500);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    record Reply(int status, String body, String mode) {}
    record Captured(String method, URI uri, String account, String contentType, JsonNode body) {}
}
