package com.loopers.config.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.support.fixture.CommerceFixture;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.jpa.show-sql=false", "management.server.port=0"})
@ActiveProfiles({"test", "experiment"})
@Import(MySqlTestContainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class ExperimentHttpTest {
    @LocalServerPort int port;

    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;

    @ParameterizedTest
    @ValueSource(ints = {1, 20})
    void recordsSuccessfulRequestStagesWithoutChangingItsBody(int pageSize, CapturedOutput output) throws Exception {
        CommerceFixture fixture = new CommerceFixture(entityManager, transactionManager);
        var buyer = fixture.user();
        var brand = fixture.brand("Timing brand");
        var product = fixture.product(brand, "Timing product", 100L, 1);
        fixture.like(buyer, product);
        for (int i = 0; i < pageSize; i++) {
            fixture.like(buyer, fixture.product(brand, "Timing product " + i, 100L, 1));
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + "/api/v1/products?sort=likes_desc&page=0&size=" + pageSize))
                .header("X-USER-ID", buyer.getId().toString())
                .header("X-Run-ID", "timing-test").header("X-Request-ID", "timing-success").GET().build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var data = new ObjectMapper().readTree(response.body()).path("data");
            assertThat(data.path("content").get(0).path("id").asLong()).isEqualTo(product.getId());
            assertThat(data.path("last").asBoolean()).isFalse();
            assertThat(data.path("content").size()).isEqualTo(pageSize);
            assertThat(data.has("totalElements")).isFalse();
            String line = null;
            for (int i = 0; i < 50 && line == null; i++) {
                line = output.getAll().lines()
                    .filter(value -> value.contains("EXPERIMENT_JSON ") && value.contains("timing-success"))
                    .findFirst().orElse(null);
                if (line == null) Thread.sleep(20);
            }
            assertThat(line).isNotNull();
            var event = new ObjectMapper().readTree(line.substring(line.indexOf("EXPERIMENT_JSON ") + 16));
            var stages = event.path("stages");
            for (String key : java.util.List.of("request.pipeline", "ProductV1Controller.getProducts",
                "ProductUseCase.findBasicPage", "ProductRepositoryAdapter.findCustomerProductsByLikes",
                "jdbc.execute", "jdbc.result.read", "response.writeAndMvcCompletion")) {
                assertThat(stages.has(key)).as(key).isTrue();
                assertThat(stages.path(key).path("totalMs").asDouble()).isGreaterThanOrEqualTo(0);
            }
            double sum = 0;
            for (var stage : stages) {
                assertThat(stage.path("selfMs").asDouble()).isGreaterThanOrEqualTo(0);
                sum += stage.path("selfMs").asDouble();
            }
            assertThat(sum).isCloseTo(stages.path("request.pipeline").path("totalMs").asDouble(),
                org.assertj.core.data.Offset.offset(0.001));
            // 인증 1 + 목록/브랜드 3 + 좋아요 일괄 2 + 재고 일괄 2. size=1/20 모두 고정 비용이다.
            assertThat(event.path("queries").size()).isEqualTo(8);
            for (String repository : java.util.List.of("BrandRepositoryAdapter.findAllByIds")) {
                assertThat(stages.path(repository).path("calls").asInt()).as(repository).isEqualTo(1);
            }
            assertThat(stages.path("StockRepositoryAdapter.findAllByProductIds").path("calls").asInt()).isEqualTo(1);
            assertThat(stages.has("StockRepositoryAdapter.findByProductId")).isFalse();
            assertThat(stages.has("LikeRepositoryAdapter.countByProductId")).isFalse();
            assertThat(stages.path("LikeRepositoryAdapter.countByProductIds").path("calls").asInt()).isEqualTo(1);
            assertThat(stages.has("BrandRepositoryAdapter.findById")).isFalse();
            for (var query : event.path("queries")) {
                assertThat(query.path("sql").asText()).doesNotContain("select count(p");
            }
        } finally {
            fixture.truncateRemainingTables();
        }
    }

    @Test void recordsActualTomcatTimeAndSqlOnAnHttpError(CapturedOutput output) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/products"))
            .header("X-USER-ID", "999999999")
            .header("X-Run-ID", "test-run").header("X-Request-ID", "test-request").GET().build();
        long start = System.nanoTime();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        double clientMs = (System.nanoTime() - start) / 1_000_000.0;
        assertThat(response.statusCode()).isEqualTo(401);
        String line = null;
        for (int i = 0; i < 50 && line == null; i++) {
            line = output.getAll().lines().filter(s -> s.contains("EXPERIMENT_JSON ") && s.contains("test-request"))
                .findFirst().orElse(null);
            if (line == null) Thread.sleep(20);
        }
        assertThat(line).isNotNull();
        var event = new ObjectMapper().readTree(line.substring(line.indexOf("EXPERIMENT_JSON ") + 16));
        assertThat(event.get("serverDurationMs").asDouble()).isPositive().isLessThan(clientMs);
        assertThat(event.get("httpStatus").asInt()).isEqualTo(401);
        assertThat(event.get("queries").size()).isGreaterThan(0);
        assertThat(event.get("queries").get(0).get("sql").asText()).containsIgnoringCase("select");
        assertThat(event.get("queries").get(0).get("durationMs").asDouble()).isGreaterThanOrEqualTo(0);
        assertThat(event.get("sqlTruncated").asBoolean()).isFalse();
    }
}
