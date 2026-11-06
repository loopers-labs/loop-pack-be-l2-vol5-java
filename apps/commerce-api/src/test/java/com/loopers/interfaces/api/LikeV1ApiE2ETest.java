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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LikeV1ApiE2ETest {

    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public LikeV1ApiE2ETest(
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

    private HttpEntity<Void> withUser(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(userId));
        return new HttpEntity<>(headers);
    }

    private ProductModel saveProduct() {
        BrandModel brand = brandJpaRepository.save(new BrandModel("나이키"));
        return productJpaRepository.save(new ProductModel(brand.getId(), "runner", 10_000L, 5));
    }

    @DisplayName("POST, DELETE /api/v1/products/{productId}/likes")
    @Nested
    class LikeAndUnlike {
        @DisplayName("좋아요를 등록하면, 상품의 좋아요 수에 반영된다.")
        @Test
        void reflectsLikeCount_whenLiked() {
            // arrange
            ProductModel product = saveProduct();

            // act
            ResponseEntity<ApiResponse<Object>> likeResponse = testRestTemplate.exchange(
                "/api/v1/products/" + product.getId() + "/likes",
                HttpMethod.POST, withUser(1L), new ParameterizedTypeReference<>() {});

            // assert
            assertThat(likeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(getLikeCount(product.getId())).isEqualTo(1L);
        }

        @DisplayName("같은 상품에 두 번 좋아요를 등록해도, 좋아요 수는 1로 유지된다.")
        @Test
        void staysIdempotent_whenLikedTwice() {
            // arrange
            ProductModel product = saveProduct();

            // act
            testRestTemplate.exchange("/api/v1/products/" + product.getId() + "/likes",
                HttpMethod.POST, withUser(1L), Void.class);
            testRestTemplate.exchange("/api/v1/products/" + product.getId() + "/likes",
                HttpMethod.POST, withUser(1L), Void.class);

            // assert
            assertThat(getLikeCount(product.getId())).isEqualTo(1L);
        }

        @DisplayName("좋아요를 취소하면, 상품의 좋아요 수에서 제외된다.")
        @Test
        void reflectsLikeCount_whenUnliked() {
            // arrange
            ProductModel product = saveProduct();
            testRestTemplate.exchange("/api/v1/products/" + product.getId() + "/likes",
                HttpMethod.POST, withUser(1L), Void.class);

            // act
            ResponseEntity<ApiResponse<Object>> unlikeResponse = testRestTemplate.exchange(
                "/api/v1/products/" + product.getId() + "/likes",
                HttpMethod.DELETE, withUser(1L), new ParameterizedTypeReference<>() {});

            // assert
            assertThat(unlikeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(getLikeCount(product.getId())).isEqualTo(0L);
        }

        @DisplayName("존재하지 않는 상품에 좋아요를 요청하면, 404를 응답한다.")
        @Test
        void returns404_whenProductDoesNotExist() {
            // act
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                "/api/v1/products/999/likes",
                HttpMethod.POST, withUser(1L), new ParameterizedTypeReference<>() {});

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("X-USER-ID 헤더 없이 요청하면, 400을 응답한다.")
        @Test
        void returns400_whenUserIdHeaderIsMissing() {
            // arrange
            ProductModel product = saveProduct();

            // act
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                "/api/v1/products/" + product.getId() + "/likes",
                HttpMethod.POST, new HttpEntity<>(null), new ParameterizedTypeReference<>() {});

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @DisplayName("GET /api/v1/likes")
    @Nested
    class MyLikedProducts {
        @DisplayName("좋아요한 상품이 있으면, 목록에 포함된다.")
        @Test
        void includesProduct_whenLiked() {
            // arrange
            ProductModel product = saveProduct();
            testRestTemplate.exchange("/api/v1/products/" + product.getId() + "/likes",
                HttpMethod.POST, withUser(1L), Void.class);

            // act
            ResponseEntity<ApiResponse<List<ProductV1Dto.ProductResponse>>> response = testRestTemplate.exchange(
                "/api/v1/likes", HttpMethod.GET, withUser(1L), new ParameterizedTypeReference<>() {});

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data()).hasSize(1);
            assertThat(response.getBody().data().get(0).id()).isEqualTo(product.getId());
        }

        @DisplayName("좋아요를 취소한 상품은, 목록에서 제외된다.")
        @Test
        void excludesProduct_whenUnliked() {
            // arrange
            ProductModel product = saveProduct();
            testRestTemplate.exchange("/api/v1/products/" + product.getId() + "/likes",
                HttpMethod.POST, withUser(1L), Void.class);
            testRestTemplate.exchange("/api/v1/products/" + product.getId() + "/likes",
                HttpMethod.DELETE, withUser(1L), Void.class);

            // act
            ResponseEntity<ApiResponse<List<ProductV1Dto.ProductResponse>>> response = testRestTemplate.exchange(
                "/api/v1/likes", HttpMethod.GET, withUser(1L), new ParameterizedTypeReference<>() {});

            // assert
            assertThat(response.getBody().data()).isEmpty();
        }

        @DisplayName("좋아요한 상품이 삭제되면, 목록에서 제외된다.")
        @Test
        void excludesProduct_whenProductIsDeleted() {
            // arrange
            ProductModel product = saveProduct();
            testRestTemplate.exchange("/api/v1/products/" + product.getId() + "/likes",
                HttpMethod.POST, withUser(1L), Void.class);
            product.delete();
            productJpaRepository.save(product);

            // act
            ResponseEntity<ApiResponse<List<ProductV1Dto.ProductResponse>>> response = testRestTemplate.exchange(
                "/api/v1/likes", HttpMethod.GET, withUser(1L), new ParameterizedTypeReference<>() {});

            // assert
            assertThat(response.getBody().data()).isEmpty();
        }
    }

    private long getLikeCount(Long productId) {
        ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductResponse>> responseType = new ParameterizedTypeReference<>() {};
        ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> response =
            testRestTemplate.exchange("/api/v1/products/" + productId, HttpMethod.GET, new HttpEntity<>(null), responseType);
        return response.getBody().data().likeCount();
    }
}
