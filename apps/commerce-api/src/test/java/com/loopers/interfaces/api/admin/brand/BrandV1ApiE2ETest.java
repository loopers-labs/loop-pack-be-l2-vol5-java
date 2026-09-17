package com.loopers.interfaces.api.admin.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.infrastructure.brand.BrandJpaRepository;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrandV1ApiE2ETest {

    private static final String ENDPOINT_BRANDS = "/api-admin/v1/brands";

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("POST /api-admin/v1/brands")
    @Nested
    class Register {
        @DisplayName("유효한 이름을 주면, 201 응답과 생성된 Brand 정보를 반환한다.")
        @Test
        void returnsCreatedBrand_whenNameIsValid() {
            // arrange
            HttpEntity<BrandV1Dto.CreateRequest> request = new HttpEntity<>(
                new BrandV1Dto.CreateRequest("Nike")
            );

            // act
            ParameterizedTypeReference<ApiResponse<BrandV1Dto.BrandResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = testRestTemplate.exchange(
                ENDPOINT_BRANDS,
                HttpMethod.POST,
                request,
                responseType
            );

            // assert
            Brand savedBrand = brandJpaRepository.findByName("Nike").orElseThrow();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED),
                () -> assertThat(response.getBody().data().id()).isEqualTo(savedBrand.getId()),
                () -> assertThat(response.getBody().data().name()).isEqualTo("Nike")
            );
        }

        @DisplayName("이름이 공백만으로 구성되면, 400 응답을 반환한다.")
        @Test
        void returnsBadRequest_whenNameIsBlank() {
            // arrange
            HttpEntity<BrandV1Dto.CreateRequest> request = new HttpEntity<>(
                new BrandV1Dto.CreateRequest(" ")
            );

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                ENDPOINT_BRANDS,
                HttpMethod.POST,
                request,
                responseType
            );

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(brandJpaRepository.count()).isZero()
            );
        }

        @DisplayName("삭제된 Brand와 같은 이름을 주면, 409 응답을 반환한다.")
        @Test
        void returnsConflict_whenNameMatchesDeletedBrand() {
            // arrange
            Brand deletedBrand = brandJpaRepository.save(Brand.create("Nike"));
            deletedBrand.delete();
            brandJpaRepository.save(deletedBrand);
            brandJpaRepository.flush();
            HttpEntity<BrandV1Dto.CreateRequest> request = new HttpEntity<>(
                new BrandV1Dto.CreateRequest("Nike")
            );

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                ENDPOINT_BRANDS,
                HttpMethod.POST,
                request,
                responseType
            );

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
                () -> assertThat(brandJpaRepository.count()).isEqualTo(1L)
            );
        }
    }

    @DisplayName("GET /api-admin/v1/brands/{brandId}")
    @Nested
    class GetDetail {
        @DisplayName("삭제되지 않은 Brand가 있으면, 200 응답과 Brand 정보를 반환한다.")
        @Test
        void returnsBrandInfo_whenBrandExists() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));

            // act
            ParameterizedTypeReference<ApiResponse<BrandV1Dto.BrandResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = testRestTemplate.exchange(
                ENDPOINT_BRANDS + "/" + brand.getId(),
                HttpMethod.GET,
                null,
                responseType
            );

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().id()).isEqualTo(brand.getId()),
                () -> assertThat(response.getBody().data().name()).isEqualTo("Nike"),
                () -> assertThat(response.getBody().data().deleted()).isFalse()
            );
        }

        @DisplayName("삭제된 Brand가 있으면, 200 응답과 삭제 상태를 반환한다.")
        @Test
        void returnsDeletedBrandInfo_whenBrandIsDeleted() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));
            brand.delete();
            brandJpaRepository.save(brand);
            brandJpaRepository.flush();

            // act
            ParameterizedTypeReference<ApiResponse<BrandV1Dto.BrandResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = testRestTemplate.exchange(
                ENDPOINT_BRANDS + "/" + brand.getId(),
                HttpMethod.GET,
                null,
                responseType
            );

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().deleted()).isTrue()
            );
        }

        @DisplayName("없는 Brand ID면, 404 응답을 반환한다.")
        @Test
        void returnsNotFound_whenBrandDoesNotExist() {
            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                ENDPOINT_BRANDS + "/1",
                HttpMethod.GET,
                null,
                responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
