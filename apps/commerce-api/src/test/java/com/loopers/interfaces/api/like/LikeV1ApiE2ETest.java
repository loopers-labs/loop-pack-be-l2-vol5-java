package com.loopers.interfaces.api.like;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.UserRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.create());
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("POST /api/v1/products/{productId}/likes")
    @Nested
    class Add {
        @DisplayName("요청자 식별값이 없으면, 400 응답을 반환하고 Like를 저장하지 않는다.")
        @Test
        void returnsBadRequest_whenUserIdIsMissing() {
            // arrange
            Product product = saveProduct();

            // act
            ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>> response = exchange(
                product.getId(), HttpMethod.POST, new HttpEntity<>((Void) null)
            );

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(likeRepository.findAllByUserId(user.getId())).isEmpty()
            );
        }

        @DisplayName("없는 User면, 404 응답을 반환하고 Like를 저장하지 않는다.")
        @Test
        void returnsNotFound_whenUserDoesNotExist() {
            // arrange
            Product product = saveProduct();
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-USER-ID", "999");

            // act
            ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>> response = exchange(
                product.getId(), HttpMethod.POST, new HttpEntity<>(headers)
            );

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(likeRepository.findAllByUserId(user.getId())).isEmpty()
            );
        }

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
                () -> assertThat(likeRepository.findAllByUserId(user.getId())).hasSize(1)
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
                () -> assertThat(likeRepository.findAllByUserId(user.getId())).isEmpty()
            );
        }
    }

    private Product saveProduct() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        return productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-USER-ID", user.getId().toString());
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
