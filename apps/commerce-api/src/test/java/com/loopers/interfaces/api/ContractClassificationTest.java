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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractClassificationTest {

    private static final String ENDPOINT_EXAMPLE = "/api/v1/examples/";
    private static final String ENDPOINT_UNMAPPED = "/api/v1/no-such-endpoint";

    private static final ParameterizedTypeReference<ApiResponse<ExampleV1Dto.ExampleResponse>> RESPONSE_TYPE =
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

    @DisplayName("존재하는 숫자 ID는 정상 조회로 구분되어, 200과 SUCCESS와 data를 돌려준다.")
    @Test
    void returnsSuccessWithData_whenExistingNumericIdIsRequested() {
        // arrange
        ExampleModel saved = exampleJpaRepository.save(new ExampleModel("예시 제목", "예시 설명"));

        // act
        ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response = get(ENDPOINT_EXAMPLE + saved.getId());

        // assert
        assertThat(response.getBody()).isNotNull();
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS),
            () -> assertThat(response.getBody().meta().errorCode()).isNull(),
            () -> assertThat(response.getBody().data()).isNotNull(),
            () -> assertThat(response.getBody().data().id()).isEqualTo(saved.getId())
        );
    }

    @DisplayName("숫자가 아닌 ID는 문법 오류로 구분되어, 400과 BAD_REQUEST를 돌려준다.")
    @Test
    void returnsBadRequest_whenIdIsNotNumeric() {
        // act
        ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response = get(ENDPOINT_EXAMPLE + "abc");

        // assert
        assertThat(response.getBody()).isNotNull();
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
            () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("BAD_REQUEST"),
            // 요청자가 고칠 대상을 알 수 있도록 잘못된 값이 메시지에 드러남
            () -> assertThat(response.getBody().meta().message()).contains("abc"),
            () -> assertThat(response.getBody().data()).isNull()
        );
    }

    @DisplayName("존재하지 않는 숫자 ID 는 대상 없음으로 구분되어, 404 와 NOT_FOUND 를 돌려준다.")
    @Test
    void returnsNotFound_whenNumericIdDoesNotExist() {
        // arrange
        long notExistingId = 999_999L;

        // act
        ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response = get(ENDPOINT_EXAMPLE + notExistingId);

        // assert
        assertThat(response.getBody()).isNotNull();
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
            () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("NOT_FOUND"),
            // 도메인이 붙인 메시지라, 아래 미매핑 경로의 기본 메시지와 구분되는 유일한 필드임
            () -> assertThat(response.getBody().meta().message()).contains(String.valueOf(notExistingId)),
            () -> assertThat(response.getBody().data()).isNull()
        );
    }

    @DisplayName("매핑되지 않은 경로는 요청 처리기 없음으로 구분되지만, 대상 없음과 같은 404 와 NOT_FOUND 를 돌려준다.")
    @Test
    void returnsNotFound_whenPathIsNotMapped() {
        // act
        ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response = get(ENDPOINT_UNMAPPED);

        // assert
        assertThat(response.getBody()).isNotNull();
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
            () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("NOT_FOUND"),
            // 도메인 메시지가 아닌 기본 메시지로만 갈리므로, 기계가 읽는 필드로는 두 실패가 구분되지 않음
            () -> assertThat(response.getBody().meta().message()).isEqualTo("존재하지 않는 요청입니다."),
            () -> assertThat(response.getBody().data()).isNull()
        );
    }

    private ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> get(String requestUrl) {
        return testRestTemplate.exchange(requestUrl, HttpMethod.GET, new HttpEntity<>(null), RESPONSE_TYPE);
    }
}
