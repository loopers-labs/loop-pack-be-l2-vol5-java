package com.loopers.infrastructure.order;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;

/** 직접 관측 GET 만 read 3초이고 업무 API client 는 더 긴 응답을 기다리는지 실제 지연 서버로 확인한다. */
@DisplayName("W10 직접 관측 HTTP 제한 검증")
class CheckoutIncidentHttpLimitTest {

    private static final long DELAY_MS = 5_000;

    private HttpServer server;
    private ExecutorService workers;

    @BeforeEach
    void startDelayedServer() throws IOException {
        workers = Executors.newFixedThreadPool(2);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(DELAY_MS);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(workers);
        server.start();
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        server.stop(0);
        workers.shutdownNow();
        assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).as("지연 서버 종료").isTrue();
    }

    @DisplayName("직접 관측 client 는 3초 read 제한으로 실패하고 업무 client 는 응답을 받는다.")
    @Test
    void observerTimesOutButBusinessClientWaits() {
        CheckoutIncidentScenario scenario = new CheckoutIncidentScenario();
        RestTemplate observer = (RestTemplate) ReflectionTestUtils.getField(scenario, "observer");
        RestTemplate business = (RestTemplate) ReflectionTestUtils.getField(scenario, "http");
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/actuator/health";

        long started = System.nanoTime();
        Throwable failure = catchThrowable(() -> observer.getForEntity(url, String.class));
        Duration observerElapsed = Duration.ofNanos(System.nanoTime() - started);
        ResponseEntity<String> waited = business.getForEntity(url, String.class);

        assertAll(
            () -> assertThat(failure).isInstanceOf(ResourceAccessException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class),
            () -> assertThat(observerElapsed).as("read 3초 근처에서 실패")
                .isBetween(Duration.ofMillis(2_500), Duration.ofMillis(DELAY_MS - 500)),
            () -> assertThat(waited.getStatusCode().value()).isEqualTo(200)
        );
    }
}
