package com.loopers.interfaces.api;

import com.loopers.domain.example.ExampleModel;
import com.loopers.infrastructure.example.ExampleJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 네 가지 입력이 현재 시스템에서 각각 어떤 계약으로 응답하는지 관찰한다.
 * 제품 코드를 바꾸지 않고, 이미 존재하는 /api/v1/examples 의 동작만 기록한다.
 *
 * 각 테스트에서 확인할 네 가지:
 *   1) HTTP status
 *   2) meta.result  (SUCCESS / FAIL)
 *   3) meta.errorCode
 *   4) data 유무
 * 관찰 결과는 docs/week1/order-discount-contract.md 의 단일 표에 그대로 옮긴다.
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractClassificationTest {

    private static final ParameterizedTypeReference<ApiResponse<Object>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    private final TestRestTemplate testRestTemplate;
    private final ExampleJpaRepository exampleJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public ContractClassificationTest(
        TestRestTemplate testRestTemplate,
        ExampleJpaRepository exampleJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.exampleJpaRepository = exampleJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private ResponseEntity<ApiResponse<Object>> get(String requestUrl) {
        return testRestTemplate.exchange(requestUrl, HttpMethod.GET, new HttpEntity<>(null), RESPONSE_TYPE);
    }

    @DisplayName("입력 1 · 존재하는 숫자 ID")
    @Test
    void existingNumericId() {
        // arrange
        ExampleModel saved = exampleJpaRepository.save(new ExampleModel("예시 제목", "예시 설명"));

        // act
        ResponseEntity<ApiResponse<Object>> response = get("/api/v1/examples/" + saved.getId());

        // assert
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS),
            () -> assertThat(response.getBody().meta().errorCode()).isNull(),
            () -> assertThat(response.getBody().data()).isNotNull()
        );
    }

    @DisplayName("입력 2 · 숫자가 아닌 ID (abc)")
    @Test
    void nonNumericId() {
        // act
        ResponseEntity<ApiResponse<Object>> response = get("/api/v1/examples/abc");

        // assert
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
            () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("Bad Request"),
            () -> assertThat(response.getBody().data()).isNull()
        );

    }

    @DisplayName("입력 3 · 존재하지 않는 숫자 ID")
    @Test
    void missingNumericId() {
        // act
        ResponseEntity<ApiResponse<Object>> response = get("/api/v1/examples/-1");

        // assert
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
            () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("Not Found"),
            () -> assertThat(response.getBody().data()).isNull()
        );
    }

    @DisplayName("입력 4 · 미매핑 URL")
    @Test
    void unmappedUrl() {
        // act
        ResponseEntity<ApiResponse<Object>> response = get("/api/v1/not-mapped");

        // assert
        // 관찰: 미매핑 URL 도 ApiControllerAdvice 를 거쳐 입력 3 과 동일한 네 값으로 응답한다.
        //       (message 만 "존재하지 않는 요청입니다." 로 다르나 message 는 계약 항목이 아니다)
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
            () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("Not Found"),
            () -> assertThat(response.getBody().data()).isNull()
        );
    }
}
