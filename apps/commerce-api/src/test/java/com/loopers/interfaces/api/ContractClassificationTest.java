package com.loopers.interfaces.api;

import com.loopers.domain.example.ExampleModel;
import com.loopers.infrastructure.example.ExampleJpaRepository;
import com.loopers.interfaces.api.example.ExampleV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractClassificationTest {

    private static final Function<Long, String> ENDPOINT_GET = id -> "/api/v1/examples/" + id;

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

    @DisplayName("GET /api/v1/examples/{id}")
    @Nested
    class Get {
        @DisplayName("존재하는 숫자 ID는 200, SUCCESS, 오류 코드 없음, 조회 데이터를 반환한다.")
        @Test
        void returnsSuccessWithData_whenIdExists() {
            // arrange
            ExampleModel exampleModel = exampleJpaRepository.save(
                new ExampleModel("존재하는 숫자 ID", "정상 숫자 ID 조회")
            );
            String requestUrl = ENDPOINT_GET.apply(exampleModel.getId());

            // act
            ParameterizedTypeReference<ApiResponse<ExampleV1Dto.ExampleResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response =
                testRestTemplate.exchange(requestUrl, HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().meta()).isNotNull();
            assertAll(
                () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS),
                () -> assertThat(response.getBody().meta().errorCode()).isNull(),
                () -> assertThat(response.getBody().data()).isNotNull(),
                () -> assertThat(response.getBody().data().id()).isEqualTo(exampleModel.getId()),
                () -> assertThat(response.getBody().data().name()).isEqualTo(exampleModel.getName()),
                () -> assertThat(response.getBody().data().description()).isEqualTo(exampleModel.getDescription())
            );
        }

        @DisplayName("abc ID는 400, FAIL, Bad Request를 반환하고 data가 없다.")
        @Test
        void returnsBadRequestWithoutData_whenIdIsAbc() {
            // arrange
            String requestUrl = "/api/v1/examples/abc";

            // act
            ParameterizedTypeReference<ApiResponse<ExampleV1Dto.ExampleResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response =
                testRestTemplate.exchange(requestUrl, HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().meta()).isNotNull();
            assertAll(
                () -> assertTrue(response.getStatusCode().is4xxClientError()),
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("Bad Request"),
                () -> assertThat(response.getBody().data()).isNull()
            );
        }

        @DisplayName("존재하지 않는 숫자 ID는 404, FAIL, Not Found를 반환하고 data가 없다.")
        @Test
        void returnsNotFoundWithoutData_whenNumericIdDoesNotExist() {
            // arrange
            Long invalidId = Long.MAX_VALUE;
            assertThat(exampleJpaRepository.existsById(invalidId)).isFalse();
            String requestUrl = ENDPOINT_GET.apply(invalidId);

            // act
            ParameterizedTypeReference<ApiResponse<ExampleV1Dto.ExampleResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response =
                testRestTemplate.exchange(requestUrl, HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().meta()).isNotNull();
            assertAll(
                () -> assertTrue(response.getStatusCode().is4xxClientError()),
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("Not Found"),
                () -> assertThat(response.getBody().data()).isNull()
            );
        }

        @DisplayName("미매핑 URL은 404, FAIL, Not Found를 반환하고 data가 없다.")
        @Test
        void returnsNotFoundWithoutData_whenUrlIsUnmapped() {
            // arrange
            String requestUrl = "/not-mapped";

            // act
            ParameterizedTypeReference<ApiResponse<ExampleV1Dto.ExampleResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response =
                testRestTemplate.exchange(requestUrl, HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().meta()).isNotNull();
            assertAll(
                () -> assertTrue(response.getStatusCode().is4xxClientError()),
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("Not Found"),
                () -> assertThat(response.getBody().data()).isNull()
            );
        }
    }
}
