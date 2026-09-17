package com.loopers.interfaces.api;

import com.loopers.domain.brand.Brand;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.interfaces.api.brand.BrandDto;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrandApiE2ETest {

    private static final String ENDPOINT_BRAND = "/api/v1/brands/";

    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    BrandApiE2ETest(
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

    private ResponseEntity<ApiResponse<BrandDto.BrandResponse>> getBrand(String brandId) {
        ParameterizedTypeReference<ApiResponse<BrandDto.BrandResponse>> responseType = new ParameterizedTypeReference<>() {};
        return testRestTemplate.exchange(ENDPOINT_BRAND + brandId, HttpMethod.GET, new HttpEntity<>(null), responseType);
    }

    @DisplayName("GET /api/v1/brands/{brandId}")
    @Nested
    class GetBrand {

        @DisplayName("존재하는 브랜드 ID를 주면, 브랜드 ID와 이름을 반환한다.")
        @Test
        void returnsBrand_whenBrandExists() {
            // arrange
            Brand brand = brandJpaRepository.save(new Brand("루퍼스"));

            // act
            ResponseEntity<ApiResponse<BrandDto.BrandResponse>> response = getBrand(String.valueOf(brand.getId()));

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().brandId()).isEqualTo(brand.getId()),
                () -> assertThat(response.getBody().data().name()).isEqualTo("루퍼스")
            );
        }

        @DisplayName("존재하지 않는 브랜드 ID를 주면, 404 NOT_FOUND 응답을 받는다. (QRY-001)")
        @Test
        void returnsNotFound_whenBrandDoesNotExist() {
            // act
            ResponseEntity<ApiResponse<BrandDto.BrandResponse>> response = getBrand("999999");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("삭제된 브랜드 ID를 주면, 404 NOT_FOUND 응답을 받는다. (QRY-001)")
        @Test
        void returnsNotFound_whenBrandIsDeleted() {
            // arrange
            Brand brand = brandJpaRepository.save(new Brand("루퍼스"));
            brand.delete();
            brandJpaRepository.save(brand);

            // act
            ResponseEntity<ApiResponse<BrandDto.BrandResponse>> response = getBrand(String.valueOf(brand.getId()));

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("숫자가 아닌 브랜드 ID를 주면, 400 BAD_REQUEST 응답을 받는다.")
        @Test
        void returnsBadRequest_whenBrandIdIsNotNumber() {
            // act
            ResponseEntity<ApiResponse<BrandDto.BrandResponse>> response = getBrand("abc");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
