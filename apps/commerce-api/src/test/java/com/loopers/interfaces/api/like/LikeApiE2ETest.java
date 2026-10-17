package com.loopers.interfaces.api.like;

import com.loopers.domain.common.Money;
import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.Stock;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.fixture.ExposedProducts;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LikeApiE2ETest {

    private static final String USER_ID_HEADER = "X-USER-ID";

    @Autowired
    private ExposedProducts exposedProducts;

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private LikeService likeService;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private User saveUser() {
        return userRepository.save(User.create());
    }

    private Product saveProduct(String name) {
        return exposedProducts.save(Product.create(1L, name, new Money(89_000), new Stock(10)));
    }

    private Product saveDeletedProduct(String name) {
        Product product = Product.create(1L, name, new Money(89_000), new Stock(10));
        product.delete();
        return productRepository.save(product);
    }

    private void saveLike(User user, Product product) {
        likeRepository.save(Like.create(user.getId(), product.getId()));
    }

    // 응답 필드 구성을 확인하려고 data 를 Map 으로 받는다.
    private ResponseEntity<ApiResponse<Map<String, Object>>> call(HttpMethod method, String url, String userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set(USER_ID_HEADER, userId);
        }
        return testRestTemplate.exchange(url, method, new HttpEntity<>(headers), new ParameterizedTypeReference<>() {});
    }

    private String likesUrl(Object productId) {
        return "/api/v1/products/" + productId + "/likes";
    }

    private String myLikesUrl(Object userId) {
        return "/api/v1/users/" + userId + "/likes";
    }

    private void assertFail(
        ResponseEntity<ApiResponse<Map<String, Object>>> response, HttpStatus status, String errorCode
    ) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().meta().errorCode()).isEqualTo(errorCode);
    }

    private void assertSuccess(ResponseEntity<ApiResponse<Map<String, Object>>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS);
    }

    @DisplayName("POST /api/v1/products/{productId}/likes")
    @Nested
    class Register {
        @DisplayName("좋아요를 등록하면 200 으로 응답하고, 관계가 저장된다.")
        @Test
        void savesLike() {
            User user = saveUser();
            Product product = saveProduct("러닝화");

            ResponseEntity<ApiResponse<Map<String, Object>>> response =
                call(HttpMethod.POST, likesUrl(product.getId()), String.valueOf(user.getId()));

            assertSuccess(response);
            assertThat(likeRepository.exists(user.getId(), product.getId())).isTrue();
        }

        @DisplayName("같은 상품에 다시 등록해도 200 이고, 관계는 하나만 유지된다.")
        @Test
        void keepsSingleLike_whenRegisteredTwice() {
            User user = saveUser();
            Product product = saveProduct("러닝화");
            String userId = String.valueOf(user.getId());

            assertSuccess(call(HttpMethod.POST, likesUrl(product.getId()), userId));
            assertSuccess(call(HttpMethod.POST, likesUrl(product.getId()), userId));

            assertThat(likeService.count(product.getId())).isEqualTo(1);
        }

        @DisplayName("없거나 삭제된 상품이면 404 NOT_FOUND 이고, 저장하지 않는다.")
        @Test
        void returnsNotFound_whenProductDoesNotExistOrIsDeleted() {
            User user = saveUser();
            Product deleted = saveDeletedProduct("삭제됨");
            String userId = String.valueOf(user.getId());

            assertFail(call(HttpMethod.POST, likesUrl(999), userId), HttpStatus.NOT_FOUND, "NOT_FOUND");
            assertFail(call(HttpMethod.POST, likesUrl(deleted.getId()), userId), HttpStatus.NOT_FOUND, "NOT_FOUND");
            assertThat(likeRepository.exists(user.getId(), deleted.getId())).isFalse();
        }

        @DisplayName("X-USER-ID 헤더가 없으면 400 INVALID_INPUT 이다.")
        @Test
        void returnsBadRequest_whenUserIdHeaderIsMissing() {
            Product product = saveProduct("러닝화");

            assertFail(call(HttpMethod.POST, likesUrl(product.getId()), null), HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }

        @DisplayName("존재하지 않는 사용자이면 404 NOT_FOUND 이다.")
        @Test
        void returnsNotFound_whenUserDoesNotExist() {
            Product product = saveProduct("러닝화");

            assertFail(call(HttpMethod.POST, likesUrl(product.getId()), "999"), HttpStatus.NOT_FOUND, "NOT_FOUND");
        }

        @DisplayName("productId 가 숫자가 아니면 400 INVALID_INPUT 이다.")
        @Test
        void returnsBadRequest_whenProductIdIsNotNumber() {
            User user = saveUser();

            assertFail(
                call(HttpMethod.POST, likesUrl("abc"), String.valueOf(user.getId())),
                HttpStatus.BAD_REQUEST, "INVALID_INPUT"
            );
        }
    }

    @DisplayName("DELETE /api/v1/products/{productId}/likes")
    @Nested
    class Cancel {
        @DisplayName("좋아요를 취소하면 200 으로 응답하고, 관계가 지워진다.")
        @Test
        void removesLike() {
            User user = saveUser();
            Product product = saveProduct("러닝화");
            saveLike(user, product);

            ResponseEntity<ApiResponse<Map<String, Object>>> response =
                call(HttpMethod.DELETE, likesUrl(product.getId()), String.valueOf(user.getId()));

            assertSuccess(response);
            assertThat(likeRepository.exists(user.getId(), product.getId())).isFalse();
        }

        @DisplayName("관계가 없어도 200 이다. 한 번도 존재한 적 없는 상품이어도 마찬가지다.")
        @Test
        void succeeds_whenLikeDoesNotExist() {
            User user = saveUser();
            Product product = saveProduct("러닝화");
            String userId = String.valueOf(user.getId());

            assertSuccess(call(HttpMethod.DELETE, likesUrl(product.getId()), userId));
            assertSuccess(call(HttpMethod.DELETE, likesUrl(999), userId));
        }

        @DisplayName("상품이 삭제된 뒤에도 자신의 기존 좋아요는 취소할 수 있다.")
        @Test
        void removesLike_evenAfterProductIsDeleted() {
            User user = saveUser();
            Product product = saveProduct("러닝화");
            saveLike(user, product);
            product.delete();
            productRepository.save(product);

            ResponseEntity<ApiResponse<Map<String, Object>>> response =
                call(HttpMethod.DELETE, likesUrl(product.getId()), String.valueOf(user.getId()));

            assertSuccess(response);
            assertThat(likeRepository.exists(user.getId(), product.getId())).isFalse();
        }

        @DisplayName("다른 사용자의 좋아요는 그대로다.")
        @Test
        void keepsOtherUsersLike() {
            User me = saveUser();
            User other = saveUser();
            Product product = saveProduct("러닝화");
            saveLike(me, product);
            saveLike(other, product);

            call(HttpMethod.DELETE, likesUrl(product.getId()), String.valueOf(me.getId()));

            assertThat(likeRepository.exists(other.getId(), product.getId())).isTrue();
        }

        @DisplayName("X-USER-ID 헤더가 없으면 400, 존재하지 않는 사용자이면 404 이다.")
        @Test
        void rejectsUnidentifiedRequester() {
            Product product = saveProduct("러닝화");

            assertFail(call(HttpMethod.DELETE, likesUrl(product.getId()), null), HttpStatus.BAD_REQUEST, "INVALID_INPUT");
            assertFail(call(HttpMethod.DELETE, likesUrl(product.getId()), "999"), HttpStatus.NOT_FOUND, "NOT_FOUND");
        }
    }

    @DisplayName("GET /api/v1/users/{userId}/likes")
    @Nested
    class MyLikes {
        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> contentOf(ResponseEntity<ApiResponse<Map<String, Object>>> response) {
            return (List<Map<String, Object>>) response.getBody().data().get("content");
        }

        @DisplayName("내가 좋아요한 상품만, 삭제된 상품은 빼고, 최근에 좋아요한 순으로 id·brandId·이름·가격만 응답한다.")
        @Test
        void returnsMyLikedProducts() {
            User me = saveUser();
            User other = saveUser();
            Product first = saveProduct("첫째");
            Product second = saveProduct("둘째");
            Product removed = saveDeletedProduct("삭제됨");
            Product notMine = saveProduct("남의 것");
            saveLike(me, second);
            saveLike(me, removed);
            saveLike(me, first);
            saveLike(other, notMine);

            ResponseEntity<ApiResponse<Map<String, Object>>> response =
                call(HttpMethod.GET, myLikesUrl(me.getId()), String.valueOf(me.getId()));

            assertSuccess(response);
            List<Map<String, Object>> content = contentOf(response);
            assertThat(content).hasSize(2);
            assertThat(content.get(0)).containsOnlyKeys("id", "brandId", "name", "price")
                .containsEntry("name", "첫째").containsEntry("price", 89_000);
            assertThat(content.get(1)).containsEntry("name", "둘째");
            assertThat(response.getBody().data())
                .containsEntry("page", 0).containsEntry("size", 20)
                .containsEntry("totalElements", 2).containsEntry("totalPages", 1);
        }

        @DisplayName("page 와 size 를 주면 해당 페이지만 응답한다.")
        @Test
        void returnsRequestedPage() {
            User me = saveUser();
            saveLike(me, saveProduct("하나"));
            saveLike(me, saveProduct("둘"));
            saveLike(me, saveProduct("셋"));

            ResponseEntity<ApiResponse<Map<String, Object>>> response =
                call(HttpMethod.GET, myLikesUrl(me.getId()) + "?page=1&size=2", String.valueOf(me.getId()));

            assertSuccess(response);
            assertThat(contentOf(response)).hasSize(1);
            assertThat(response.getBody().data())
                .containsEntry("page", 1).containsEntry("size", 2)
                .containsEntry("totalElements", 3).containsEntry("totalPages", 2);
        }

        @DisplayName("page 가 음수이거나 size 가 범위를 벗어나거나 숫자가 아니면 400 INVALID_INPUT 이다.")
        @ParameterizedTest
        @ValueSource(strings = {"page=-1", "size=0", "size=101", "size=abc"})
        void returnsBadRequest_whenPagingIsInvalid(String query) {
            User me = saveUser();

            assertFail(
                call(HttpMethod.GET, myLikesUrl(me.getId()) + "?" + query, String.valueOf(me.getId())),
                HttpStatus.BAD_REQUEST, "INVALID_INPUT"
            );
        }

        @DisplayName("다른 사용자의 userId 이면 403 FORBIDDEN 이다. 존재하지 않는 userId 여도 마찬가지다.")
        @Test
        void returnsForbidden_whenUserIdDiffersFromRequester() {
            User me = saveUser();
            User other = saveUser();
            String requester = String.valueOf(me.getId());

            ResponseEntity<ApiResponse<Map<String, Object>>> response =
                call(HttpMethod.GET, myLikesUrl(other.getId()), requester);

            assertFail(response, HttpStatus.FORBIDDEN, "FORBIDDEN");
            assertThat(response.getBody().meta().message()).isEqualTo("본인의 좋아요 목록만 조회할 수 있습니다.");
            assertFail(call(HttpMethod.GET, myLikesUrl(999), requester), HttpStatus.FORBIDDEN, "FORBIDDEN");
        }

        @DisplayName("userId 가 숫자가 아니면 400, X-USER-ID 헤더가 없으면 400, 존재하지 않는 요청자이면 404 이다.")
        @Test
        void rejectsInvalidIdentification() {
            User me = saveUser();

            assertFail(call(HttpMethod.GET, myLikesUrl("abc"), String.valueOf(me.getId())),
                HttpStatus.BAD_REQUEST, "INVALID_INPUT");
            assertFail(call(HttpMethod.GET, myLikesUrl(me.getId()), null), HttpStatus.BAD_REQUEST, "INVALID_INPUT");
            assertFail(call(HttpMethod.GET, myLikesUrl(999), "999"), HttpStatus.NOT_FOUND, "NOT_FOUND");
        }
    }
}
