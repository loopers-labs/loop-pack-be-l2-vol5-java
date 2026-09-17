package com.loopers.interfaces.api.like;

import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("좋아요 API 는 요청자의 좋아요를 등록·취소하고 목록으로 제공한다.")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LikeV1ApiE2ETest {

    private static final String USER_ID_HEADER = "X-USER-ID";

    private static final ParameterizedTypeReference<ApiResponse<LikeV1Dto.LikeResponse>> LIKE_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<Object>> EMPTY_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> PAGE_TYPE =
        new ParameterizedTypeReference<>() {};

    @Autowired
    private TestRestTemplate testRestTemplate;
    @Autowired
    private UserFixture userFixture;
    @Autowired
    private ProductFixture productFixture;
    @Autowired
    private LikeService likeService;
    @Autowired
    private LikeJpaRepository likeJpaRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private HttpEntity<Object> request(String userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set(USER_ID_HEADER, userId);
        }
        return new HttpEntity<>(null, headers);
    }

    private String likesOf(Object productId) {
        return "/api/v1/products/" + productId + "/likes";
    }

    @DisplayName("POST /api/v1/products/{productId}/likes")
    @Nested
    class Like {
        @DisplayName("좋아요 관계를 저장하고 201 로 반환한다.")
        @Test
        void createsLike() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 3L);

            ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>> response = testRestTemplate.exchange(
                likesOf(shoes.getId()), HttpMethod.POST, request(String.valueOf(user.getId())), LIKE_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED),
                () -> assertThat(response.getBody().data().id()).isNotNull(),
                () -> assertThat(response.getBody().data().userId()).isEqualTo(user.getId()),
                () -> assertThat(response.getBody().data().productId()).isEqualTo(shoes.getId()),
                () -> assertThat(likeJpaRepository.findAll()).hasSize(1)
            );
        }

        @DisplayName("이미 좋아요한 상품은 409 LIKE_ALREADY_EXISTS 로 거절하고 관계를 늘리지 않는다.")
        @Test
        void rejectsDuplicate() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 3L);
            likeService.like(user.getId(), shoes.getId());

            ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>> response = testRestTemplate.exchange(
                likesOf(shoes.getId()), HttpMethod.POST, request(String.valueOf(user.getId())), LIKE_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("LIKE_ALREADY_EXISTS"),
                () -> assertThat(likeJpaRepository.findAll()).hasSize(1)
            );
        }

        @DisplayName("삭제된 상품에는 404 PRODUCT_NOT_FOUND 로 거절하고 좋아요를 만들지 않는다.")
        @Test
        void rejectsDeletedProduct() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel deleted = productFixture.createDeletedProduct("단종 운동화", 10_000L, 3L);

            ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>> response = testRestTemplate.exchange(
                likesOf(deleted.getId()), HttpMethod.POST, request(String.valueOf(user.getId())), LIKE_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("PRODUCT_NOT_FOUND"),
                () -> assertThat(likeJpaRepository.findAll()).isEmpty()
            );
        }

        @DisplayName("저장되지 않은 사용자 ID 로 요청하면 404 USER_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsUnknownUser() {
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 3L);

            ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>> response = testRestTemplate.exchange(
                likesOf(shoes.getId()), HttpMethod.POST, request("999999"), LIKE_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("USER_NOT_FOUND"),
                () -> assertThat(likeJpaRepository.findAll()).isEmpty()
            );
        }
    }

    @DisplayName("DELETE /api/v1/products/{productId}/likes")
    @Nested
    class CancelLike {
        @DisplayName("좋아요 관계를 삭제하고 200 과 빈 데이터로 응답한다.")
        @Test
        void cancelsLike() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 3L);
            likeService.like(user.getId(), shoes.getId());

            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                likesOf(shoes.getId()), HttpMethod.DELETE, request(String.valueOf(user.getId())), EMPTY_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data()).isNull(),
                () -> assertThat(likeJpaRepository.findAll()).isEmpty()
            );
        }

        @DisplayName("좋아요하지 않은 상품은 404 LIKE_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsMissingLike() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 3L);

            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                likesOf(shoes.getId()), HttpMethod.DELETE, request(String.valueOf(user.getId())), EMPTY_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("LIKE_NOT_FOUND")
            );
        }

        @DisplayName("다른 사용자의 좋아요는 취소하지 않고 404 LIKE_NOT_FOUND 로 거절한다.")
        @Test
        void doesNotCancelOthersLike() {
            UserModel owner = userFixture.createUserWithPoint();
            UserModel other = userFixture.createUserWithPoint();
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 3L);
            likeService.like(owner.getId(), shoes.getId());

            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                likesOf(shoes.getId()), HttpMethod.DELETE, request(String.valueOf(other.getId())), EMPTY_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("LIKE_NOT_FOUND"),
                () -> assertThat(likeJpaRepository.findAll()).hasSize(1)
            );
        }

        @DisplayName("삭제된 상품이라도 삭제 전에 만든 자신의 좋아요는 취소한다.")
        @Test
        void cancelsLikeOfDeletedProduct() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 3L);
            likeService.like(user.getId(), shoes.getId());
            productFixture.deleteProduct(shoes.getId());

            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                likesOf(shoes.getId()), HttpMethod.DELETE, request(String.valueOf(user.getId())), EMPTY_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(likeJpaRepository.findAll()).isEmpty()
            );
        }
    }

    @DisplayName("GET /api/v1/users/{userId}/likes")
    @Nested
    class GetMyLikes {
        private ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> get(
            Object pathUserId, String headerUserId, String query
        ) {
            return testRestTemplate.exchange(
                "/api/v1/users/" + pathUserId + "/likes" + query,
                HttpMethod.GET, request(headerUserId), PAGE_TYPE);
        }

        @DisplayName("자신이 좋아요한 활성 상품만 최신 좋아요순으로 반환한다.")
        @Test
        void returnsOwnLikedProducts() {
            UserModel user = userFixture.createUserWithPoint();
            UserModel other = userFixture.createUserWithPoint();
            ProductModel first = productFixture.createProduct("첫째", 1_000L, 1L);
            ProductModel second = productFixture.createProduct("둘째", 2_000L, 2L);
            ProductModel othersPick = productFixture.createProduct("남의 것", 3_000L, 3L);
            likeService.like(user.getId(), first.getId());
            likeService.like(user.getId(), second.getId());
            likeService.like(other.getId(), othersPick.getId());

            ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> response =
                get(user.getId(), String.valueOf(user.getId()), "");

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().totalElements()).isEqualTo(2L),
                () -> assertThat(response.getBody().data().items())
                    .extracting(ProductV1Dto.ProductResponse::name)
                    .containsExactly("둘째", "첫째")
            );
        }

        @DisplayName("상품 응답에 브랜드명·좋아요 수·현재 재고 수량을 포함한다.")
        @Test
        void includesProductDetails() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shoes = productFixture.createProduct("운동화", 89_000L, 7L);
            likeService.like(user.getId(), shoes.getId());

            ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> response =
                get(user.getId(), String.valueOf(user.getId()), "");

            ProductV1Dto.ProductResponse item = response.getBody().data().items().get(0);
            assertAll(
                () -> assertThat(item.brandName()).isEqualTo("운동화 브랜드"),
                () -> assertThat(item.likeCount()).isEqualTo(1L),
                () -> assertThat(item.stockQuantity()).isEqualTo(7L),
                () -> assertThat(item.price()).isEqualTo(89_000L)
            );
        }

        @DisplayName("경로의 사용자 ID 가 요청자와 다르면 404 USER_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsOtherUsersPath() {
            UserModel user = userFixture.createUserWithPoint();
            UserModel other = userFixture.createUserWithPoint();
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 3L);
            likeService.like(other.getId(), shoes.getId());

            ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> response =
                get(other.getId(), String.valueOf(user.getId()), "");

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("USER_NOT_FOUND"),
                () -> assertThat(response.getBody().data()).isNull()
            );
        }

        @DisplayName("oldest 는 오래된 좋아요순으로 반환한다.")
        @Test
        void returnsOldestFirst() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel first = productFixture.createProduct("첫째", 1_000L, 1L);
            ProductModel second = productFixture.createProduct("둘째", 2_000L, 2L);
            likeService.like(user.getId(), first.getId());
            likeService.like(user.getId(), second.getId());

            ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> response =
                get(user.getId(), String.valueOf(user.getId()), "?sort=oldest");

            assertThat(response.getBody().data().items())
                .extracting(ProductV1Dto.ProductResponse::name)
                .containsExactly("첫째", "둘째");
        }

        @DisplayName("지원하지 않는 sort 는 400 INVALID_SORT 로 거절한다.")
        @Test
        void rejectsUnsupportedSort() {
            UserModel user = userFixture.createUserWithPoint();

            ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> response =
                get(user.getId(), String.valueOf(user.getId()), "?sort=likes_desc");

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("INVALID_SORT")
            );
        }

        @DisplayName("page 와 size 로 끊어 전체 개수와 함께 반환한다.")
        @Test
        void returnsRequestedPage() {
            UserModel user = userFixture.createUserWithPoint();
            for (int i = 1; i <= 3; i++) {
                ProductModel product = productFixture.createProduct("상품" + i, 1_000L * i, 1L);
                likeService.like(user.getId(), product.getId());
            }

            ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> response =
                get(user.getId(), String.valueOf(user.getId()), "?page=1&size=2&sort=oldest");

            assertAll(
                () -> assertThat(response.getBody().data().page()).isEqualTo(1),
                () -> assertThat(response.getBody().data().totalElements()).isEqualTo(3L),
                () -> assertThat(response.getBody().data().totalPages()).isEqualTo(2),
                () -> assertThat(response.getBody().data().items())
                    .extracting(ProductV1Dto.ProductResponse::name)
                    .containsExactly("상품3")
            );
        }

        @DisplayName("X-USER-ID 가 없으면 400 INVALID_REQUEST 로 거절한다.")
        @Test
        void rejectsMissingHeader() {
            UserModel user = userFixture.createUserWithPoint();

            ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> response =
                get(user.getId(), null, "");

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("INVALID_REQUEST")
            );
        }
    }
}
