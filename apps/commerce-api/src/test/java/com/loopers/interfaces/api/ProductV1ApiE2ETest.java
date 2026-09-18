package com.loopers.interfaces.api;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.interfaces.api.product.ProductV1Dto;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductV1ApiE2ETest {

    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public ProductV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        BrandJpaRepository brandJpaRepository,
        ProductJpaRepository productJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("GET /api/v1/products/{productId}")
    @Nested
    class Get {
        @DisplayName("존재하며 삭제되지 않은 상품 id를 주면, 상품 정보와 브랜드명을 반환한다.")
        @Test
        void returnsProduct_whenActiveIdIsProvided() {
            // arrange
            BrandModel brand = brandJpaRepository.save(new BrandModel("나이키"));
            ProductModel product = productJpaRepository.save(new ProductModel(brand.getId(), "runner", 10_000L, 5));

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> response =
                testRestTemplate.exchange("/api/v1/products/" + product.getId(), HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().name()).isEqualTo("runner");
            assertThat(response.getBody().data().brandName()).isEqualTo("나이키");
        }

        @DisplayName("존재하지 않는 상품 id를 주면, 404를 응답한다.")
        @Test
        void returns404_whenIdDoesNotExist() {
            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> response =
                testRestTemplate.exchange("/api/v1/products/999", HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("GET /api/v1/products")
    @Nested
    class GetList {
        @DisplayName("가격 오름차순 정렬을 요청하면, 가격이 낮은 상품부터 반환한다.")
        @Test
        void returnsProductsSortedByPriceAsc_whenSortIsPriceAsc() {
            // arrange
            BrandModel brand = brandJpaRepository.save(new BrandModel("나이키"));
            productJpaRepository.save(new ProductModel(brand.getId(), "expensive", 30_000L, 5));
            productJpaRepository.save(new ProductModel(brand.getId(), "cheap", 10_000L, 5));

            // act
            ParameterizedTypeReference<ApiResponse<java.util.List<ProductV1Dto.ProductResponse>>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<java.util.List<ProductV1Dto.ProductResponse>>> response =
                testRestTemplate.exchange("/api/v1/products?sort=PRICE_ASC", HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().get(0).name()).isEqualTo("cheap");
        }

        @DisplayName("brandId를 지정하면, 해당 브랜드의 상품만 반환한다.")
        @Test
        void returnsOnlyProductsOfBrand_whenBrandIdIsProvided() {
            // arrange
            BrandModel nike = brandJpaRepository.save(new BrandModel("나이키"));
            BrandModel adidas = brandJpaRepository.save(new BrandModel("아디다스"));
            productJpaRepository.save(new ProductModel(nike.getId(), "runner", 10_000L, 5));
            productJpaRepository.save(new ProductModel(adidas.getId(), "tracer", 20_000L, 5));

            // act
            ParameterizedTypeReference<ApiResponse<java.util.List<ProductV1Dto.ProductResponse>>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<java.util.List<ProductV1Dto.ProductResponse>>> response =
                testRestTemplate.exchange("/api/v1/products?brandId=" + nike.getId(), HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert
            assertThat(response.getBody().data()).hasSize(1);
            assertThat(response.getBody().data().get(0).name()).isEqualTo("runner");
        }
    }
}
