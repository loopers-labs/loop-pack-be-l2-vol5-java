package com.loopers.common.in;

import com.loopers.common.in.error.ErrorType;
import com.loopers.domains.brand.app.BrandApplicationService;
import com.loopers.domains.brand.in.BrandController;
import com.loopers.domains.user.out.UserJpaEntity;
import com.loopers.domains.user.out.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractClassificationTest {

    private static final Function<Object, String> ENDPOINT_GET = id -> "/api/v1/brands/" + id;

    private final TestRestTemplate testRestTemplate;
    private final BrandApplicationService brandApplicationService;
    private final UserJpaRepository userJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public ContractClassificationTest(
            TestRestTemplate testRestTemplate,
            BrandApplicationService brandApplicationService,
            UserJpaRepository userJpaRepository,
            DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.brandApplicationService = brandApplicationService;
        this.userJpaRepository = userJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    // 브랜드 상세는 식별된 고객만 조회하므로, 오류 분류만 보도록 존재하는 사용자로 요청한다.
    @BeforeEach
    void setUp() {
        userJpaRepository.save(new UserJpaEntity(1L));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("GET /api/v1/brands/{brandId}")
    @Nested
    class Get {

        @DisplayName("정상 숫자 ID를 요청하면 200 OK, meta.result SUCCESS, error code null, data 존재를 반환한다.")
        @Test
        void whenValidNumericIdProvided() {
            // arrange
            long brandId = brandApplicationService.create("브랜드").id();
            String requestUrl = ENDPOINT_GET.apply(brandId);

            // act
            ParameterizedTypeReference<ApiResponse<BrandController.DetailResponse>> responseType = new ParameterizedTypeReference<>() {
            };
            ResponseEntity<ApiResponse<BrandController.DetailResponse>> response =
                    testRestTemplate.exchange(requestUrl, HttpMethod.GET, new HttpEntity<>(customerHeaders()), responseType);

            // assert
            assertAll(
                    () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS),
                    () -> assertThat(response.getBody().meta().errorCode()).isNull(),
                    () -> assertThat(response.getBody().data()).isNotNull(),
                    () -> assertThat(response.getBody().data().id()).isEqualTo(brandId),
                    () -> assertThat(response.getBody().data().name()).isEqualTo("브랜드")
            );
        }

        @DisplayName("abc를 요청하면 400 BAD_REQUEST, meta.result FAIL, error code 'Bad Request', data null을 반환한다.")
        @Test
        void whenAbcIdProvided() {
            // arrange
            String requestUrl = ENDPOINT_GET.apply("abc");

            // act
            ParameterizedTypeReference<ApiResponse<BrandController.DetailResponse>> responseType = new ParameterizedTypeReference<>() {
            };
            ResponseEntity<ApiResponse<BrandController.DetailResponse>> response =
                    testRestTemplate.exchange(requestUrl, HttpMethod.GET, new HttpEntity<>(customerHeaders()), responseType);

            // assert
            assertAll(
                    () -> assertTrue(response.getStatusCode().is4xxClientError()),
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
                    () -> assertThat(response.getBody().meta().errorCode()).isEqualTo(ErrorType.BAD_REQUEST.getCode()),
                    () -> assertThat(response.getBody().data()).isNull()
            );
        }

        @DisplayName("존재하지 않는 숫자 ID를 요청하면 404 NOT_FOUND, meta.result FAIL, error code 'Not Found', data null을 반환한다.")
        @Test
        void whenNonExistentNumericIdProvided() {
            // arrange
            Long nonExistentId = -1L;
            String requestUrl = ENDPOINT_GET.apply(nonExistentId);

            // act
            ParameterizedTypeReference<ApiResponse<BrandController.DetailResponse>> responseType = new ParameterizedTypeReference<>() {
            };
            ResponseEntity<ApiResponse<BrandController.DetailResponse>> response =
                    testRestTemplate.exchange(requestUrl, HttpMethod.GET, new HttpEntity<>(customerHeaders()), responseType);

            // assert
            assertAll(
                    () -> assertTrue(response.getStatusCode().is4xxClientError()),
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
                    () -> assertThat(response.getBody().meta().errorCode()).isEqualTo(ErrorType.NOT_FOUND.getCode()),
                    () -> assertThat(response.getBody().data()).isNull()
            );
        }

        @DisplayName("미매핑 URL을 요청하면 404 NOT_FOUND, meta.result FAIL, error code 'Not Found', data null을 반환한다.")
        @Test
        void whenUnmappedUrlProvided() {
            // arrange
            String requestUrl = "/api/v2/brands/1";

            // act
            ParameterizedTypeReference<ApiResponse<BrandController.DetailResponse>> responseType = new ParameterizedTypeReference<>() {
            };
            ResponseEntity<ApiResponse<BrandController.DetailResponse>> response =
                    testRestTemplate.exchange(requestUrl, HttpMethod.GET, new HttpEntity<>(customerHeaders()), responseType);

            // assert
            assertAll(
                    () -> assertTrue(response.getStatusCode().is4xxClientError()),
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
                    () -> assertThat(response.getBody().meta().errorCode()).isEqualTo(ErrorType.NOT_FOUND.getCode()),
                    () -> assertThat(response.getBody().data()).isNull()
            );
        }
    }

    private static HttpHeaders customerHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", "1");
        return headers;
    }
}
