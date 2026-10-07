package com.loopers.interfaces.api;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.like.LikeModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.interfaces.api.like.LikeV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LikeV1ApiE2ETest {

    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final ParameterizedTypeReference<ApiResponse<LikeV1Dto.LikeResponse>> LIKE_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<LikeV1Dto.MyLikesResponse>> MY_LIKES_TYPE =
        new ParameterizedTypeReference<>() {};

    private final TestRestTemplate testRestTemplate;
    private final UserJpaRepository userJpaRepository;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final LikeJpaRepository likeJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private ProductModel product;

    @Autowired
    public LikeV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        UserJpaRepository userJpaRepository,
        BrandJpaRepository brandJpaRepository,
        ProductJpaRepository productJpaRepository,
        LikeJpaRepository likeJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.userJpaRepository = userJpaRepository;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.likeJpaRepository = likeJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        user = userJpaRepository.save(new UserModel("실습 사용자"));
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        product = productJpaRepository.save(new ProductModel(brand.getId(), "상품", 10_000L, 5));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private HttpHeaders headersOf(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set(USER_ID_HEADER, String.valueOf(userId));
        }
        return headers;
    }

    private ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>> like(Long userId, Long productId) {
        return testRestTemplate.exchange(
            "/api/v1/products/" + productId + "/likes", HttpMethod.POST,
            new HttpEntity<>(null, headersOf(userId)), LIKE_TYPE
        );
    }

    private ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>> unlike(Long userId, Long productId) {
        return testRestTemplate.exchange(
            "/api/v1/products/" + productId + "/likes", HttpMethod.DELETE,
            new HttpEntity<>(null, headersOf(userId)), LIKE_TYPE
        );
    }

    @DisplayName("좋아요를 등록하면 관계가 저장되고, 중복 등록해도 효과가 늘지 않는다.")
    @Test
    void registersLike_idempotently() {
        // act
        ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>> first = like(user.getId(), product.getId());
        ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>> second = like(user.getId(), product.getId());

        // assert
        assertAll(
            () -> assertTrue(first.getStatusCode().is2xxSuccessful()),
            () -> assertTrue(second.getStatusCode().is2xxSuccessful()),
            () -> assertThat(second.getBody().data().likeCount()).isEqualTo(1L),
            () -> assertThat(likeJpaRepository.count()).isEqualTo(1L)
        );
    }

    @DisplayName("삭제된 상품에는 새 좋아요를 등록할 수 없다 (404).")
    @Test
    void rejectsLike_whenProductIsDeleted() {
        // arrange
        product.delete();
        productJpaRepository.save(product);

        // act
        ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>> response = like(user.getId(), product.getId());

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @DisplayName("좋아요 취소는 관계를 제거하고, 없는 관계를 취소해도 200이다.")
    @Test
    void cancelsLike_idempotently() {
        // arrange
        like(user.getId(), product.getId());

        // act
        ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>> first = unlike(user.getId(), product.getId());
        ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>> second = unlike(user.getId(), product.getId());

        // assert
        assertAll(
            () -> assertTrue(first.getStatusCode().is2xxSuccessful()),
            () -> assertTrue(second.getStatusCode().is2xxSuccessful()),
            () -> assertThat(likeJpaRepository.count()).isEqualTo(0L)
        );
    }

    @DisplayName("삭제된 상품에 남아 있는 자신의 좋아요 관계는 취소할 수 있다.")
    @Test
    void cancelsLike_evenWhenProductIsDeleted() {
        // arrange
        likeJpaRepository.save(new LikeModel(user.getId(), product.getId()));
        product.delete();
        productJpaRepository.save(product);

        // act
        ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>> response = unlike(user.getId(), product.getId());

        // assert
        assertAll(
            () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
            () -> assertThat(likeJpaRepository.count()).isEqualTo(0L)
        );
    }

    @DisplayName("내 좋아요 목록은 삭제된 상품을 제외하고, 상품 정보와 좋아요 수를 포함한다.")
    @Test
    void returnsMyLikes_excludingDeletedProducts() {
        // arrange
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드B"));
        ProductModel deleted = new ProductModel(brand.getId(), "삭제된 상품", 20_000L, 5);
        deleted.delete();
        ProductModel savedDeleted = productJpaRepository.save(deleted);
        likeJpaRepository.save(new LikeModel(user.getId(), product.getId()));
        likeJpaRepository.save(new LikeModel(user.getId(), savedDeleted.getId()));

        // act
        ResponseEntity<ApiResponse<LikeV1Dto.MyLikesResponse>> response = testRestTemplate.exchange(
            "/api/v1/users/" + user.getId() + "/likes", HttpMethod.GET,
            new HttpEntity<>(null, headersOf(user.getId())), MY_LIKES_TYPE
        );

        // assert
        assertAll(
            () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
            () -> assertThat(response.getBody().data().items()).hasSize(1),
            () -> assertThat(response.getBody().data().items().get(0).id()).isEqualTo(product.getId()),
            () -> assertThat(response.getBody().data().items().get(0).likeCount()).isEqualTo(1L)
        );
    }

    @DisplayName("타인의 좋아요 목록을 요청하면, 404로 거절한다.")
    @Test
    void rejectsMyLikes_whenRequestingOthersList() {
        // arrange
        UserModel other = userJpaRepository.save(new UserModel("다른 사용자"));

        // act
        ResponseEntity<ApiResponse<LikeV1Dto.MyLikesResponse>> response = testRestTemplate.exchange(
            "/api/v1/users/" + other.getId() + "/likes", HttpMethod.GET,
            new HttpEntity<>(null, headersOf(user.getId())), MY_LIKES_TYPE
        );

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @DisplayName("X-USER-ID 헤더가 없으면, 400으로 거절한다.")
    @Test
    void rejectsLike_whenUserIdHeaderIsMissing() {
        // act
        ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>> response = like(null, product.getId());

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
