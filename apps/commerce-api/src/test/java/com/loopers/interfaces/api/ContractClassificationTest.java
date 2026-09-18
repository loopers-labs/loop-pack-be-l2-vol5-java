package com.loopers.interfaces.api;

import com.loopers.brand.domain.Brand;
import com.loopers.brand.interfaces.BrandV1Dto;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.user.domain.User;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractClassificationTest {

    private static final String BRANDS_ENDPOINT = "/api/v1/brands/";
    private static final String UNMAPPED_ENDPOINT = "/api/v1/unmapped";
    private static final String BRAND_NAME = "Nike";
    private static final String USER_HEADER = "X-USER-ID";
    private static final long MISSING_ID = -1L;

    private final TestRestTemplate testRestTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private final EntityManager entityManager;
    private final PlatformTransactionManager transactionManager;

    private CommerceFixture fixture;
    private User requester;

    @Autowired
    ContractClassificationTest(
        TestRestTemplate testRestTemplate,
        DatabaseCleanUp databaseCleanUp,
        EntityManager entityManager,
        PlatformTransactionManager transactionManager
    ) {
        this.testRestTemplate = testRestTemplate;
        this.databaseCleanUp = databaseCleanUp;
        this.entityManager = entityManager;
        this.transactionManager = transactionManager;
    }

    @BeforeEach
    void setUp() {
        fixture = new CommerceFixture(entityManager, transactionManager);
        requester = fixture.user();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("존재하는 숫자 ID는 성공 계약으로 분류한다.")
    @Test
    void classifiesExistingNumericIdAsSuccess() {
        // arrange
        Brand brand = fixture.brand(BRAND_NAME);

        // act
        ResponseEntity<ApiResponse<BrandV1Dto.CustomerBrandResponse>> response = get(BRANDS_ENDPOINT + brand.getId());
        ApiResponse<BrandV1Dto.CustomerBrandResponse> body = response.getBody();

        // assert
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(body).isNotNull(),
            () -> assertThat(body.meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS),
            () -> assertThat(body.meta().errorCode()).isNull(),
            () -> assertThat(body.meta().message()).isNull(),
            () -> assertThat(body.data()).isNotNull(),
            () -> assertThat(body.data().id()).isEqualTo(brand.getId()),
            () -> assertThat(body.data().name()).isEqualTo(BRAND_NAME)
        );
    }

    @DisplayName("숫자가 아닌 ID는 문법 오류 계약으로 분류하고, 잘못된 파라미터와 값을 message 로 알린다.")
    @Test
    void classifiesNonNumericIdAsBadRequest() {
        // act
        ResponseEntity<ApiResponse<BrandV1Dto.CustomerBrandResponse>> response = get(BRANDS_ENDPOINT + "abc");
        ApiResponse<BrandV1Dto.CustomerBrandResponse> body = response.getBody();

        // assert
        assertFailureContract(
            response,
            body,
            HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST",
            "요청 파라미터 'brandId' (타입: Long)의 값 'abc'이(가) 잘못되었습니다."
        );
    }

    @DisplayName("존재하지 않는 숫자 ID는 자원 미존재 계약으로 분류하고, 찾지 못한 ID를 message 로 알린다.")
    @Test
    void classifiesMissingNumericIdAsNotFound() {
        // act
        ResponseEntity<ApiResponse<BrandV1Dto.CustomerBrandResponse>> response = get(BRANDS_ENDPOINT + MISSING_ID);
        ApiResponse<BrandV1Dto.CustomerBrandResponse> body = response.getBody();

        // assert
        assertFailureContract(
            response,
            body,
            HttpStatus.NOT_FOUND,
            "BRAND_NOT_FOUND",
            "브랜드를 찾을 수 없습니다."
        );
    }

    @DisplayName("연결되지 않은 URL은 미매핑 계약으로 분류하고, 찾지 못한 자원을 특정하지 않는다.")
    @Test
    void classifiesUnmappedUrlAsNotFound() {
        // act
        ResponseEntity<ApiResponse<BrandV1Dto.CustomerBrandResponse>> response = get(UNMAPPED_ENDPOINT);
        ApiResponse<BrandV1Dto.CustomerBrandResponse> body = response.getBody();

        // assert
        assertFailureContract(
            response,
            body,
            HttpStatus.NOT_FOUND,
            "NOT_FOUND",
            "요청한 경로를 찾을 수 없습니다."
        );
    }

    @DisplayName("미존재 자원과 미매핑 URL은 모두 404지만 서로 다른 오류 코드로 분류한다.")
    @Test
    void distinguishesMissingResourceFromUnmappedUrl() {
        // act
        ResponseEntity<ApiResponse<BrandV1Dto.CustomerBrandResponse>> missing = get(BRANDS_ENDPOINT + MISSING_ID);
        ResponseEntity<ApiResponse<BrandV1Dto.CustomerBrandResponse>> unmapped = get(UNMAPPED_ENDPOINT);
        ApiResponse<BrandV1Dto.CustomerBrandResponse> missingBody = missing.getBody();
        ApiResponse<BrandV1Dto.CustomerBrandResponse> unmappedBody = unmapped.getBody();

        // assert
        assertAll(
            () -> assertThat(missing.getStatusCode()).isEqualTo(unmapped.getStatusCode()),
            () -> assertThat(missingBody.meta().result()).isEqualTo(unmappedBody.meta().result()),
            () -> assertThat(missingBody.meta().errorCode()).isEqualTo("BRAND_NOT_FOUND"),
            () -> assertThat(unmappedBody.meta().errorCode()).isEqualTo("NOT_FOUND"),
            () -> assertThat(missingBody.data()).isNull(),
            () -> assertThat(unmappedBody.data()).isNull(),
            () -> assertThat(missingBody.meta().message()).isNotBlank(),
            () -> assertThat(unmappedBody.meta().message()).isNotBlank(),
            () -> assertThat(missingBody.meta().message()).isNotEqualTo(unmappedBody.meta().message())
        );
    }

    @DisplayName("값이 없는 meta 필드와 data 는 응답 본문에서 키까지 생략된다.")
    @Test
    void omitsNullFieldsFromResponseBody() {
        // arrange
        Brand brand = fixture.brand(BRAND_NAME);

        // act
        String successBody = getRaw(BRANDS_ENDPOINT + brand.getId());
        String failureBody = getRaw(BRANDS_ENDPOINT + "abc");

        // assert
        assertAll(
            () -> assertThat(successBody).contains("\"result\":\"SUCCESS\"").contains("\"data\""),
            () -> assertThat(successBody).doesNotContain("errorCode").doesNotContain("message"),
            () -> assertThat(failureBody).contains("\"result\":\"FAIL\"").contains("errorCode").contains("message"),
            () -> assertThat(failureBody).doesNotContain("\"data\"")
        );
    }

    private String getRaw(String endpoint) {
        HttpHeaders headers = requesterHeaders();
        return testRestTemplate.exchange(endpoint, HttpMethod.GET, new HttpEntity<>(null, headers), String.class)
            .getBody();
    }

    private ResponseEntity<ApiResponse<BrandV1Dto.CustomerBrandResponse>> get(String endpoint) {
        ParameterizedTypeReference<ApiResponse<BrandV1Dto.CustomerBrandResponse>> responseType =
            new ParameterizedTypeReference<>() {};
        return testRestTemplate.exchange(
            endpoint,
            HttpMethod.GET,
            new HttpEntity<>(null, requesterHeaders()),
            responseType
        );
    }

    private HttpHeaders requesterHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(USER_HEADER, requester.getId().toString());
        return headers;
    }

    private void assertFailureContract(
        ResponseEntity<ApiResponse<BrandV1Dto.CustomerBrandResponse>> response,
        ApiResponse<BrandV1Dto.CustomerBrandResponse> body,
        HttpStatus expectedStatus,
        String expectedErrorCode,
        String expectedMessage
    ) {
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(expectedStatus),
            () -> assertThat(body).isNotNull(),
            () -> assertThat(body.meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(body.meta().errorCode()).isEqualTo(expectedErrorCode),
            () -> assertThat(body.meta().message()).isEqualTo(expectedMessage),
            () -> assertThat(body.data()).isNull()
        );
    }
}
