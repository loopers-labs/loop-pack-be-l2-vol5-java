package com.loopers.interfaces.api;

import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.loopers.domain.example.ExampleModel;
import com.loopers.infrastructure.example.ExampleJpaRepository;
import com.loopers.interfaces.api.example.ExampleV1Dto;
import com.loopers.utils.DatabaseCleanUp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractClassificationTest {

    private static final Function<String, String> ENDPOINT_GET      = id -> "/api/v1/examples/" + id;
    private static final String                   ENDPOINT_UNMAPPED = "/api/v1/unmapped";

    private final TestRestTemplate     testRestTemplate;
    private final ExampleJpaRepository exampleJpaRepository;
    private final DatabaseCleanUp      databaseCleanUp;

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

    @DisplayName("GET /api/v1/examples/{id}")
    @Nested
    class Get {
        @DisplayName("존재하는 숫자 ID를 요청하면 성공 응답을 반환한다.")
        @Test
        void returnsSuccess_whenExistingNumericIdIsProvided() {
            ExampleModel exampleModel = exampleJpaRepository.save(
                    new ExampleModel("예시 제목", "예시 설명")
            );
            String requestUrl = ENDPOINT_GET.apply(String.valueOf(exampleModel.getId()));

            ParameterizedTypeReference<ApiResponse<ExampleV1Dto.ExampleResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response = testRestTemplate.exchange(requestUrl, HttpMethod.GET, new HttpEntity<>(null), responseType);

            assertThat(response.getBody()).isNotNull();
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS),
                    () -> assertThat(response.getBody().meta().errorCode()).isNull(),
                    () -> assertThat(response.getBody().data()).isNotNull()
            );
        }

        @DisplayName("숫자가 아닌 ID를 요청하면 400 BAD_REQUEST 응답을 반환한다.")
        @Test
        void returnsBadRequest_whenNonNumericIdIsProvided() {
            String requestUrl = ENDPOINT_GET.apply("abc");

            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(requestUrl, HttpMethod.GET, new HttpEntity<>(null), responseType);

            assertThat(response.getBody()).isNotNull();
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                    () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
                    () -> assertThat(response.getBody().meta().errorCode()).isEqualTo(HttpStatus.BAD_REQUEST.getReasonPhrase()),
                    () -> assertThat(response.getBody().data()).isNull()
            );
        }

        @DisplayName("존재하지 않는 숫자 ID를 요청하면 404 NOT_FOUND 응답을 반환한다.")
        @Test
        void returnsNotFound_whenNonExistingNumericIdIsProvided() {
            String requestUrl = ENDPOINT_GET.apply("-1");

            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(requestUrl, HttpMethod.GET, new HttpEntity<>(null), responseType);

            assertThat(response.getBody()).isNotNull();
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                    () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
                    () -> assertThat(response.getBody().meta().errorCode()).isEqualTo(HttpStatus.NOT_FOUND.getReasonPhrase()),
                    () -> assertThat(response.getBody().data()).isNull()
            );
        }
    }

    @DisplayName("GET /api/v1/unmapped")
    @Nested
    class Unmapped {
        @DisplayName("미매핑 URL을 요청하면 404 NOT_FOUND 응답을 반환한다.")
        @Test
        void returnsNotFound_whenUnmappedUrlIsProvided() {
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(ENDPOINT_UNMAPPED, HttpMethod.GET, new HttpEntity<>(null), responseType);

            assertThat(response.getBody()).isNotNull();
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                    () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
                    () -> assertThat(response.getBody().meta().errorCode()).isEqualTo(HttpStatus.NOT_FOUND.getReasonPhrase()),
                    () -> assertThat(response.getBody().data()).isNull()
            );
        }
    }
}
