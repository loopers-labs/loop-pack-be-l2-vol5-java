package com.loopers.config;

import com.loopers.infrastructure.payment.monitoring.UnresolvedPaymentMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** 실제 management 서버를 띄워 노출 범위 · 바인딩 주소 · 업무 지표를 확인함 (10주차 D-201, D-205). 8081 이 비어 있다고 가정하지 않음 */
// 테스트는 기본으로 지표 export 를 끄므로 Prometheus 응답을 보려면 켬
@AutoConfigureObservability(tracing = false)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"management.server.port=0", "payment.metrics.unresolved.refresh-ms=200"}
)
class ManagementEndpointTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private Environment environment;

    @Autowired
    private UnresolvedPaymentMetrics metrics;

    @DisplayName("management 서버는 127.0.0.1 에 묶이고, health 는 UP 이다.")
    @Test
    void bindsToLoopbackAndReportsHealth() throws Exception {
        // act
        HttpResponse<String> health = get("/actuator/health");
        HttpResponse<String> readiness = get("/actuator/health/readiness");

        // assert
        assertAll(
            () -> assertThat(environment.getProperty("management.server.address")).isEqualTo("127.0.0.1"),
            () -> assertThat(health.statusCode()).isEqualTo(200),
            () -> assertThat(health.body()).contains("\"status\":\"UP\""),
            () -> assertThat(readiness.statusCode()).isEqualTo(200)
        );
    }

    @DisplayName("prometheus 응답에 미종결 결제 gauge 가 상태별로 있고 갱신 시각이 채워진다.")
    @Test
    void exposesUnresolvedPaymentGauge() throws Exception {
        // arrange
        metrics.refresh();

        // act
        String body = get("/actuator/prometheus").body();

        // assert
        assertAll(
            () -> assertThat(body).containsPattern("commerce_payment_unresolved\\{[^}]*status=\"REQUESTED\"[^}]*} "),
            () -> assertThat(body).containsPattern("commerce_payment_unresolved\\{[^}]*status=\"UNKNOWN\"[^}]*} "),
            () -> assertThat(body).containsPattern("commerce_payment_unresolved\\{[^}]*status=\"PENDING\"[^}]*} "),
            () -> assertThat(body).containsPattern("commerce_payment_unresolved_refreshed\\{[^}]*} [1-9]")
        );
    }

    @DisplayName("health · prometheus 밖의 관리 endpoint 는 열지 않는다.")
    @Test
    void doesNotExposeOtherEndpoints() throws Exception {
        assertAll(
            () -> assertThat(get("/actuator/env").statusCode()).isEqualTo(404),
            () -> assertThat(get("/actuator/heapdump").statusCode()).isEqualTo(404),
            () -> assertThat(get("/actuator/beans").statusCode()).isEqualTo(404)
        );
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
