package com.loopers.batch.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("결제 대사 PG 조회 서킷 브레이커")
class TossPaymentLookupCircuitBreakerTest {

    @Test
    @DisplayName("기술적 5xx 실패율이 임계치에 도달하면 다음 PG 조회 요청을 차단한다")
    void opensForServerErrorsAndSkipsFurtherLookupRequests() throws Exception {
        try (StubPg pg = new StubPg(500, "{}")) {
            CircuitBreaker circuitBreaker = testCircuitBreaker();
            TossPaymentLookupClient client = pg.client(circuitBreaker);

            assertThat(client.findByOrderId("order-1").httpStatus()).isEqualTo(500);
            assertThat(client.findByOrderId("order-1").httpStatus()).isEqualTo(500);

            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(client.findByOrderId("order-1").httpStatus()).isZero();
            assertThat(pg.requestCount()).hasValue(2);
        }
    }

    @Test
    @DisplayName("정상 응답인 결제 PROCESSING 결과는 배치 조회 서킷 실패로 집계하지 않는다")
    void doesNotOpenForProcessingResult() throws Exception {
        try (StubPg pg = new StubPg(200, "{\"status\":\"IN_PROGRESS\"}")) {
            CircuitBreaker circuitBreaker = testCircuitBreaker();
            TossPaymentLookupClient client = pg.client(circuitBreaker);

            assertThat(client.findByOrderId("order-1").paymentStatus()).isEqualTo("IN_PROGRESS");
            assertThat(client.findByOrderId("order-1").paymentStatus()).isEqualTo("IN_PROGRESS");

            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(pg.requestCount()).hasValue(2);
        }
    }

    private CircuitBreaker testCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(2)
            .minimumNumberOfCalls(2)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofMinutes(1))
            .permittedNumberOfCallsInHalfOpenState(1)
            .recordResult(result -> result instanceof PaymentLookupClient.LookupResult lookup
                && (lookup.httpStatus() == 0 || lookup.httpStatus() >= 500))
            .build();
        return CircuitBreaker.of("test-batch-lookup", config);
    }

    private static final class StubPg implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger requestCount = new AtomicInteger();

        private StubPg(int status, String body) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/payments/orders/order-1", exchange -> {
                requestCount.incrementAndGet();
                byte[] responseBody = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, responseBody.length);
                exchange.getResponseBody().write(responseBody);
                exchange.close();
            });
            server.start();
        }

        private AtomicInteger requestCount() {
            return requestCount;
        }

        private TossPaymentLookupClient client(CircuitBreaker circuitBreaker) {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            return new TossPaymentLookupClient(
                RestClient.builder(), new ObjectMapper(), new TossPaymentProperties(baseUrl, "test-secret"),
                circuitBreaker);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
