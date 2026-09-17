package com.loopers.interfaces.api;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.like.Like;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.interfaces.api.like.LikeDto;
import com.loopers.interfaces.api.product.ProductDto;
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
class LikeApiE2ETest {

    private static final String USER_ID_HEADER = "X-USER-ID";

    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final UserJpaRepository userJpaRepository;
    private final LikeJpaRepository likeJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    private User user;
    private Brand brand;

    @Autowired
    LikeApiE2ETest(
        TestRestTemplate testRestTemplate,
        BrandJpaRepository brandJpaRepository,
        ProductJpaRepository productJpaRepository,
        UserJpaRepository userJpaRepository,
        LikeJpaRepository likeJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.userJpaRepository = userJpaRepository;
        this.likeJpaRepository = likeJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        user = userJpaRepository.save(new User("user1"));
        brand = brandJpaRepository.save(new Brand("루퍼스"));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Product saveProduct(String name) {
        return productJpaRepository.save(new Product(brand.getId(), name, 10_000L, 10L));
    }

    private void deleteProduct(Product product) {
        product.delete();
        productJpaRepository.save(product);
    }

    private HttpEntity<Void> requestAs(User requester) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(USER_ID_HEADER, String.valueOf(requester.getId()));
        return new HttpEntity<>(headers);
    }

    private ResponseEntity<ApiResponse<LikeDto.LikeResponse>> requestLike(HttpMethod method, User requester, Long productId) {
        ParameterizedTypeReference<ApiResponse<LikeDto.LikeResponse>> responseType = new ParameterizedTypeReference<>() {};
        return testRestTemplate.exchange("/api/v1/products/" + productId + "/likes", method, requestAs(requester), responseType);
    }

    private ResponseEntity<ApiResponse<PageResponse<ProductDto.ProductResponse>>> getLikedProducts(User requester, Long pathUserId) {
        ParameterizedTypeReference<ApiResponse<PageResponse<ProductDto.ProductResponse>>> responseType = new ParameterizedTypeReference<>() {};
        return testRestTemplate.exchange("/api/v1/users/" + pathUserId + "/likes", HttpMethod.GET, requestAs(requester), responseType);
    }

    @DisplayName("POST /api/v1/products/{productId}/likes")
    @Nested
    class AddLike {

