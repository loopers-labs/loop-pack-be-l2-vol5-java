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

    private ResponseEntity<ApiResponse<Object>> requestGet(String url) {
        ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
        return testRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(null), responseType);
    }

    @DisplayName("정상 숫자 ID로 요청하면, 성공 응답을 받는다.")
    @Test
    void validNumericId() {
        // arrange
        ExampleModel saved = exampleJpaRepository.save(
            new ExampleModel("예시 제목", "예시 설명")
        );

        // act
        ResponseEntity<ApiResponse<Object>> response = requestGet("/api/v1/examples/" + saved.getId());

        // assert
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS),
            () -> assertThat(response.getBody().meta().errorCode()).isNull(),
            () -> assertThat(response.getBody().data()).isNotNull()
        );
    }

    @DisplayName("숫자가 아닌 ID(abc)로 요청하면, 실패 응답을 받는다.")
    @Test
    void nonNumericId() {
        // act
        ResponseEntity<ApiResponse<Object>> response = requestGet("/api/v1/examples/abc");

        // assert
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
            () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("Bad Request"),
            () -> assertThat(response.getBody().data()).isNull()
        );
    }

    @DisplayName("존재하지 않는 숫자 ID로 요청하면, 실패 응답을 받는다. (비즈니스 로직에서 못 찾음)")
    @Test
    void notFoundId() {
        // arrange: 데이터를 저장하지 않았으므로 이 id는 존재하지 않는다.
        Long nonExistentId = -1L;
        ResponseEntity<ApiResponse<Object>> response = requestGet("/api/v1/examples/" + nonExistentId);

        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
            () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("Not Found"),
            () -> assertThat(response.getBody().data()).isNull()
        );
    }

    @DisplayName("매핑되지 않은 URL로 요청하면, 실패 응답을 받는다. (라우팅 자체가 없음)")
    @Test
    void unmappedUrl() {
        ResponseEntity<ApiResponse<Object>> response = requestGet("/api/v1/no-such-endpoint");

        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
            () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("Not Found"),
            () -> assertThat(response.getBody().data()).isNull()
        );
    }

}
