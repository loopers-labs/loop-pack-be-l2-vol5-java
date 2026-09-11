package com.loopers.interfaces.api;

import com.loopers.domain.example.ExampleModel;
import com.loopers.infrastructure.example.ExampleJpaRepository;
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
    private static final String ENDPOINT_UNMAPPED = "/api/v1/not-mapped-at-all";
    private static final long ABSENT_NUMERIC_ID = -1L;

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

    @DisplayName("정상 숫자 ID 로 요청하면, 200 SUCCESS 이고 data 가 있다.")
    @Test
    void observesSuccess_whenExistingNumericIdIsProvided() {
        // arrange
        ExampleModel saved = exampleJpaRepository.save(new ExampleModel("예시 제목", "예시 설명"));
        String requestUrl = ENDPOINT_EXAMPLE + saved.getId();

        // act
        ResponseEntity<ApiResponse<Object>> response = get(requestUrl);

        // assert
        ApiResponse<Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(body.meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS),
            () -> assertThat(body.meta().errorCode()).isNull(),
            () -> assertThat(body.data()).isNotNull()
        );
    }

    @DisplayName("숫자가 아닌 ID 'abc' 로 요청하면, 400 FAIL 이고 error code 는 'Bad Request' 이며 data 가 없다.")
    @Test
    void observesBadRequest_whenIdIsNotNumeric() {
        // arrange
        String requestUrl = ENDPOINT_EXAMPLE + "abc";

        // act
        ResponseEntity<ApiResponse<Object>> response = get(requestUrl);

        // assert
        ApiResponse<Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
            () -> assertThat(body.meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(body.meta().errorCode()).isEqualTo("Bad Request"),
            () -> assertThat(body.data()).isNull()
        );
    }

    @DisplayName("존재하지 않는 숫자 ID 로 요청하면, 404 FAIL 이고 error code 는 'Not Found' 이며 data 가 없다.")
    @Test
    void observesNotFound_whenNumericIdDoesNotExist() {
        // arrange
        String requestUrl = ENDPOINT_EXAMPLE + ABSENT_NUMERIC_ID;

        // act
        ResponseEntity<ApiResponse<Object>> response = get(requestUrl);

        // assert
        ApiResponse<Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
            () -> assertThat(body.meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(body.meta().errorCode()).isEqualTo("Not Found"),
            () -> assertThat(body.data()).isNull()
        );
    }

    @DisplayName("미매핑 URL 로 요청하면, 404 FAIL 이고 error code 는 'Not Found' 이며 data 가 없다.")
    @Test
    void observesNotFound_whenUrlIsNotMapped() {
        // arrange
        String requestUrl = ENDPOINT_UNMAPPED;

        // act
        ResponseEntity<ApiResponse<Object>> response = get(requestUrl);

        // assert
        ApiResponse<Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
            () -> assertThat(body.meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(body.meta().errorCode()).isEqualTo("Not Found"),
            () -> assertThat(body.data()).isNull()
        );
    }

    private ResponseEntity<ApiResponse<Object>> get(String requestUrl) {
        ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
        return testRestTemplate.exchange(requestUrl, HttpMethod.GET, new HttpEntity<>(null), responseType);
    }
}
