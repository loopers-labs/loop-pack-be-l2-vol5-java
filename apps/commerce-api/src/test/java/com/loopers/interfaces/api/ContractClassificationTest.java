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

/**
 * 네 가지 요청 입력이 각각 어떤 응답 계약으로 분류되는지 관찰한다.
 *
 * 기존 ExampleV1ApiE2ETest 는 HTTP status 만 검증하므로,
 * 여기서는 meta.result / meta.errorCode / data 유무까지 함께 확인해
 * 설계 문서의 관찰표 근거로 삼는다.
 *
 * 제품 코드는 변경하지 않으며, 현재 동작을 기록하는 것이 목적이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractClassificationTest {

    private static final String ENDPOINT_EXAMPLE = "/api/v1/examples/";
    private static final String ENDPOINT_UNMAPPED = "/api/v1/no-such-endpoint";

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

    private ResponseEntity<ApiResponse<Object>> get(String url) {
        ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
        return testRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(null), responseType);
    }

    @DisplayName("존재하는 숫자 ID 로 요청하면, 2xx 와 SUCCESS meta 및 data 를 반환한다.")
    @Test
    void returnsSuccessContract_whenIdExists() {
        // arrange
        ExampleModel saved = exampleJpaRepository.save(new ExampleModel("예시 제목", "예시 설명"));

        // act
        ResponseEntity<ApiResponse<Object>> response = get(ENDPOINT_EXAMPLE + saved.getId());

        // assert
        ApiResponse<Object> body = response.getBody();
        assertAll(
            () -> assertThat(response.getStatusCode().is2xxSuccessful()).isTrue(),
            () -> assertThat(body).isNotNull(),
            () -> assertThat(body.meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS),
            () -> assertThat(body.meta().errorCode()).isNull(),
            () -> assertThat(body.data()).isNotNull()
        );
    }

    @DisplayName("숫자가 아닌 ID 로 요청하면, 400 과 FAIL meta 를 반환하고 data 는 비어 있다.")
    @Test
    void returnsBadRequestContract_whenIdIsNotNumeric() {
        // act
        ResponseEntity<ApiResponse<Object>> response = get(ENDPOINT_EXAMPLE + "abc");

        // assert
        ApiResponse<Object> body = response.getBody();
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
            () -> assertThat(body).isNotNull(),
            () -> assertThat(body.meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(body.meta().errorCode()).isNotNull(),
            () -> assertThat(body.data()).isNull()
        );
    }

    @DisplayName("존재하지 않는 숫자 ID 로 요청하면, 404 와 FAIL meta 를 반환하고 data 는 비어 있다.")
    @Test
    void returnsNotFoundContract_whenIdDoesNotExist() {
        // act
        ResponseEntity<ApiResponse<Object>> response = get(ENDPOINT_EXAMPLE + "-1");

        // assert
        ApiResponse<Object> body = response.getBody();
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
            () -> assertThat(body).isNotNull(),
            () -> assertThat(body.meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(body.meta().errorCode()).isNotNull(),
            () -> assertThat(body.data()).isNull()
        );
    }

    @DisplayName("연결되지 않은 URL 로 요청하면, 대상 자원 없음과 같은 응답 계약으로 처리된다.")
    @Test
    void returnsNotFoundContract_whenUrlIsUnmapped() {
        // act
        ResponseEntity<ApiResponse<Object>> response = get(ENDPOINT_UNMAPPED);

        // assert
        ApiResponse<Object> body = response.getBody();
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
            () -> assertThat(body).isNotNull(),
            () -> assertThat(body.meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(body.meta().errorCode()).isNotNull(),
            () -> assertThat(body.data()).isNull()
        );
    }
}
