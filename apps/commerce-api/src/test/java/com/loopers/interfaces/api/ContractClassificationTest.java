package com.loopers.interfaces.api;

import com.loopers.domain.example.ExampleModel;
import com.loopers.infrastructure.example.ExampleJpaRepository;
import com.loopers.interfaces.api.example.ExampleV1Dto;
import com.loopers.support.test.E2ETest;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@E2ETest
@Tag("example")
class ContractClassificationTest {

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

    @DisplayName("존재하는 숫자 ID는 200, SUCCESS, 예시 데이터를 반환한다.")
    @Test
    void returnsSuccessWithData_whenExampleExists() {
        ExampleModel example = exampleJpaRepository.save(new ExampleModel("Loop:pack", "별거 없네"));

        ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response =
            get("/api/v1/examples/" + example.getId());

        ApiResponse<ExampleV1Dto.ExampleResponse> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.meta()).isNotNull();
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(body.meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS),
            () -> assertThat(body.meta().errorCode()).isNull(),
            () -> {
                assertThat(body.data()).isNotNull();
                assertAll(
                    () -> assertThat(body.data().id()).isEqualTo(example.getId()),
                    () -> assertThat(body.data().name()).isEqualTo(example.getName()),
                    () -> assertThat(body.data().description()).isEqualTo(example.getDescription())
                );
            }
        );
    }

    @DisplayName("숫자가 아닌 ID는 400, FAIL, Bad Request와 null 데이터를 반환한다.")
    @Test
    void returnsBadRequest_whenIdIsNotNumeric() {
        ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response = get("/api/v1/examples/abc");

        assertFailure(response, HttpStatus.BAD_REQUEST, "Bad Request");
    }

    @DisplayName("존재하지 않는 숫자 ID는 404, FAIL, Not Found와 null 데이터를 반환한다.")
    @Test
    void returnsNotFound_whenExampleDoesNotExist() {
        ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response = get("/api/v1/examples/-1");

        assertFailure(response, HttpStatus.NOT_FOUND, "Not Found");
    }

    @DisplayName("미매핑 URL은 404, FAIL, Not Found와 null 데이터를 반환한다.")
    @Test
    void returnsNotFound_whenUrlIsUnmapped() {
        ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response =
            get("/api/v1/unmapped-contract-classification");

        assertFailure(response, HttpStatus.NOT_FOUND, "Not Found");
    }

    private ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> get(String url) {
        ParameterizedTypeReference<ApiResponse<ExampleV1Dto.ExampleResponse>> responseType =
            new ParameterizedTypeReference<>() {};
        return testRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(null), responseType);
    }

    private void assertFailure(
        ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response,
        HttpStatus expectedStatus,
        String expectedErrorCode
    ) {
        ApiResponse<ExampleV1Dto.ExampleResponse> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.meta()).isNotNull();
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(expectedStatus),
            () -> assertThat(body.meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(body.meta().errorCode()).isEqualTo(expectedErrorCode),
            () -> assertThat(body.data()).isNull()
        );
    }
}
