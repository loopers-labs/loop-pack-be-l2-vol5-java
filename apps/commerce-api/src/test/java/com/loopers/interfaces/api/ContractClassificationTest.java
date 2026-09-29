package com.loopers.interfaces.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.brand.BrandFacade;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractClassificationTest {

    private static final String ENDPOINT_BRAND = "/api/v1/brands/";
    private static final String ENDPOINT_UNMAPPED = "/api/v1/this-endpoint-does-not-exist";

    private final TestRestTemplate testRestTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private final ObjectMapper objectMapper;
    private final BrandFacade brandFacade;

    @Autowired
    public ContractClassificationTest(
        TestRestTemplate testRestTemplate,
        DatabaseCleanUp databaseCleanUp,
        ObjectMapper objectMapper,
        BrandFacade brandFacade
    ) {
        this.testRestTemplate = testRestTemplate;
        this.databaseCleanUp = databaseCleanUp;
        this.objectMapper = objectMapper;
        this.brandFacade = brandFacade;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private record Observation(int status, String result, String errorCode, boolean hasData) {}

    private Observation observe(String url) {
        ResponseEntity<String> response =
            testRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(null), String.class);

        JsonNode body = readTree(response.getBody());
        JsonNode meta = body.path("meta");
        return new Observation(
            response.getStatusCode().value(),
            text(meta.path("result")),
            text(meta.path("errorCode")),
            body.hasNonNull("data")
        );
    }

    private JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new AssertionError("응답 body 가 JSON 이 아니다: " + body, e);
        }
    }

    private String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    @DisplayName("존재하는 숫자 ID: 200 / SUCCESS / errorCode 없음 / data 있음")
    @Test
    void observesSuccessEnvelope_whenExistingNumericId() {
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();

        Observation observed = observe(ENDPOINT_BRAND + brandId);

        assertAll(
            () -> assertThat(observed.status()).isEqualTo(200),
            () -> assertThat(observed.result()).isEqualTo("SUCCESS"),
            () -> assertThat(observed.errorCode()).isNull(),
            () -> assertThat(observed.hasData()).isTrue()
        );
    }

    @DisplayName("숫자가 아닌 ID 'abc': 400 / FAIL / errorCode 'Bad Request' / data 없음")
    @Test
    void observesBadRequestEnvelope_whenIdIsNotNumeric() {
        Observation observed = observe(ENDPOINT_BRAND + "abc");

        assertAll(
            () -> assertThat(observed.status()).isEqualTo(400),
            () -> assertThat(observed.result()).isEqualTo("FAIL"),
            () -> assertThat(observed.errorCode()).isEqualTo("Bad Request"),
            () -> assertThat(observed.hasData()).isFalse()
        );
    }

    @DisplayName("존재하지 않는 숫자 ID: 404 / FAIL / errorCode 'BRAND_NOT_FOUND' / data 없음 — 봉투는 같고 업무 의미가 실린다")
    @Test
    void observesNotFoundEnvelope_whenNumericIdIsUnknown() {
        Observation observed = observe(ENDPOINT_BRAND + Long.MAX_VALUE);

        assertAll(
            () -> assertThat(observed.status()).isEqualTo(404),
            () -> assertThat(observed.result()).isEqualTo("FAIL"),
            () -> assertThat(observed.errorCode()).isEqualTo("BRAND_NOT_FOUND"),
            () -> assertThat(observed.hasData()).isFalse()
        );
    }

    @DisplayName("미매핑 URL: 404 / FAIL / errorCode 'Not Found' / data 없음")
    @Test
    void observesNotFoundEnvelope_whenUrlIsUnmapped() {
        Observation observed = observe(ENDPOINT_UNMAPPED);

        assertAll(
            () -> assertThat(observed.status()).isEqualTo(404),
            () -> assertThat(observed.result()).isEqualTo("FAIL"),
            () -> assertThat(observed.errorCode()).isEqualTo("Not Found"),
            () -> assertThat(observed.hasData()).isFalse()
        );
    }
}
