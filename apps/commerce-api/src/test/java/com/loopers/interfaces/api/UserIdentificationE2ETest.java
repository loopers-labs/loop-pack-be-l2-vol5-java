package com.loopers.interfaces.api;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 고객 요청의 로컬 실습용 사용자 식별(X-USER-ID 헤더) 거절 규칙(실습 과제 4절).
 * 식별 누락은 입력 오류(400), 없는 사용자는 대상 없음(404)으로 거절하고 아무것도 저장하지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserIdentificationE2ETest {

    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final Long NOT_EXISTING_USER_ID = 999999L;

    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final UserJpaRepository userJpaRepository;
    private final LikeJpaRepository likeJpaRepository;
    private final OrderJpaRepository orderJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    private User user;
    private Product product;

    @Autowired
    UserIdentificationE2ETest(
        TestRestTemplate testRestTemplate,
        BrandJpaRepository brandJpaRepository,
        ProductJpaRepository productJpaRepository,
        UserJpaRepository userJpaRepository,
        LikeJpaRepository likeJpaRepository,
        OrderJpaRepository orderJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.userJpaRepository = userJpaRepository;
        this.likeJpaRepository = likeJpaRepository;
        this.orderJpaRepository = orderJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        user = userJpaRepository.save(new User("user1"));
        Brand brand = brandJpaRepository.save(new Brand("루퍼스"));
        product = productJpaRepository.save(new Product(brand.getId(), "가방", 3_000L, 5L));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private ResponseEntity<ApiResponse<Object>> request(HttpMethod method, String url, Long requesterId, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (requesterId != null) {
            headers.set(USER_ID_HEADER, String.valueOf(requesterId));
        }
        ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
        return testRestTemplate.exchange(url, method, new HttpEntity<>(jsonBody, headers), responseType);
    }

    private void assertFailed(ResponseEntity<ApiResponse<Object>> response, HttpStatus status) {
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(status),
            () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(response.getBody().data()).isNull()
        );
    }

    @DisplayName("X-USER-ID 헤더가 없으면, ")
    @Nested
    class MissingHeader {

        @DisplayName("좋아요 등록·취소·내 목록은 400 BAD_REQUEST 응답을 받고 관계가 저장되지 않는다.")
        @Test
        void rejectsLikeApis() {
            // act
            ResponseEntity<ApiResponse<Object>> like = request(HttpMethod.POST, "/api/v1/products/" + product.getId() + "/likes", null, null);
            ResponseEntity<ApiResponse<Object>> unlike = request(HttpMethod.DELETE, "/api/v1/products/" + product.getId() + "/likes", null, null);
            ResponseEntity<ApiResponse<Object>> likedProducts = request(HttpMethod.GET, "/api/v1/users/" + user.getId() + "/likes", null, null);

            // assert
            assertFailed(like, HttpStatus.BAD_REQUEST);
            assertFailed(unlike, HttpStatus.BAD_REQUEST);
            assertFailed(likedProducts, HttpStatus.BAD_REQUEST);
            assertThat(likeJpaRepository.count()).isZero();
        }

        @DisplayName("포인트 충전·잔액 조회는 400 BAD_REQUEST 응답을 받고 잔액이 유지된다.")
        @Test
        void rejectsPointApis() {
            // act
            ResponseEntity<ApiResponse<Object>> charge = request(HttpMethod.POST, "/api/v1/points/charge", null, "{\"amount\": 1000}");
            ResponseEntity<ApiResponse<Object>> balance = request(HttpMethod.GET, "/api/v1/points", null, null);

            // assert
            assertFailed(charge, HttpStatus.BAD_REQUEST);
            assertFailed(balance, HttpStatus.BAD_REQUEST);
            assertThat(userJpaRepository.findById(user.getId()).orElseThrow().getBalance()).isZero();
        }

        @DisplayName("주문 생성·확정·목록·상세는 400 BAD_REQUEST 응답을 받고 주문이 저장되지 않는다.")
        @Test
        void rejectsOrderApis() {
            // act
            ResponseEntity<ApiResponse<Object>> create = request(HttpMethod.POST, "/api/v1/orders", null,
                "{\"items\": [{\"productId\": " + product.getId() + ", \"quantity\": 1}]}");
            ResponseEntity<ApiResponse<Object>> confirm = request(HttpMethod.POST, "/api/v1/orders/1/confirm", null, null);
            ResponseEntity<ApiResponse<Object>> orders = request(HttpMethod.GET, "/api/v1/orders", null, null);
            ResponseEntity<ApiResponse<Object>> order = request(HttpMethod.GET, "/api/v1/orders/1", null, null);

            // assert
            assertFailed(create, HttpStatus.BAD_REQUEST);
            assertFailed(confirm, HttpStatus.BAD_REQUEST);
            assertFailed(orders, HttpStatus.BAD_REQUEST);
            assertFailed(order, HttpStatus.BAD_REQUEST);
            assertThat(orderJpaRepository.count()).isZero();
        }
    }

    @DisplayName("X-USER-ID가 없는 사용자면, ")
    @Nested
    class NotExistingUser {

        @DisplayName("좋아요 등록은 404 NOT_FOUND 응답을 받고 관계가 저장되지 않는다.")
        @Test
        void rejectsLike_andSavesNothing() {
            // act
            ResponseEntity<ApiResponse<Object>> response = request(HttpMethod.POST, "/api/v1/products/" + product.getId() + "/likes", NOT_EXISTING_USER_ID, null);

            // assert
            assertFailed(response, HttpStatus.NOT_FOUND);
            assertThat(likeJpaRepository.count()).isZero();
        }

        @DisplayName("좋아요 취소·내 목록은 404 NOT_FOUND 응답을 받는다.")
        @Test
        void rejectsUnlikeAndLikedProducts() {
            // act
            ResponseEntity<ApiResponse<Object>> unlike = request(HttpMethod.DELETE, "/api/v1/products/" + product.getId() + "/likes", NOT_EXISTING_USER_ID, null);
            ResponseEntity<ApiResponse<Object>> likedProducts = request(HttpMethod.GET, "/api/v1/users/" + NOT_EXISTING_USER_ID + "/likes", NOT_EXISTING_USER_ID, null);

            // assert
            assertFailed(unlike, HttpStatus.NOT_FOUND);
            assertFailed(likedProducts, HttpStatus.NOT_FOUND);
            assertThat(likedProducts.getBody().meta().message()).contains("사용자");
        }

        @DisplayName("포인트 잔액 조회는 404 NOT_FOUND 응답을 받는다.")
        @Test
        void rejectsBalance() {
            // act
            ResponseEntity<ApiResponse<Object>> response = request(HttpMethod.GET, "/api/v1/points", NOT_EXISTING_USER_ID, null);

            // assert
            assertFailed(response, HttpStatus.NOT_FOUND);
        }

        @DisplayName("주문 생성은 404 NOT_FOUND 응답을 받고 주문이 저장되지 않는다.")
        @Test
        void rejectsOrderCreation_andSavesNothing() {
            // act
            ResponseEntity<ApiResponse<Object>> response = request(HttpMethod.POST, "/api/v1/orders", NOT_EXISTING_USER_ID,
                "{\"items\": [{\"productId\": " + product.getId() + ", \"quantity\": 1}]}");

            // assert
            assertFailed(response, HttpStatus.NOT_FOUND);
            assertThat(orderJpaRepository.count()).isZero();
        }
    }
}
