package com.loopers.interfaces.api.like;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.like.LikeJpaRepository;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LikeV1ApiE2ETest {

    private static final String ENDPOINT_PRODUCTS = "/api/v1/products";

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private LikeJpaRepository likeJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("POST /api/v1/products/{productId}/likes")
    @Nested
    class Add {
        @DisplayName("활성 Product면, 200 응답과 좋아요 상태를 반환한다.")
        @Test
        void returnsLiked_whenProductIsActive() {
            // arrange
            Product product = saveProduct();
            HttpEntity<Void> request = new HttpEntity<>(headers());

            // act
            ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>> response = exchange(
                product.getId(), HttpMethod.POST, request
            );

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().liked()).isTrue(),
                () -> assertThat(likeJpaRepository.count()).isEqualTo(1L)
            );
        }
    }

    @DisplayName("DELETE /api/v1/products/{productId}/likes")
    @Nested
    class Cancel {
        @DisplayName("자신의 Like가 있으면, 200 응답과 취소 상태를 반환한다.")
        @Test
        void returnsUnliked_whenOwnLikeExists() {
            // arrange
            Product product = saveProduct();
            HttpEntity<Void> request = new HttpEntity<>(headers());
            exchange(product.getId(), HttpMethod.POST, request);

            // act
            ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>> response = exchange(
                product.getId(), HttpMethod.DELETE, request
            );

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().liked()).isFalse(),
                () -> assertThat(likeJpaRepository.count()).isZero()
            );
        }
    }

    private Product saveProduct() {
        Brand brand = brandJpaRepository.save(Brand.create("Nike"));
        return productJpaRepository.save(Product.create(brand, "Air Max", 100_000L));
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-USER-ID", "1");
        return headers;
    }

    private ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>> exchange(
        Long productId,
        HttpMethod method,
        HttpEntity<Void> request
    ) {
        ParameterizedTypeReference<ApiResponse<LikeV1Dto.LikeResponse>> responseType = new ParameterizedTypeReference<>() {};
        return testRestTemplate.exchange(
            ENDPOINT_PRODUCTS + "/" + productId + "/likes",
            method,
            request,
            responseType
        );
    }
}
