package com.loopers.interfaces.api.brand;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrandV1ApiE2ETest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("BRAND-HTTP-01: 헤더 없이 요청한 브랜드를 200과 정확한 성공 JSON으로 반환한다.")
    @Test
    void returnsRequestedBrandWithoutUserHeader() throws JsonProcessingException {
        brandRepository.save(new Brand("다른 브랜드"));
        Brand target = brandRepository.save(new Brand("  조회할 브랜드  "));
        var before = storedBrands();

        ResponseEntity<JsonNode> response = restTemplate.getForEntity(
            "/api/v1/brands/" + target.getId(), JsonNode.class);

        assertResponse(response, HttpStatus.OK, successBody(target.getId(), "조회할 브랜드"), before);
    }

    @DisplayName("BRAND-HTTP-02: 없는 브랜드는 404와 BRAND_NOT_FOUND를 반환하고 DB를 보존한다.")
    @Test
    void rejectsMissingBrand() {
        brandRepository.save(new Brand("기존 브랜드"));
        var before = storedBrands();

        ResponseEntity<JsonNode> response = restTemplate.getForEntity(
            "/api/v1/brands/" + Long.MAX_VALUE, JsonNode.class);

        assertResponse(response, HttpStatus.NOT_FOUND,
            failureBody("BRAND_NOT_FOUND", "브랜드를 찾을 수 없습니다."), before);
    }

    @DisplayName("BRAND-HTTP-03: 삭제된 브랜드도 같은 404 JSON을 반환하고 삭제·감사 시각을 보존한다.")
    @Test
    void rejectsDeletedBrand() {
        brandRepository.save(new Brand("노출 가능한 브랜드"));
        Brand deleted = brandRepository.save(new Brand("삭제된 브랜드"));
        LocalDateTime deletedAtUtc = LocalDateTime.of(2026, 9, 18, 3, 0);
        assertThat(jdbcTemplate.update("UPDATE brand SET deleted_at = ? WHERE id = ?",
            deletedAtUtc, deleted.getId())).isEqualTo(1);
        var before = storedBrands();

        ResponseEntity<JsonNode> response = restTemplate.getForEntity(
            "/api/v1/brands/" + deleted.getId(), JsonNode.class);

        assertResponse(response, HttpStatus.NOT_FOUND,
            failureBody("BRAND_NOT_FOUND", "브랜드를 찾을 수 없습니다."), before);
    }

    @DisplayName("BRAND-HTTP-04: 문자·소수·0·음수·Long 범위 초과 ID를 400으로 거절한다.")
    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "0", "-1", "9223372036854775808", "-9223372036854775809"})
    void rejectsInvalidBrandId(String brandId) {
        brandRepository.save(new Brand("기존 브랜드"));
        var before = storedBrands();

        ResponseEntity<JsonNode> response = restTemplate.getForEntity(
            "/api/v1/brands/" + brandId, JsonNode.class);

        assertResponse(response, HttpStatus.BAD_REQUEST,
            failureBody("INVALID_REQUEST", "요청 값이 올바르지 않습니다."), before);
    }

    @DisplayName("BRAND-HTTP-05: 엔티티 복원 실패는 내부 상세 없이 500을 반환하고 DB를 보존한다.")
    @Test
    void hidesUnexpectedEntityReadFailure() {
        Brand invalid = brandRepository.save(new Brand("정상으로 저장한 브랜드"));
        // 저장 데이터 오류를 준비해 실제 BrandName 복원 실패를 유도한다.
        assertThat(jdbcTemplate.update("UPDATE brand SET name = ? WHERE id = ?", " ", invalid.getId()))
            .isEqualTo(1);
        var before = storedBrands();

        ResponseEntity<JsonNode> response = restTemplate.getForEntity(
            "/api/v1/brands/" + invalid.getId(), JsonNode.class);

        assertResponse(response, HttpStatus.INTERNAL_SERVER_ERROR,
            failureBody("INTERNAL_ERROR", "일시적인 오류가 발생했습니다."), before);
    }

    @DisplayName("BRAND-HTTP-06: 전달된 사용자 헤더의 값과 관계없이 같은 공개 브랜드를 조회한다.")
    @ParameterizedTest
    @ValueSource(strings = {"alice", "admin", "unknown-user", "", " alice "})
    void ignoresProvidedUserHeader(String externalId) throws JsonProcessingException {
        Brand target = brandRepository.save(new Brand("공개 브랜드"));
        var before = storedBrands();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", externalId);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
            "/api/v1/brands/" + target.getId(), HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

        assertResponse(response, HttpStatus.OK, successBody(target.getId(), "공개 브랜드"), before);
    }

    private void assertResponse(ResponseEntity<JsonNode> response, HttpStatus status, JsonNode expectedBody,
                                List<Map<String, Object>> before) {
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(status),
            () -> assertThat(response.getBody()).isEqualTo(expectedBody),
            () -> assertThat(storedBrands()).isEqualTo(before)
        );
    }

    private JsonNode successBody(long brandId, String name) throws JsonProcessingException {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.putObject("meta").put("result", "SUCCESS");
        body.putObject("data").put("brandId", brandId).put("name", name);
        return objectMapper.readTree(body.toString());
    }

    private JsonNode failureBody(String errorCode, String message) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.putObject("meta").put("result", "FAIL")
            .put("errorCode", errorCode).put("message", message);
        return body;
    }

    private List<Map<String, Object>> storedBrands() {
        return jdbcTemplate.queryForList(
            "SELECT id, name, created_at, updated_at, deleted_at FROM brand ORDER BY id");
    }
}
