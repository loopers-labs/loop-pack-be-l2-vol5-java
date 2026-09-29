package com.loopers.interfaces.api.like;

import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
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

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LikeV1ApiE2ETest {

    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final Function<Long, String> ENDPOINT_LIKE = id -> "/api/v1/products/" + id + "/likes";
    private static final Function<Long, String> ENDPOINT_MY_LIKES = id -> "/api/v1/users/" + id + "/likes";

    private final TestRestTemplate testRestTemplate;
    private final UserJpaRepository userJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final LikeJpaRepository likeJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public LikeV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        UserJpaRepository userJpaRepository,
        ProductJpaRepository productJpaRepository,
        LikeJpaRepository likeJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.userJpaRepository = userJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.likeJpaRepository = likeJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("POST /api/v1/products/{productId}/likes")
    @Nested
    class Like_ {
        @DisplayName("식별된 사용자가 요청하면, 201 응답과 함께 관계가 저장된다.")
        @Test
        void returnsCreatedAndSavesRelation_whenRequesterIsIdentified() {
            // arrange
            Long userId = userJpaRepository.save(new User()).getId();
            Long productId = productJpaRepository.save(new Product(1L, "루퍼스 티셔츠", new Price(1000L))).getId();

            // act
            ResponseEntity<String> response = post(ENDPOINT_LIKE.apply(productId), userId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(likeJpaRepository.countByProductId(productId)).isEqualTo(1L);
        }

        @DisplayName("이미 좋아요한 상품에 다시 요청하면, 200 응답을 받고 관계는 하나로 유지된다.")
        @Test
        void returnsOkAndKeepsSingleRelation_whenAlreadyLiked() {
            // arrange
            Long userId = userJpaRepository.save(new User()).getId();
            Long productId = productJpaRepository.save(new Product(1L, "루퍼스 티셔츠", new Price(1000L))).getId();
            post(ENDPOINT_LIKE.apply(productId), userId);

            // act
            ResponseEntity<String> response = post(ENDPOINT_LIKE.apply(productId), userId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(likeJpaRepository.countByProductId(productId)).isEqualTo(1L);
        }

        @DisplayName("식별 헤더가 없으면, 401 응답을 받고 저장되지 않는다.")
        @Test
        void returnsUnauthorized_whenHeaderIsMissing() {
            // arrange
            Long productId = productJpaRepository.save(new Product(1L, "루퍼스 티셔츠", new Price(1000L))).getId();

            // act
            ResponseEntity<String> response = post(ENDPOINT_LIKE.apply(productId), null);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(likeJpaRepository.findAll()).isEmpty();
        }

        @DisplayName("삭제된 상품에 요청하면, 404 응답을 받고 저장되지 않는다.")
        @Test
        void returnsNotFound_whenProductIsDeleted() {
            // arrange
            Long userId = userJpaRepository.save(new User()).getId();
            Product product = new Product(1L, "삭제된 상품", new Price(1000L));
            product.delete();
            Long productId = productJpaRepository.save(product).getId();

            // act
            ResponseEntity<String> response = post(ENDPOINT_LIKE.apply(productId), userId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(likeJpaRepository.findAll()).isEmpty();
        }
    }

    @DisplayName("DELETE /api/v1/products/{productId}/likes")
    @Nested
    class Unlike {
        @DisplayName("본인의 좋아요를 취소하면, 204 응답을 받고 관계가 삭제된다.")
        @Test
        void returnsNoContentAndDeletesRelation_whenRequesterIsOwner() {
            // arrange
            Long userId = userJpaRepository.save(new User()).getId();
            Long productId = productJpaRepository.save(new Product(1L, "루퍼스 티셔츠", new Price(1000L))).getId();
            post(ENDPOINT_LIKE.apply(productId), userId);

            // act
            ResponseEntity<String> response = delete(ENDPOINT_LIKE.apply(productId), userId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(likeJpaRepository.findAll()).isEmpty();
        }

        @DisplayName("상품이 삭제되었어도, 기존 좋아요는 취소된다.")
        @Test
        void deletesRelation_evenWhenProductIsDeleted() {
            // arrange
            Long userId = userJpaRepository.save(new User()).getId();
            Product product = productJpaRepository.save(new Product(1L, "루퍼스 티셔츠", new Price(1000L)));
            post(ENDPOINT_LIKE.apply(product.getId()), userId);
            product.delete();
            productJpaRepository.save(product);

            // act
            ResponseEntity<String> response = delete(ENDPOINT_LIKE.apply(product.getId()), userId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(likeJpaRepository.findAll()).isEmpty();
        }

        @DisplayName("다른 사용자의 좋아요는 취소되지 않고, 404 응답을 받는다.")
        @Test
        void returnsNotFound_whenRelationBelongsToAnotherUser() {
            // arrange
            Long ownerId = userJpaRepository.save(new User()).getId();
            Long otherId = userJpaRepository.save(new User()).getId();
            Long productId = productJpaRepository.save(new Product(1L, "루퍼스 티셔츠", new Price(1000L))).getId();
            post(ENDPOINT_LIKE.apply(productId), ownerId);

            // act
            ResponseEntity<String> response = delete(ENDPOINT_LIKE.apply(productId), otherId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(likeJpaRepository.countByProductId(productId)).isEqualTo(1L);
        }
    }

    @DisplayName("GET /api/v1/users/{userId}/likes")
    @Nested
    class GetMyLikes {
        @DisplayName("본인 목록을 조회하면, 200 응답과 좋아요한 상품 목록을 받는다.")
        @Test
        void returnsLikedProducts_whenRequesterIsOwner() {
            // arrange
            Long userId = userJpaRepository.save(new User()).getId();
            Long productId = productJpaRepository.save(new Product(1L, "루퍼스 티셔츠", new Price(1000L))).getId();
            post(ENDPOINT_LIKE.apply(productId), userId);

            // act
            ResponseEntity<ApiResponse<List<LikeV1Dto.LikedProductResponse>>> response = getMyLikes(userId, userId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data()).hasSize(1);
            assertThat(response.getBody().data().get(0).productName()).isEqualTo("루퍼스 티셔츠");
            assertThat(response.getBody().data().get(0).likeCount()).isEqualTo(1L);
        }

        @DisplayName("삭제된 상품은 목록에서 제외된다.")
        @Test
        void excludesDeletedProducts() {
            // arrange
            Long userId = userJpaRepository.save(new User()).getId();
            Product alive = productJpaRepository.save(new Product(1L, "살아있는 상품", new Price(1000L)));
            Product target = productJpaRepository.save(new Product(1L, "삭제될 상품", new Price(1000L)));
            post(ENDPOINT_LIKE.apply(alive.getId()), userId);
            post(ENDPOINT_LIKE.apply(target.getId()), userId);
            target.delete();
            productJpaRepository.save(target);

            // act
            ResponseEntity<ApiResponse<List<LikeV1Dto.LikedProductResponse>>> response = getMyLikes(userId, userId);

            // assert
            assertThat(response.getBody().data()).hasSize(1);
            assertThat(response.getBody().data().get(0).productName()).isEqualTo("살아있는 상품");
        }

        @DisplayName("다른 사용자의 목록을 조회하면, 404 응답을 받는다.")
        @Test
        void returnsNotFound_whenRequesterIsNotOwner() {
            // arrange
            Long requesterId = userJpaRepository.save(new User()).getId();
            Long otherId = userJpaRepository.save(new User()).getId();

            // act
            ResponseEntity<ApiResponse<List<LikeV1Dto.LikedProductResponse>>> response =
                getMyLikes(requesterId, otherId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    private ResponseEntity<String> post(String url, Long userId) {
        return testRestTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(null, headers(userId)), String.class);
    }

    private ResponseEntity<String> delete(String url, Long userId) {
        return testRestTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(null, headers(userId)), String.class);
    }

    private ResponseEntity<ApiResponse<List<LikeV1Dto.LikedProductResponse>>> getMyLikes(Long requesterId, Long userId) {
        ParameterizedTypeReference<ApiResponse<List<LikeV1Dto.LikedProductResponse>>> responseType =
            new ParameterizedTypeReference<>() {};
        return testRestTemplate.exchange(
            ENDPOINT_MY_LIKES.apply(userId), HttpMethod.GET, new HttpEntity<>(null, headers(requesterId)), responseType);
    }

    private HttpHeaders headers(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set(USER_ID_HEADER, String.valueOf(userId));
        }
        return headers;
    }
}
