package com.loopers.interfaces.api.like;

import com.loopers.domain.product.ProductModel;
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

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LikeV1ApiE2ETest {

    private static final Function<Long, String> ENDPOINT = productId -> "/api/v1/products/" + productId + "/likes";

    private final TestRestTemplate testRestTemplate;
    private final ProductJpaRepository productJpaRepository;
    private final LikeJpaRepository likeJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public LikeV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        ProductJpaRepository productJpaRepository,
        LikeJpaRepository likeJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.productJpaRepository = productJpaRepository;
        this.likeJpaRepository = likeJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private ProductModel createProduct() {
        return productJpaRepository.save(new ProductModel("상품", 1000L, 1L, 10));
    }

    private HttpEntity<Void> requestWithUser(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-USER-ID", String.valueOf(userId));
        return new HttpEntity<>(null, headers);
    }

    @DisplayName("POST /api/v1/products/{productId}/likes")
    @Nested
    class Like {
        @DisplayName("존재하는 상품에 처음 좋아요를 등록하면, 200을 반환한다.")
        @Test
        void returns200_whenFirstLike() {
            // arrange
            ProductModel product = createProduct();
            String requestUrl = ENDPOINT.apply(product.getId());

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response =
                testRestTemplate.exchange(requestUrl, HttpMethod.POST, requestWithUser(1L), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(likeJpaRepository.existsByUserIdAndProductId(1L, product.getId())).isTrue();
        }

        @DisplayName("이미 좋아요한 상품에 다시 등록하면, 409를 반환한다.")
        @Test
        void returns409_whenAlreadyLiked() {
            // arrange
            ProductModel product = createProduct();
            String requestUrl = ENDPOINT.apply(product.getId());
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            testRestTemplate.exchange(requestUrl, HttpMethod.POST, requestWithUser(1L), responseType);

            // act
            ResponseEntity<ApiResponse<Object>> response =
                testRestTemplate.exchange(requestUrl, HttpMethod.POST, requestWithUser(1L), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @DisplayName("존재하지 않는 상품에 좋아요를 등록하면, 404를 반환한다.")
        @Test
        void returns404_whenProductDoesNotExist() {
            // arrange
            String requestUrl = ENDPOINT.apply(999L);

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response =
                testRestTemplate.exchange(requestUrl, HttpMethod.POST, requestWithUser(1L), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("삭제된 상품에 좋아요를 등록하면, 404를 반환한다.")
        @Test
        void returns404_whenProductIsDeleted() {
            // arrange
            ProductModel product = createProduct();
            product.delete();
            productJpaRepository.saveAndFlush(product);
            String requestUrl = ENDPOINT.apply(product.getId());

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response =
                testRestTemplate.exchange(requestUrl, HttpMethod.POST, requestWithUser(1L), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("DELETE /api/v1/products/{productId}/likes")
    @Nested
    class Unlike {
        @DisplayName("등록된 좋아요를 취소하면, 200을 반환하고 관계가 삭제된다.")
        @Test
        void returns200_whenRelationExists() {
            // arrange
            ProductModel product = createProduct();
            String requestUrl = ENDPOINT.apply(product.getId());
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            testRestTemplate.exchange(requestUrl, HttpMethod.POST, requestWithUser(1L), responseType);

            // act
            ResponseEntity<ApiResponse<Object>> response =
                testRestTemplate.exchange(requestUrl, HttpMethod.DELETE, requestWithUser(1L), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(likeJpaRepository.existsByUserIdAndProductId(1L, product.getId())).isFalse();
        }

        @DisplayName("좋아요한 뒤 상품이 삭제됐어도, 자기 좋아요 취소는 200이고 관계가 실제로 삭제된다.")
        @Test
        void returns200AndRemovesRelation_whenProductWasDeletedAfterLike() {
            // arrange
            ProductModel product = createProduct();
            String requestUrl = ENDPOINT.apply(product.getId());
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            testRestTemplate.exchange(requestUrl, HttpMethod.POST, requestWithUser(1L), responseType);
            product.delete();
            productJpaRepository.saveAndFlush(product);

            // act
            ResponseEntity<ApiResponse<Object>> response =
                testRestTemplate.exchange(requestUrl, HttpMethod.DELETE, requestWithUser(1L), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(likeJpaRepository.existsByUserIdAndProductId(1L, product.getId())).isFalse();
        }

        @DisplayName("등록된 적 없는 좋아요를 취소해도, 멱등하게 200을 반환한다.")
        @Test
        void returns200_whenRelationDoesNotExist() {
            // arrange
            ProductModel product = createProduct();
            String requestUrl = ENDPOINT.apply(product.getId());

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response =
                testRestTemplate.exchange(requestUrl, HttpMethod.DELETE, requestWithUser(1L), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}