        @DisplayName("좋아요하지 않은 상품에 좋아요하면, 관계가 저장되고 좋아요 수 1을 반환한다. (LIK-001)")
        @Test
        void savesLikeAndReturnsLikeCount_whenNotLikedYet() {
            // arrange
            Product product = saveProduct("가방");

            // act
            ResponseEntity<ApiResponse<LikeDto.LikeResponse>> response = requestLike(HttpMethod.POST, user, product.getId());

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().productId()).isEqualTo(product.getId()),
                () -> assertThat(response.getBody().data().liked()).isTrue(),
                () -> assertThat(response.getBody().data().likeCount()).isEqualTo(1L),
                () -> assertThat(likeJpaRepository.count()).isEqualTo(1L)
            );
        }

        @DisplayName("이미 좋아요한 상품에 다시 좋아요하면, 같은 응답을 주고 관계는 하나만 남는다. (LIK-001, P-7)")
        @Test
        void returnsSameResponseWithoutDuplicate_whenAlreadyLiked() {
            // arrange
            Product product = saveProduct("가방");
            requestLike(HttpMethod.POST, user, product.getId());

            // act
            ResponseEntity<ApiResponse<LikeDto.LikeResponse>> response = requestLike(HttpMethod.POST, user, product.getId());

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().liked()).isTrue(),
                () -> assertThat(response.getBody().data().likeCount()).isEqualTo(1L),
                () -> assertThat(likeJpaRepository.count()).isEqualTo(1L)
            );
        }

        @DisplayName("존재하지 않는 상품에 좋아요하면, 404 NOT_FOUND 응답을 받는다.")
        @Test
        void returnsNotFound_whenProductDoesNotExist() {
            // act
            ResponseEntity<ApiResponse<LikeDto.LikeResponse>> response = requestLike(HttpMethod.POST, user, 999999L);

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(likeJpaRepository.count()).isEqualTo(0L)
            );
        }

        @DisplayName("삭제된 상품에 좋아요하면, 404 NOT_FOUND 응답을 받는다. (LIK-003)")
        @Test
        void returnsNotFound_whenProductIsDeleted() {
            // arrange
            Product product = saveProduct("가방");
            deleteProduct(product);

            // act
            ResponseEntity<ApiResponse<LikeDto.LikeResponse>> response = requestLike(HttpMethod.POST, user, product.getId());

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(likeJpaRepository.count()).isEqualTo(0L)
            );
        }

        @DisplayName("삭제 전에 좋아요한 상품이어도, 삭제된 뒤 다시 좋아요하면 404 NOT_FOUND 응답을 받는다. (P-10)")
        @Test
        void returnsNotFound_whenLikedProductIsDeletedAndLikedAgain() {
            // arrange
            Product product = saveProduct("가방");
            likeJpaRepository.save(new Like(user.getId(), product.getId()));
            deleteProduct(product);

            // act
            ResponseEntity<ApiResponse<LikeDto.LikeResponse>> response = requestLike(HttpMethod.POST, user, product.getId());

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("DELETE /api/v1/products/{productId}/likes")
    @Nested
    class CancelLike {

        @DisplayName("좋아요한 상품을 취소하면, 관계 행이 삭제되고 좋아요 수가 줄어든다. (LIK-004, P-8)")
        @Test
        void deletesLikeAndReturnsDecreasedCount_whenLiked() {
            // arrange
            Product product = saveProduct("가방");
            User other = userJpaRepository.save(new User("user2"));
            likeJpaRepository.save(new Like(user.getId(), product.getId()));
            likeJpaRepository.save(new Like(other.getId(), product.getId()));

            // act
            ResponseEntity<ApiResponse<LikeDto.LikeResponse>> response = requestLike(HttpMethod.DELETE, user, product.getId());

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().productId()).isEqualTo(product.getId()),
                () -> assertThat(response.getBody().data().liked()).isFalse(),
                () -> assertThat(response.getBody().data().likeCount()).isEqualTo(1L),
                () -> assertThat(likeJpaRepository.count()).isEqualTo(1L)
            );
        }

        @DisplayName("좋아요한 적 없는 상품을 취소하면, 404 NOT_FOUND 응답을 받는다. (P-7)")
        @Test
        void returnsNotFound_whenNotLiked() {
            // arrange
            Product product = saveProduct("가방");

            // act
            ResponseEntity<ApiResponse<LikeDto.LikeResponse>> response = requestLike(HttpMethod.DELETE, user, product.getId());

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("좋아요한 상품이 삭제되었어도, 자신의 좋아요는 취소할 수 있다. (LIK-003)")
        @Test
        void cancelsLike_whenLikedProductIsDeleted() {
            // arrange
            Product product = saveProduct("가방");
            likeJpaRepository.save(new Like(user.getId(), product.getId()));
            deleteProduct(product);

            // act
            ResponseEntity<ApiResponse<LikeDto.LikeResponse>> response = requestLike(HttpMethod.DELETE, user, product.getId());

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().liked()).isFalse(),
                () -> assertThat(likeJpaRepository.count()).isEqualTo(0L)
            );
        }
    }

    @DisplayName("GET /api/v1/users/{userId}/likes")
    @Nested
    class GetLikedProducts {

        @DisplayName("자신의 좋아요 목록을 조회하면, 좋아요한 상품 중 삭제되지 않은 상품만 반환한다. (LIK-003)")
        @Test
        void returnsLikedProductsExcludingDeleted() {
            // arrange
            Product liked = saveProduct("가방");
            Product likedButDeleted = saveProduct("나시");
            saveProduct("다운");
            likeJpaRepository.save(new Like(user.getId(), liked.getId()));
            likeJpaRepository.save(new Like(user.getId(), likedButDeleted.getId()));
            deleteProduct(likedButDeleted);

            // act
            ResponseEntity<ApiResponse<PageResponse<ProductDto.ProductResponse>>> response = getLikedProducts(user, user.getId());

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().content())
                    .extracting(ProductDto.ProductResponse::productId)
                    .containsExactly(liked.getId()),
                () -> assertThat(response.getBody().data().totalElements()).isEqualTo(1L)
            );
        }

        @DisplayName("다른 사용자의 좋아요 목록을 조회하면, 404 NOT_FOUND 응답을 받는다. (LIK-004, P-6)")
        @Test
        void returnsNotFound_whenPathUserIdIsNotRequester() {
            // arrange
            User other = userJpaRepository.save(new User("user2"));
            Product product = saveProduct("가방");
            likeJpaRepository.save(new Like(other.getId(), product.getId()));

            // act
            ResponseEntity<ApiResponse<PageResponse<ProductDto.ProductResponse>>> response = getLikedProducts(user, other.getId());

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
