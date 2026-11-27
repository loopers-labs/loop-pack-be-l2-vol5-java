package com.loopers.application.ordering.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

record UnknownOrderGaugeSample(Instant phaseCompletedAt, Instant observedAt, int count, String health) {
    static UnknownOrderGaugeSample awaitAfter(TestRestTemplate http, int managementPort, Instant phaseCompletedAt,
                                             int expectedCount, Path phaseDirectory) throws IOException {
        Files.createDirectories(phaseDirectory);
        String actuator = "http://127.0.0.1:" + managementPort + "/actuator/";
        String healthBody = readAndSave(http, actuator + "health", phaseDirectory.resolve("health.json"));
        String health = new ObjectMapper().readTree(healthBody).path("status").asText();
        assertThat(health).as("단계별 Actuator health").isEqualTo("UP");

        Path prometheus = phaseDirectory.resolve("prometheus.prom");
        var result = new AtomicReference<UnknownOrderGaugeSample>();
        await().alias("미확인 주문 " + expectedCount + "건 · " + phaseDirectory)
            .pollDelay(Duration.ZERO).pollInterval(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(45))
            .untilAsserted(() -> {
                String body = readAndSave(http, actuator + "prometheus", prometheus);
                double count = sample(body, "commerce_payment_unknown");
                double lastSuccess = sample(body, "commerce_payment_unknown_last_success_timestamp_seconds");
                assertThat(count).as("마지막으로 읽은 건수 (집계 시각 %s초)", lastSuccess).isEqualTo((double) expectedCount);
                assertThat(lastSuccess).as("마지막 성공 집계 시각").isFinite();
                Instant observedAt = Instant.ofEpochMilli(Math.round(lastSuccess * 1000));
                assertThat(observedAt).as("단계 완료 후 새 집계 시각 (마지막 건수 %s)", count).isAfter(phaseCompletedAt);
                result.set(new UnknownOrderGaugeSample(phaseCompletedAt, observedAt, (int) count, health));
            });
        return result.get();
    }

    private static String readAndSave(TestRestTemplate http, String url, Path file) throws IOException {
        var response = http.getForEntity(url, String.class);
        String body = response.getBody() == null ? "" : response.getBody();
        Files.writeString(file, body);
        assertThat(response.getStatusCode()).as("%s · %s", url, file).isEqualTo(HttpStatus.OK);
        return body;
    }

    private static double sample(String body, String name) {
        var lines = body.lines().filter(line -> line.startsWith(name + "{") || line.startsWith(name + " ")).toList();
        assertThat(lines).as("Prometheus 표본 %s", name).hasSize(1);
        String line = lines.getFirst();
        return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
    }
}
