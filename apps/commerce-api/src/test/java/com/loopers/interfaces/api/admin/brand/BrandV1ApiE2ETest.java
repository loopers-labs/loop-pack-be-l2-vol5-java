package com.loopers.interfaces.api.admin.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.admin.AdminMockMvcClient;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
@AutoConfigureMockMvc
class BrandV1ApiE2ETest {

    private static final String ENDPOINT_BRANDS = "/api-admin/v1/brands";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private AdminMockMvcClient adminClient;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @BeforeEach
    void setUp() {
        adminClient = new AdminMockMvcClient(mockMvc, objectMapper);
    }

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
            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = adminClient.exchange(
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
            ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
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
            ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
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
            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = adminClient.exchange(
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
            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = adminClient.exchange(
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
            ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
                ENDPOINT_BRANDS + "/1",
                HttpMethod.GET,
                null,
                responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("PUT /api-admin/v1/brands/{brandId}")
    @Nested
    class Update {
        @DisplayName("삭제되지 않은 Brand면, 200 응답과 수정된 Brand 정보를 반환한다.")
        @Test
        void returnsUpdatedBrand_whenBrandIsNotDeleted() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));
            HttpEntity<BrandV1Dto.UpdateRequest> request = new HttpEntity<>(
                new BrandV1Dto.UpdateRequest("Adidas")
            );

            // act
            ParameterizedTypeReference<ApiResponse<BrandV1Dto.BrandResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = adminClient.exchange(
                ENDPOINT_BRANDS + "/" + brand.getId(),
                HttpMethod.PUT,
                request,
                responseType
            );

            // assert
            Brand savedBrand = brandJpaRepository.findById(brand.getId()).orElseThrow();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().name()).isEqualTo("Adidas"),
                () -> assertThat(savedBrand.getName()).isEqualTo("Adidas")
            );
        }

        @DisplayName("이름이 공백만으로 구성되면, 400 응답을 반환하고 기존 이름을 유지한다.")
        @Test
        void keepsName_whenNameIsBlank() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));
            HttpEntity<BrandV1Dto.UpdateRequest> request = new HttpEntity<>(
                new BrandV1Dto.UpdateRequest(" ")
            );

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
                ENDPOINT_BRANDS + "/" + brand.getId(),
                HttpMethod.PUT,
                request,
                responseType
            );

            // assert
            Brand savedBrand = brandJpaRepository.findById(brand.getId()).orElseThrow();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(savedBrand.getName()).isEqualTo("Nike")
            );
        }

        @DisplayName("삭제된 Brand면, 404 응답을 반환한다.")
        @Test
        void returnsNotFound_whenBrandIsDeleted() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));
            brand.delete();
            brandJpaRepository.save(brand);
            brandJpaRepository.flush();
            HttpEntity<BrandV1Dto.UpdateRequest> request = new HttpEntity<>(
                new BrandV1Dto.UpdateRequest("Adidas")
            );

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
                ENDPOINT_BRANDS + "/" + brand.getId(),
                HttpMethod.PUT,
                request,
                responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("삭제된 Brand와 같은 이름이면, 409 응답을 반환한다.")
        @Test
        void returnsConflict_whenNameMatchesDeletedBrand() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));
            Brand deletedBrand = brandJpaRepository.save(Brand.create("Adidas"));
            deletedBrand.delete();
            brandJpaRepository.save(deletedBrand);
            brandJpaRepository.flush();
            HttpEntity<BrandV1Dto.UpdateRequest> request = new HttpEntity<>(
                new BrandV1Dto.UpdateRequest("Adidas")
            );

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
                ENDPOINT_BRANDS + "/" + brand.getId(),
                HttpMethod.PUT,
                request,
                responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @DisplayName("DELETE /api-admin/v1/brands/{brandId}")
    @Nested
    class Delete {
        @DisplayName("연결된 활성 Product가 없으면, 200 응답과 논리 삭제 결과를 반환한다.")
        @Test
        void deletesBrand_whenNoActiveProductExists() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
                ENDPOINT_BRANDS + "/" + brand.getId(),
                HttpMethod.DELETE,
                null,
                responseType
            );

            // assert
            Brand deletedBrand = brandJpaRepository.findById(brand.getId()).orElseThrow();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(deletedBrand.getDeletedAt()).isNotNull()
            );
        }

        @DisplayName("활성 Product가 연결되어 있으면, 409 응답과 Brand 미삭제를 반환한다.")
        @Test
        void keepsBrand_whenActiveProductExists() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));
            productJpaRepository.save(Product.create(brand, "Air Max", 100_000L));

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
                ENDPOINT_BRANDS + "/" + brand.getId(),
                HttpMethod.DELETE,
                null,
                responseType
            );

            // assert
            Brand savedBrand = brandJpaRepository.findById(brand.getId()).orElseThrow();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
                () -> assertThat(savedBrand.getDeletedAt()).isNull()
            );
        }

        @DisplayName("없는 Brand ID면, 404 응답을 반환한다.")
        @Test
        void returnsNotFound_whenBrandDoesNotExist() {
            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
                ENDPOINT_BRANDS + "/1",
                HttpMethod.DELETE,
                null,
                responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("GET /api-admin/v1/brands")
    @Nested
    class GetList {
        @DisplayName("status를 지정하지 않으면, 활성·삭제 Brand를 모두 200 응답으로 반환한다.")
        @Test
        void returnsAllBrands_whenStatusIsOmitted() {
            // arrange
            Brand activeBrand = brandJpaRepository.save(Brand.create("Nike"));
            Brand deletedBrand = brandJpaRepository.save(Brand.create("Adidas"));
            deletedBrand.delete();
            brandJpaRepository.save(deletedBrand);

            // act
            ParameterizedTypeReference<ApiResponse<List<BrandV1Dto.BrandResponse>>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<List<BrandV1Dto.BrandResponse>>> response = adminClient.exchange(
                ENDPOINT_BRANDS,
                HttpMethod.GET,
                null,
                responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data()).extracting(BrandV1Dto.BrandResponse::id)
                .containsExactlyInAnyOrder(activeBrand.getId(), deletedBrand.getId());
        }

        @DisplayName("status=ACTIVE이면 삭제되지 않은 Brand만 200 응답으로 반환한다.")
        @Test
        void returnsActiveBrands_whenStatusIsActive() {
            // arrange
            Brand activeBrand = brandJpaRepository.save(Brand.create("Nike"));
            Brand deletedBrand = brandJpaRepository.save(Brand.create("Adidas"));
            deletedBrand.delete();
            brandJpaRepository.save(deletedBrand);

            // act
            ParameterizedTypeReference<ApiResponse<List<BrandV1Dto.BrandResponse>>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<List<BrandV1Dto.BrandResponse>>> response = adminClient.exchange(
                ENDPOINT_BRANDS + "?status=ACTIVE",
                HttpMethod.GET,
                null,
                responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data()).extracting(BrandV1Dto.BrandResponse::id)
                .containsExactly(activeBrand.getId());
        }

        @DisplayName("status가 잘못되면, 400 응답을 반환한다.")
        @Test
        void returnsBadRequest_whenStatusIsInvalid() {
            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
                ENDPOINT_BRANDS + "?status=INVALID",
                HttpMethod.GET,
                null,
                responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
