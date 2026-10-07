package com.loopers.interfaces.api;

import com.loopers.domain.brand.BrandModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.interfaces.api.brand.BrandV1Dto;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrandV1ApiE2ETest {

    private static final ParameterizedTypeReference<ApiResponse<BrandV1Dto.BrandResponse>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public BrandV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        BrandJpaRepository brandJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.brandJpaRepository = brandJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> getBrand(Long brandId) {
        return testRestTemplate.exchange(
            "/api/v1/brands/" + brandId, HttpMethod.GET, new HttpEntity<>(null), RESPONSE_TYPE
        );
    }

    @DisplayName("GET /api/v1/brands/{brandId} — 존재하는 브랜드를 조회한다.")
    @Test
    void returnsBrand_whenBrandExists() {
        // arrange
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));

        // act
        ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = getBrand(brand.getId());

        // assert
        assertAll(
            () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
            () -> assertThat(response.getBody().data().name()).isEqualTo("브랜드A")
        );
    }

    @DisplayName("존재하지 않는 브랜드는, 404 NOT_FOUND로 응답한다.")
    @Test
    void returns404_whenBrandDoesNotExist() {
        // act
        ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = getBrand(999_999L);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @DisplayName("삭제된 브랜드는, 404 NOT_FOUND로 응답한다.")
    @Test
    void returns404_whenBrandIsDeleted() {
        // arrange
        BrandModel brand = new BrandModel("삭제된 브랜드");
        brand.delete();
        brand = brandJpaRepository.save(brand);

        // act
        ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = getBrand(brand.getId());

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
