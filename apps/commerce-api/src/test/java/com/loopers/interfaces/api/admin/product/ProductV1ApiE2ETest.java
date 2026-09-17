package com.loopers.interfaces.api.admin.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
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
class ProductV1ApiE2ETest {

    private static final String ENDPOINT_PRODUCTS = "/api-admin/v1/products";

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("POST /api-admin/v1/products")
    @Nested
    class Register {
        @DisplayName("삭제되지 않은 Brand를 참조하면, 201 응답과 생성된 Product 정보를 반환한다.")
        @Test
        void returnsCreatedProduct_whenBrandIsNotDeleted() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));
            HttpEntity<ProductV1Dto.CreateRequest> request = new HttpEntity<>(
                new ProductV1Dto.CreateRequest(brand.getId(), "Air Max", 100_000L)
            );

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> response = testRestTemplate.exchange(
                ENDPOINT_PRODUCTS,
                HttpMethod.POST,
                request,
                responseType
            );

            // assert
            Product savedProduct = productJpaRepository.findById(response.getBody().data().id()).orElseThrow();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED),
                () -> assertThat(response.getBody().data().brandId()).isEqualTo(brand.getId()),
                () -> assertThat(response.getBody().data().name()).isEqualTo("Air Max"),
                () -> assertThat(response.getBody().data().price()).isEqualTo(100_000L),
                () -> assertThat(response.getBody().data().stock()).isZero(),
                () -> assertThat(savedProduct.getStock().amount()).isZero()
            );
        }

        @DisplayName("이름이 공백만으로 구성되면, 400 응답을 반환하고 Product를 저장하지 않는다.")
        @Test
        void returnsBadRequest_whenNameIsBlank() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));
            HttpEntity<ProductV1Dto.CreateRequest> request = new HttpEntity<>(
                new ProductV1Dto.CreateRequest(brand.getId(), " ", 100_000L)
            );

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                ENDPOINT_PRODUCTS,
                HttpMethod.POST,
                request,
                responseType
            );

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(productJpaRepository.count()).isZero()
            );
        }

        @DisplayName("삭제된 Brand를 참조하면, 404 응답을 반환하고 Product를 저장하지 않는다.")
        @Test
        void returnsNotFound_whenBrandIsDeleted() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));
            brand.delete();
            brandJpaRepository.save(brand);
            brandJpaRepository.flush();
            HttpEntity<ProductV1Dto.CreateRequest> request = new HttpEntity<>(
                new ProductV1Dto.CreateRequest(brand.getId(), "Air Max", 100_000L)
            );

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                ENDPOINT_PRODUCTS,
                HttpMethod.POST,
                request,
                responseType
            );

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(productJpaRepository.count()).isZero()
            );
        }
    }

    @DisplayName("PUT /api-admin/v1/products/{productId}/stock")
    @Nested
    class ChangeStock {
        @DisplayName("0 이상인 최종 수량이면, 200 응답과 변경된 재고를 반환한다.")
        @Test
        void returnsUpdatedStock_whenQuantityIsZeroOrMore() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));
            Product product = productJpaRepository.save(Product.create(brand, "Air Max", 100_000L));
            HttpEntity<ProductV1Dto.StockUpdateRequest> request = new HttpEntity<>(
                new ProductV1Dto.StockUpdateRequest(5L)
            );

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.StockResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.StockResponse>> response = testRestTemplate.exchange(
                ENDPOINT_PRODUCTS + "/" + product.getId() + "/stock",
                HttpMethod.PUT,
                request,
                responseType
            );

            // assert
            Product savedProduct = productJpaRepository.findById(product.getId()).orElseThrow();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().productId()).isEqualTo(product.getId()),
                () -> assertThat(response.getBody().data().stock()).isEqualTo(5L),
                () -> assertThat(savedProduct.getStock().amount()).isEqualTo(5L)
            );
        }

        @DisplayName("음수인 최종 수량이면, 400 응답과 기존 재고를 유지한다.")
        @Test
        void keepsStock_whenQuantityIsNegative() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));
            Product product = productJpaRepository.save(Product.create(brand, "Air Max", 100_000L));
            product.changeStockTo(5L);
            productJpaRepository.save(product);
            HttpEntity<ProductV1Dto.StockUpdateRequest> request = new HttpEntity<>(
                new ProductV1Dto.StockUpdateRequest(-1L)
            );

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                ENDPOINT_PRODUCTS + "/" + product.getId() + "/stock",
                HttpMethod.PUT,
                request,
                responseType
            );

            // assert
            Product savedProduct = productJpaRepository.findById(product.getId()).orElseThrow();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(savedProduct.getStock().amount()).isEqualTo(5L)
            );
        }

        @DisplayName("삭제된 Product면, 404 응답을 반환한다.")
        @Test
        void returnsNotFound_whenProductIsDeleted() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));
            Product product = productJpaRepository.save(Product.create(brand, "Air Max", 100_000L));
            product.delete();
            productJpaRepository.save(product);
            productJpaRepository.flush();
            HttpEntity<ProductV1Dto.StockUpdateRequest> request = new HttpEntity<>(
                new ProductV1Dto.StockUpdateRequest(5L)
            );

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                ENDPOINT_PRODUCTS + "/" + product.getId() + "/stock",
                HttpMethod.PUT,
                request,
                responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
