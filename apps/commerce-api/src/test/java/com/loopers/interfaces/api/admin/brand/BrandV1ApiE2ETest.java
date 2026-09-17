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
            Brand deletedBrand = brandJpaRepository.save(new Brand("Nike"));
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
}
