package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@DisplayName("W10 직접 관측 실패 원문 보존")
class CheckoutIncidentObservationTest {

    private static final String UP = "{\"status\":\"UP\"}";
    private static final String METRIC = "commerce_payment_unknown{application=\"commerce-api\"} ";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;

    private CheckoutIncidentScenario scenario;
    private MockRestServiceServer server;
    private Path fault;

    @BeforeEach
    void prepare() {
        IncidentEvidenceWriter writer = IncidentEvidenceWriter.openRun(root,
            new IncidentCodeReference("head", "diff", 0, List.of()), IncidentMode.AUTO, "AUTO", Instant.EPOCH);
        fault = writer.runDir().resolve("fault");
        scenario = new CheckoutIncidentScenario();
        ReflectionTestUtils.setField(scenario, "writer", writer);
        ReflectionTestUtils.setField(scenario, "stage", IncidentStage.FAULT);
        ReflectionTestUtils.setField(scenario, "managementPort", 12345);
        RestTemplate observer = (RestTemplate) ReflectionTestUtils.getField(scenario, "observer");
        server = MockRestServiceServer.createServer(observer);
    }

    @Test
    @DisplayName("정상 응답도 원문과 상태·시각을 저장하고 기존 결합 원문을 반환한다.")
    void preservesSuccessfulResponses() throws IOException {
        respond("health", 200, UP);
        respond("prometheus", 200, METRIC + "3.0\n");

        String combined = observe();

        assertPreserved("health", 200, UP);
        assertPreserved("prometheus", 200, METRIC + "3.0\n");
        assertThat(combined).contains("\n" + UP + "\n\n" + METRIC + "3.0\n");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 503})
    @DisplayName("health 상태나 HTTP 코드가 잘못돼도 받은 원문은 남고 다음 조회로 진행하지 않는다.")
    void preservesFailedHealth(int status) throws IOException {
        String body = "{\"status\":\"DOWN\"}";
        respond("health", status, body);

        assertThatThrownBy(this::observe).isInstanceOf(AssertionError.class);

        assertPreserved("health", status, body);
        assertThat(fault.resolve("prometheus-response.txt")).doesNotExist();
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("badMetrics")
    @DisplayName("Prometheus HTTP 오류·NaN·표본 누락·기대값 불일치도 검사 전에 원문을 남긴다.")
    void preservesFailedMetric(int status, String body) throws IOException {
        respond("health", 200, UP);
        respond("prometheus", status, body);

        assertThatThrownBy(this::observe).isInstanceOf(AssertionError.class);

        assertPreserved("health", 200, UP);
        assertPreserved("prometheus", status, body);
        server.verify();
    }

    static Stream<Arguments> badMetrics() {
        return Stream.of(Arguments.of(503, "metrics unavailable\n"), Arguments.of(200, METRIC + "NaN\n"),
            Arguments.of(200, "# no UNKNOWN sample\n"), Arguments.of(200, METRIC + "7.0\n"));
    }

    @Test
    @DisplayName("원문 저장 실패는 전파하고 기존 파일을 덮거나 다음 조회로 진행하지 않는다.")
    void stopsWhenWritingFails() throws IOException {
        Files.createDirectories(fault);
        Files.writeString(fault.resolve("health-response.txt"), "original");
        respond("health", 200, UP);

        assertThatThrownBy(this::observe).isInstanceOf(UncheckedIOException.class);

        assertThat(Files.readString(fault.resolve("health-response.txt"))).isEqualTo("original");
        assertThat(fault.resolve("prometheus-response.txt")).doesNotExist();
        server.verify();
    }

    @Test
    @DisplayName("다음 HTTP 조회 자체가 실패해도 이미 받은 health 원문은 남는다.")
    void keepsHealthWhenPrometheusRequestFails() throws IOException {
        respond("health", 200, UP);
        server.expect(requestTo("http://127.0.0.1:12345/actuator/prometheus"))
            .andRespond(withException(new IOException("통신 실패")));

        assertThatThrownBy(this::observe).isInstanceOf(ResourceAccessException.class);

        assertPreserved("health", 200, UP);
        assertThat(fault.resolve("prometheus-response.txt")).doesNotExist();
        server.verify();
    }

    private void respond(String endpoint, int status, String body) {
        server.expect(requestTo("http://127.0.0.1:12345/actuator/" + endpoint))
            .andRespond(withStatus(HttpStatus.valueOf(status)).body(body));
    }

    private String observe() {
        return ReflectionTestUtils.invokeMethod(scenario, "assertDirectObservation", 3);
    }

    private void assertPreserved(String endpoint, int status, String body) throws IOException {
        assertThat(Files.readString(fault.resolve(endpoint + "-response.txt"))).isEqualTo(body);
        JsonNode metadata = JSON.readTree(fault.resolve(endpoint + "-response.json").toFile());
        assertThat(metadata.path("status").asInt()).isEqualTo(status);
        assertThat(metadata.path("bodyPresent").asBoolean()).isTrue();
        assertThat(Instant.parse(metadata.path("receivedAt").asText())).isNotNull();
    }
}
