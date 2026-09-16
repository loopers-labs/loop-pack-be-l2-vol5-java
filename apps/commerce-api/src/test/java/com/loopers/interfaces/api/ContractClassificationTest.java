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
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * docs/week1/order-discount-contract.md 관찰 표의 근거 테스트.
 * 네 가지 HTTP 입력(정상 숫자, 숫자가 아닌 ID, 존재하지 않는 숫자 ID, 미매핑 URL)에 대해
 * HTTP status / meta.result / errorCode / data 유무를 관찰하고 문서의 표와 같은 값을 검증한다.
 */
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

    private ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> get(String requestUrl) {
        ParameterizedTypeReference<ApiResponse<ExampleV1Dto.ExampleResponse>> responseType =
            new ParameterizedTypeReference<>() {};
        return testRestTemplate.exchange(requestUrl, HttpMethod.GET, new HttpEntity<>(null), responseType);
    }

    @DisplayName("[Fact] 입력 1: 정상 숫자 ID로 조회하면, 200 / SUCCESS / errorCode 없음 / data 있음.")
    @Test
    void input1_existingResource_returns200WithDataEnvelope() {
        // arrange
        ExampleModel exampleModel = exampleJpaRepository.save(
            new ExampleModel("계약 관찰용 제목", "계약 관찰용 설명")
        );

        // act
        ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response =
            get("/api/v1/examples/" + exampleModel.getId());

        // assert
        assertAll(
            () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
            () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS),
            () -> assertThat(response.getBody().meta().errorCode()).isNull(),
            () -> assertThat(response.getBody().data()).isNotNull(),
            () -> assertThat(response.getBody().data().id()).isEqualTo(exampleModel.getId())
        );
    }

    @DisplayName("[Fact] 입력 2: 숫자가 아닌 ID(abc)로 요청하면, 400 / FAIL / Bad Request / data 없음.")
    @Test
    void input2_nonNumericId_returns400BadRequest() {
        // act
        ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response =
            get("/api/v1/examples/abc");

        // assert
        assertAll(
            () -> assertThat(response.getStatusCode().value()).isEqualTo(400),
            () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("Bad Request"),
            () -> assertThat(response.getBody().data()).isNull()
        );
    }

    @DisplayName("[Fact] 입력 3: 존재하지 않는 숫자 ID로 조회하면, 404 / FAIL / Not Found / data 없음.")
    @Test
    void input3_missingResource_returns404NotFound() {
        // act
        ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response =
            get("/api/v1/examples/999999");

        // assert
        assertAll(
            () -> assertThat(response.getStatusCode().value()).isEqualTo(404),
            () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("Not Found"),
            () -> assertThat(response.getBody().data()).isNull()
        );
    }

    @DisplayName("[Fact] 입력 4: 미매핑 URL로 요청해도, 404 / FAIL / Not Found / data 없음 — 봉투 규약이 유지된다.")
    @Test
    void input4_unmappedUrl_returns404WithEnvelope() {
        // act
        ResponseEntity<ApiResponse<ExampleV1Dto.ExampleResponse>> response =
            get("/api/v1/no-such-resource");

        // assert
        assertAll(
            () -> assertThat(response.getStatusCode().value()).isEqualTo(404),
            () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("Not Found"),
            () -> assertThat(response.getBody().data()).isNull()
        );
    }
}
