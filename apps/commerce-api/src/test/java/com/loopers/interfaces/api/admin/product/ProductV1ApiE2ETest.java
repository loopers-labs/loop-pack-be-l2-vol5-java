package com.loopers.interfaces.api.admin.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.ProductRepository;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
@AutoConfigureMockMvc
class ProductV1ApiE2ETest {

    private static final String ENDPOINT_PRODUCTS = "/api-admin/v1/products";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private AdminMockMvcClient adminClient;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

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

    @DisplayName("POST /api-admin/v1/products")
    @Nested
    class Register {
        @DisplayName("삭제되지 않은 Brand를 참조하면, 201 응답과 생성된 Product 정보를 반환한다.")
        @Test
        void returnsCreatedProduct_whenBrandIsNotDeleted() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("Nike"));
            HttpEntity<ProductV1Dto.CreateRequest> request = new HttpEntity<>(
                new ProductV1Dto.CreateRequest(brand.getId(), "Air Max", 100_000L)
            );

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> response = adminClient.exchange(
                ENDPOINT_PRODUCTS,
                HttpMethod.POST,
                request,
                responseType
            );

            // assert
            Product savedProduct = productRepository.findById(response.getBody().data().id()).orElseThrow();
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
            Brand brand = brandRepository.save(Brand.create("Nike"));
            HttpEntity<ProductV1Dto.CreateRequest> request = new HttpEntity<>(
                new ProductV1Dto.CreateRequest(brand.getId(), " ", 100_000L)
            );

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
                ENDPOINT_PRODUCTS,
                HttpMethod.POST,
                request,
                responseType
            );

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(productRepository.findAll()).isEmpty()
            );
        }

        @DisplayName("삭제된 Brand를 참조하면, 404 응답을 반환하고 Product를 저장하지 않는다.")
        @Test
        void returnsNotFound_whenBrandIsDeleted() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("Nike"));
            brand.delete();
            brandRepository.save(brand);
            HttpEntity<ProductV1Dto.CreateRequest> request = new HttpEntity<>(
                new ProductV1Dto.CreateRequest(brand.getId(), "Air Max", 100_000L)
            );

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
                ENDPOINT_PRODUCTS,
                HttpMethod.POST,
                request,
                responseType
            );

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(productRepository.findAll()).isEmpty()
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
            Brand brand = brandRepository.save(Brand.create("Nike"));
            Product product = productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));
            HttpEntity<ProductV1Dto.StockUpdateRequest> request = new HttpEntity<>(
                new ProductV1Dto.StockUpdateRequest(5L)
            );

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.StockResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.StockResponse>> response = adminClient.exchange(
                ENDPOINT_PRODUCTS + "/" + product.getId() + "/stock",
                HttpMethod.PUT,
                request,
                responseType
            );

            // assert
            Product savedProduct = productRepository.findById(product.getId()).orElseThrow();
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
            Brand brand = brandRepository.save(Brand.create("Nike"));
            Product product = productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));
            product.changeStockTo(5L);
            productRepository.save(product);
            HttpEntity<ProductV1Dto.StockUpdateRequest> request = new HttpEntity<>(
                new ProductV1Dto.StockUpdateRequest(-1L)
            );

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
                ENDPOINT_PRODUCTS + "/" + product.getId() + "/stock",
                HttpMethod.PUT,
                request,
                responseType
            );

            // assert
            Product savedProduct = productRepository.findById(product.getId()).orElseThrow();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(savedProduct.getStock().amount()).isEqualTo(5L)
            );
        }

        @DisplayName("삭제된 Product면, 404 응답을 반환한다.")
        @Test
        void returnsNotFound_whenProductIsDeleted() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("Nike"));
            Product product = productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));
            product.delete();
            productRepository.save(product);
            HttpEntity<ProductV1Dto.StockUpdateRequest> request = new HttpEntity<>(
                new ProductV1Dto.StockUpdateRequest(5L)
            );

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
                ENDPOINT_PRODUCTS + "/" + product.getId() + "/stock",
                HttpMethod.PUT,
                request,
                responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("GET /api-admin/v1/products/{productId}")
    @Nested
    class GetDetail {
        @DisplayName("Product가 있으면, 200 응답과 상품 정보를 반환한다.")
        @Test
        void returnsProductInfo_whenProductExists() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("Nike"));
            Product product = productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));
            product.changeStockTo(5L);
            productRepository.save(product);

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> response = adminClient.exchange(
                ENDPOINT_PRODUCTS + "/" + product.getId(),
                HttpMethod.GET,
                null,
                responseType
            );

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().id()).isEqualTo(product.getId()),
                () -> assertThat(response.getBody().data().brandId()).isEqualTo(brand.getId()),
                () -> assertThat(response.getBody().data().name()).isEqualTo("Air Max"),
                () -> assertThat(response.getBody().data().price()).isEqualTo(100_000L),
                () -> assertThat(response.getBody().data().stock()).isEqualTo(5L),
                () -> assertThat(response.getBody().data().deleted()).isFalse()
            );
        }

        @DisplayName("삭제된 Product가 있으면, 200 응답과 삭제 상태를 반환한다.")
        @Test
        void returnsDeletedProductInfo_whenProductIsDeleted() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("Nike"));
            Product product = productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));
            product.delete();
            productRepository.save(product);
            HttpEntity<Void> request = new HttpEntity<>(null);

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> response = adminClient.exchange(
                ENDPOINT_PRODUCTS + "/" + product.getId(),
                HttpMethod.GET,
                request,
                responseType
            );

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().deleted()).isTrue()
            );
        }

        @DisplayName("없는 Product ID면, 404 응답을 반환한다.")
        @Test
        void returnsNotFound_whenProductDoesNotExist() {
            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
                ENDPOINT_PRODUCTS + "/1",
                HttpMethod.GET,
                null,
                responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("PUT /api-admin/v1/products/{productId}")
    @Nested
    class Update {
        @DisplayName("유효한 이름과 가격이면, 200 응답과 수정된 Product 정보를 반환한다.")
        @Test
        void returnsUpdatedProduct_whenDetailsAreValid() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("Nike"));
            Product product = productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));
            HttpEntity<ProductV1Dto.UpdateRequest> request = new HttpEntity<>(
                new ProductV1Dto.UpdateRequest("Air Force", 120_000L)
            );

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> response = adminClient.exchange(
                ENDPOINT_PRODUCTS + "/" + product.getId(),
                HttpMethod.PUT,
                request,
                responseType
            );

            // assert
            Product savedProduct = productRepository.findById(product.getId()).orElseThrow();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().name()).isEqualTo("Air Force"),
                () -> assertThat(response.getBody().data().price()).isEqualTo(120_000L),
                () -> assertThat(savedProduct.getBrandId()).isEqualTo(brand.getId())
            );
        }

        @DisplayName("이름이 공백만으로 구성되면, 400 응답과 기존 정보를 유지한다.")
        @Test
        void keepsDetails_whenNameIsBlank() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("Nike"));
            Product product = productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));
            HttpEntity<ProductV1Dto.UpdateRequest> request = new HttpEntity<>(
                new ProductV1Dto.UpdateRequest(" ", 120_000L)
            );

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
                ENDPOINT_PRODUCTS + "/" + product.getId(),
                HttpMethod.PUT,
                request,
                responseType
            );

            // assert
            Product savedProduct = productRepository.findById(product.getId()).orElseThrow();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(savedProduct.getName()).isEqualTo("Air Max"),
                () -> assertThat(savedProduct.getPrice()).isEqualTo(100_000L)
            );
        }

        @DisplayName("삭제된 Product면, 404 응답을 반환한다.")
        @Test
        void returnsNotFound_whenProductIsDeleted() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("Nike"));
            Product product = productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));
            product.delete();
            productRepository.save(product);
            HttpEntity<ProductV1Dto.UpdateRequest> request = new HttpEntity<>(
                new ProductV1Dto.UpdateRequest("Air Force", 120_000L)
            );

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
                ENDPOINT_PRODUCTS + "/" + product.getId(),
                HttpMethod.PUT,
                request,
                responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("DELETE /api-admin/v1/products/{productId}")
    @Nested
    class Delete {
        @DisplayName("Product가 있으면, 200 응답을 반환하고 논리 삭제한다.")
        @Test
        void deletesProduct_whenProductExists() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("Nike"));
            Product product = productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
                ENDPOINT_PRODUCTS + "/" + product.getId(),
                HttpMethod.DELETE,
                null,
                responseType
            );

            // assert
            Product deletedProduct = productRepository.findById(product.getId()).orElseThrow();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(deletedProduct.getDeletedAt()).isNotNull()
            );
        }

        @DisplayName("없는 Product ID면, 404 응답을 반환한다.")
        @Test
        void returnsNotFound_whenProductDoesNotExist() {
            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
                ENDPOINT_PRODUCTS + "/1",
                HttpMethod.DELETE,
                null,
                responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("GET /api-admin/v1/products")
    @Nested
    class GetList {
        @DisplayName("status를 지정하지 않으면, 활성·삭제 Product를 모두 200 응답으로 반환한다.")
        @Test
        void returnsAllProducts_whenStatusIsOmitted() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("Nike"));
            Product activeProduct = productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));
            Product deletedProduct = productRepository.save(Product.create(brand.getId(), "Air Force", 120_000L));
            deletedProduct.delete();
            productRepository.save(deletedProduct);

            // act
            ParameterizedTypeReference<ApiResponse<List<ProductV1Dto.ProductResponse>>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<List<ProductV1Dto.ProductResponse>>> response = adminClient.exchange(
                ENDPOINT_PRODUCTS,
                HttpMethod.GET,
                null,
                responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data()).extracting(ProductV1Dto.ProductResponse::id)
                .containsExactlyInAnyOrder(activeProduct.getId(), deletedProduct.getId());
        }

        @DisplayName("status=ACTIVE이면 삭제되지 않은 Product만 200 응답으로 반환한다.")
        @Test
        void returnsActiveProducts_whenStatusIsActive() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("Nike"));
            Product activeProduct = productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));
            Product deletedProduct = productRepository.save(Product.create(brand.getId(), "Air Force", 120_000L));
            deletedProduct.delete();
            productRepository.save(deletedProduct);

            // act
            ParameterizedTypeReference<ApiResponse<List<ProductV1Dto.ProductResponse>>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<List<ProductV1Dto.ProductResponse>>> response = adminClient.exchange(
                ENDPOINT_PRODUCTS + "?status=ACTIVE",
                HttpMethod.GET,
                null,
                responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data()).extracting(ProductV1Dto.ProductResponse::id)
                .containsExactly(activeProduct.getId());
        }

        @DisplayName("status가 잘못되면, 400 응답을 반환한다.")
        @Test
        void returnsBadRequest_whenStatusIsInvalid() {
            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
                ENDPOINT_PRODUCTS + "?status=INVALID",
                HttpMethod.GET,
                null,
                responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
