package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.PaymentLookupObservation;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class TestPgPaymentGatewayFailureClassificationTest {
    private HttpServer server;
    private ExecutorService executor;
    private volatile int responseStatus = 200;
    private volatile long delayMillis;
    private volatile String responseBody = "not-json";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/api/v1/payments", exchange -> {
            try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(responseStatus, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                // 취소된 HTTP 호출의 응답 쓰기는 실패할 수 있다.
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        Thread.interrupted();
        server.stop(0);
        executor.shutdownNow();
    }

    @DisplayName("조회 HTTP 상태와 응답 형식으로 재시도 여부를 결정할 실패 종류를 구분한다.")
    @ParameterizedTest
    @CsvSource({"false,502,TEMPORARY_HTTP", "true,503,TEMPORARY_HTTP", "false,504,TEMPORARY_HTTP",
        "true,401,HTTP_ERROR", "false,429,HTTP_ERROR", "true,500,HTTP_ERROR",
        "false,200,INVALID_RESPONSE", "true,200,INVALID_RESPONSE"})
    void classifiesHttpResponsesBeforeRetry(boolean byOrder, int status, PaymentLookupObservation.FailureKind kind) {
        responseStatus = status;

        PaymentLookupObservation result = byOrder ? gateway(Duration.ofSeconds(1)).lookupByOrder("LP-ORD-000042")
            : gateway(Duration.ofSeconds(1)).lookup("TX-1");

        assertThat(result).isInstanceOfSatisfying(PaymentLookupObservation.Unavailable.class,
            unavailable -> assertThat(unavailable.kind()).isEqualTo(kind));
    }

    @DisplayName("응답 timeout은 재시도 가능한 조회 실패로 분류한다.")
    @Test
    void classifiesReadTimeoutAsRetryable() {
        delayMillis = 300;

        assertThat(gateway(Duration.ofMillis(50)).lookup("TX-1"))
            .isInstanceOfSatisfying(PaymentLookupObservation.Unavailable.class, unavailable -> {
                assertThat(unavailable.kind()).isEqualTo(PaymentLookupObservation.FailureKind.TIMEOUT);
                assertThat(unavailable.kind().retryable()).isTrue();
            });
    }

    @DisplayName("호출자의 중단은 재시도 가능한 timeout으로 취급하지 않는다.")
    @Test
    void doesNotTreatCallerInterruptionAsRetryableTimeout() {
        Thread.currentThread().interrupt();

        assertThat(gateway(Duration.ofSeconds(1)).lookup("TX-1"))
            .isInstanceOfSatisfying(PaymentLookupObservation.Unavailable.class, unavailable -> {
                assertThat(unavailable.kind()).isEqualTo(PaymentLookupObservation.FailureKind.CANCELLED);
                assertThat(unavailable.kind().retryable()).isFalse();
            });
    }

    @DisplayName("연결 실패는 재시도 가능한 네트워크 오류로 분류한다.")
    @Test
    void classifiesConnectionFailureAsRetryableNetworkError() {
        TestPgPaymentGateway gateway = gateway(Duration.ofSeconds(1));
        server.stop(0);
        assertThat(gateway.lookupByOrder("LP-ORD-000042"))
            .isInstanceOfSatisfying(PaymentLookupObservation.Unavailable.class, unavailable -> {
                assertThat(unavailable.kind()).isEqualTo(PaymentLookupObservation.FailureKind.NETWORK);
                assertThat(unavailable.kind().retryable()).isTrue();
            });
    }

    private TestPgPaymentGateway gateway(Duration timeout) {
        return new TestPgPaymentGateway(new PgProperties("http://127.0.0.1:" + server.getAddress().getPort(),
            "http://127.0.0.1:9/callback", "135135", Duration.ofSeconds(1), timeout), new ObjectMapper());
    }
}
