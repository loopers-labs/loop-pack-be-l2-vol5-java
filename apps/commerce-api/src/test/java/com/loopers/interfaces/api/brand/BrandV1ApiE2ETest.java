package com.loopers.interfaces.api.brand;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.user.UserModel;
import com.loopers.fixture.BrandFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.interfaces.api.ApiResponse;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("브랜드 API 는 활성 브랜드의 상세 정보를 제공한다.")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrandV1ApiE2ETest {

    private static final String ENDPOINT = "/api/v1/brands/";
    private static final String USER_ID_HEADER = "X-USER-ID";

    private static final ParameterizedTypeReference<ApiResponse<BrandV1Dto.BrandResponse>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    @Autowired
    private TestRestTemplate testRestTemplate;
    @Autowired
    private UserFixture userFixture;
    @Autowired
    private BrandFixture brandFixture;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private HttpEntity<Object> request(String userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set(USER_ID_HEADER, userId);
        }
        return new HttpEntity<>(null, headers);
    }

    private ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> get(Object brandId, String userId) {
        return testRestTemplate.exchange(ENDPOINT + brandId, HttpMethod.GET, request(userId), RESPONSE_TYPE);
    }

    @DisplayName("GET /api/v1/brands/{brandId}")
    @Nested
    class GetBrand {
        @DisplayName("활성 브랜드의 ID 와 이름을 200 으로 반환한다.")
        @Test
        void returnsActiveBrand() {
            UserModel user = userFixture.createUserWithPoint();
            BrandModel nike = brandFixture.createBrand("나이키");

            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response =
                get(nike.getId(), String.valueOf(user.getId()));

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().id()).isEqualTo(nike.getId()),
                () -> assertThat(response.getBody().data().name()).isEqualTo("나이키")
            );
        }

        @DisplayName("존재하지 않는 브랜드는 404 BRAND_NOT_FOUND 로 응답한다.")
        @Test
        void rejectsUnknownBrand() {
            UserModel user = userFixture.createUserWithPoint();

            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response =
                get(999999L, String.valueOf(user.getId()));

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("BRAND_NOT_FOUND"),
                () -> assertThat(response.getBody().data()).isNull()
            );
        }

        @DisplayName("삭제된 브랜드는 노출하지 않고 404 BRAND_NOT_FOUND 로 응답한다.")
        @Test
        void hidesDeletedBrand() {
            UserModel user = userFixture.createUserWithPoint();
            BrandModel deleted = brandFixture.createDeletedBrand("사라진브랜드");

            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response =
                get(deleted.getId(), String.valueOf(user.getId()));

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("BRAND_NOT_FOUND")
            );
        }

        @DisplayName("X-USER-ID 가 없으면 400 INVALID_REQUEST 로 거절한다.")
        @Test
        void rejectsMissingHeader() {
            BrandModel nike = brandFixture.createBrand("나이키");

            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = get(nike.getId(), null);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("INVALID_REQUEST")
            );
        }

        @DisplayName("저장되지 않은 사용자 ID 로 요청하면 404 USER_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsUnknownUser() {
            BrandModel nike = brandFixture.createBrand("나이키");

            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = get(nike.getId(), "999999");

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("USER_NOT_FOUND")
            );
        }

        @DisplayName("브랜드 ID 가 숫자가 아니면 400 으로 거절한다.")
        @Test
        void rejectsNonNumericBrandId() {
            UserModel user = userFixture.createUserWithPoint();

            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response =
                get("abc", String.valueOf(user.getId()));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
