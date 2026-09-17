package com.loopers.interfaces.api;

import com.loopers.domain.example.ExampleModel;
import com.loopers.infrastructure.example.ExampleJpaRepository;
import com.loopers.interfaces.api.example.ExampleV1Dto;
import com.loopers.utils.DatabaseCleanUp;
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
 * Week 1 관찰 테스트.
 *
 * 기존 /api/v1/examples/{id} 의 현재 동작을 네 입력(정상 / 문법 오류 / 미존재 / 미매핑)으로 나누어
 * HTTP status, meta.result, meta.errorCode, data 유무를 고정한다.
 * 제품 코드(src/main)는 바꾸지 않으며, 여기서 확인한 값은 docs/week1/order-discount-contract.md 의 표와 같아야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractClassificationTest {

    private static final String EXAMPLES_PATH = "/api/v1/examples/";
    private static final String UNMAPPED_PATH = "/api/v1/not-mapped";

    private static final ParameterizedTypeReference<ApiResponse<ExampleV1Dto.ExampleResponse>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    private final TestRestTemplate testRestTemplate;
    private final ExampleJpaRepository exampleJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    ContractClassificationTest(
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

    private ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> get(String path) {
        return testRestTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(null), RESPONSE_TYPE);
    }

    @DisplayName("존재하는 예시 ID를 주면, 해당 예시 정보를 반환한다.")
    @Test
    void existingNumericId_returnsSuccessWithData() {
        // arrange
        ExampleModel saved = exampleJpaRepository.save(new ExampleModel("예시 제목", "예시 설명"));

        // act
        ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response = get(EXAMPLES_PATH + saved.getId());

        // assert
        ApiResponse<ExampleV1Dto.ExampleResponse> body = response.getBody();
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(body).isNotNull(),
            () -> assertThat(body.meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS),
            () -> assertThat(body.meta().errorCode()).isNull(),
            () -> assertThat(body.data()).isNotNull(),
            () -> assertThat(body.data().id()).isEqualTo(saved.getId()),
            () -> assertThat(body.data().name()).isEqualTo(saved.getName()),
            () -> assertThat(body.data().description()).isEqualTo(saved.getDescription())
        );
    }

    @DisplayName("숫자가 아닌 ID 로 요청하면, 400 BAD_REQUEST 응답을 받는다.")
    @Test
    void nonNumericId_returnsBadRequest() {
        // act
        ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response = get(EXAMPLES_PATH + "abc");

        // assert
        ApiResponse<ExampleV1Dto.ExampleResponse> body = response.getBody();
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
            () -> assertThat(body).isNotNull(),
            () -> assertThat(body.meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(body.meta().errorCode()).isEqualTo("BAD_REQUEST"),
            () -> assertThat(body.meta().message()).contains("exampleId").contains("abc"),
            () -> assertThat(body.data()).isNull()
        );
    }

    @DisplayName("존재하지 않는 예시 ID를 주면, 404 NOT_FOUND 응답을 받는다.")
    @Test
    void nonExistingNumericId_returnsNotFound() {
        // arrange
        long missingId = 999_999L;

        // act
        ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response = get(EXAMPLES_PATH + missingId);

        // assert
        ApiResponse<ExampleV1Dto.ExampleResponse> body = response.getBody();
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
            () -> assertThat(body).isNotNull(),
            () -> assertThat(body.meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(body.meta().errorCode()).isEqualTo("NOT_FOUND"),
            () -> assertThat(body.meta().message()).contains("[id = " + missingId + "]"),
            () -> assertThat(body.data()).isNull()
        );
    }

    @DisplayName("연결되지 않은 URL 로 요청하면, 404 NOT_FOUND 응답을 받는다.")
    @Test
    void unmappedUrl_returnsNotFound() {
        // act
        ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response = get(UNMAPPED_PATH);

        // assert
        ApiResponse<ExampleV1Dto.ExampleResponse> body = response.getBody();
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
            () -> assertThat(body).isNotNull(),
            () -> assertThat(body.meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(body.meta().errorCode()).isEqualTo("NOT_FOUND"),
            () -> assertThat(body.meta().message()).isEqualTo("존재하지 않는 요청입니다."),
            () -> assertThat(body.data()).isNull()
        );
    }
}
