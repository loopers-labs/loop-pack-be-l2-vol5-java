package com.loopers.interfaces.api.brand;

import com.loopers.application.brand.BrandFacade;
import com.loopers.domain.brand.BrandService;
import com.loopers.interfaces.api.ApiResponse;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrandV1ApiE2ETest {

    private static final String CUSTOMER = "/api/v1/brands";

    private final TestRestTemplate testRestTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private final BrandFacade brandFacade;

    @Autowired
    BrandV1ApiE2ETest(
        TestRestTemplate testRestTemplate, BrandService brandService, DatabaseCleanUp databaseCleanUp,
        BrandFacade brandFacade
    ) {
        this.testRestTemplate = testRestTemplate;
        this.databaseCleanUp = databaseCleanUp;
        this.brandFacade = brandFacade;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private static final ParameterizedTypeReference<ApiResponse<BrandV1Dto.BrandResponse>> BRAND =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<Object>> ANY =
        new ParameterizedTypeReference<>() {};

    private ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> get(Long brandId) {
        return testRestTemplate.exchange(CUSTOMER + "/" + brandId, HttpMethod.GET, HttpEntity.EMPTY, BRAND);
    }

    @DisplayName("살아 있는 브랜드의 상세를 돌려준다.")
    @Test
    void returnsBrand() {
        Long id = brandFacade.register("무신사", "패션 플랫폼").getId();

        ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = get(id);

        assertAll(
            () -> assertThat(response.getStatusCode().value()).isEqualTo(200),
            () -> assertThat(response.getBody().data().id()).isEqualTo(id),
            () -> assertThat(response.getBody().data().name()).isEqualTo("무신사"),
            () -> assertThat(response.getBody().data().description()).isEqualTo("패션 플랫폼")
        );
    }

    @DisplayName("BRAND-003 · 없는 브랜드는 404 와 BRAND_NOT_FOUND 다.")
    @Test
    void returnsNotFoundForUnknown() {
        ResponseEntity<ApiResponse<Object>> response =
            testRestTemplate.exchange(CUSTOMER + "/9999", HttpMethod.GET, HttpEntity.EMPTY, ANY);

        assertAll(
            () -> assertThat(response.getStatusCode().value()).isEqualTo(404),
            () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("BRAND_NOT_FOUND")
        );
    }

    @DisplayName("COMMON-006 · 삭제된 브랜드도 없는 브랜드와 같은 404 · 같은 코드로 답한다.")
    @Test
    void hidesDeletedBrand() {
        Long id = brandFacade.register("무신사", "패션 플랫폼").getId();
        brandFacade.delete(id);

        ResponseEntity<ApiResponse<Object>> response =
            testRestTemplate.exchange(CUSTOMER + "/" + id, HttpMethod.GET, HttpEntity.EMPTY, ANY);

        assertAll(
            () -> assertThat(response.getStatusCode().value()).isEqualTo(404),
            () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("BRAND_NOT_FOUND")
        );
    }

    @DisplayName("고객 경로는 관리자 경계와 무관하다 — 인증 없이 부를 수 있다.")
    @Test
    void needsNoAuthentication() {
        Long id = brandFacade.register("무신사", null).getId();

        assertThat(get(id).getStatusCode().value()).isEqualTo(200);
    }
}
