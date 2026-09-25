package com.loopers.interfaces.api.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrandV1ApiE2ETest {

    private static final String ENDPOINT_BRANDS = "/api/v1/brands";

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("GET /api/v1/brands/{brandId}")
    @Nested
    class GetDetail {
        @DisplayName("삭제되지 않은 Brand가 있으면, 200 응답과 Brand 정보를 반환한다.")
        @Test
        void returnsBrandInfo_whenBrandIsActive() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("Nike"));

            // act
            ParameterizedTypeReference<ApiResponse<BrandV1Dto.BrandResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = testRestTemplate.exchange(
                ENDPOINT_BRANDS + "/" + brand.getId(),
                HttpMethod.GET,
                null,
                responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().name()).isEqualTo("Nike");
        }

        @DisplayName("삭제된 Brand면, 404 응답을 반환한다.")
        @Test
        void returnsNotFound_whenBrandIsDeleted() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("Nike"));
            brand.delete();
            brandRepository.save(brand);

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                ENDPOINT_BRANDS + "/" + brand.getId(),
                HttpMethod.GET,
                null,
                responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
