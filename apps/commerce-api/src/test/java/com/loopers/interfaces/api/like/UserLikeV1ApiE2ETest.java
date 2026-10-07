package com.loopers.interfaces.api.like;

import com.loopers.application.brand.BrandAdminFacade;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.like.LikeModel;
import com.loopers.domain.product.ProductModel;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserLikeV1ApiE2ETest {

    private final TestRestTemplate testRestTemplate;
    private final LikeJpaRepository likeJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final BrandJpaRepository brandJpaRepository;
    private final BrandAdminFacade brandAdminFacade;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public UserLikeV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        LikeJpaRepository likeJpaRepository,
        ProductJpaRepository productJpaRepository,
        BrandJpaRepository brandJpaRepository,
        BrandAdminFacade brandAdminFacade,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.likeJpaRepository = likeJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.brandJpaRepository = brandJpaRepository;
        this.brandAdminFacade = brandAdminFacade;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private HttpEntity<Void> requestWithUser(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-USER-ID", String.valueOf(userId));
        return new HttpEntity<>(null, headers);
    }

    @DisplayName("GET /api/v1/users/{userId}/likes")
    @Nested
    class Get {
        @DisplayName("본인의 좋아요 목록을 조회하면, 200과 좋아요한 productId 목록을 반환한다.")
        @Test
        void returns200WithProductIds_whenRequesterIsOwner() {
            // arrange
            Long productA = productJpaRepository.save(new ProductModel("상품A", 1000L, 1L, 10)).getId();
            Long productB = productJpaRepository.save(new ProductModel("상품B", 1000L, 1L, 10)).getId();
            likeJpaRepository.save(new LikeModel(1L, productA));
            likeJpaRepository.save(new LikeModel(1L, productB));
            String requestUrl = "/api/v1/users/1/likes";

            // act
            ParameterizedTypeReference<ApiResponse<UserLikeV1Dto.LikeListResponse>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<UserLikeV1Dto.LikeListResponse>> response =
                testRestTemplate.exchange(requestUrl, HttpMethod.GET, requestWithUser(1L), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().productIds()).containsExactlyInAnyOrder(productA, productB);
        }

        @DisplayName("브랜드가 삭제되면, 그 브랜드 상품은 목록에서 빠지고 다른 브랜드 상품은 남는다 — 좋아요 행 자체는 보존된다.")
        @Test
        void excludesProductsOfDeletedBrand_butKeepsLikeRows() {
            // arrange
            BrandModel deletedBrand = brandJpaRepository.save(new BrandModel("나이키", "스포츠 브랜드", "신발/의류"));
            BrandModel otherBrand = brandJpaRepository.save(new BrandModel("아디다스", "스포츠 브랜드", "신발/의류"));
            Long removed = productJpaRepository.save(new ProductModel("에어맥스", 1000L, deletedBrand.getId(), 10)).getId();
            Long kept = productJpaRepository.save(new ProductModel("울트라부스트", 1000L, otherBrand.getId(), 10)).getId();
            likeJpaRepository.save(new LikeModel(1L, removed));
            likeJpaRepository.save(new LikeModel(1L, kept));
            brandAdminFacade.deleteBrand(deletedBrand.getId());

            // act
            ParameterizedTypeReference<ApiResponse<UserLikeV1Dto.LikeListResponse>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<UserLikeV1Dto.LikeListResponse>> response =
                testRestTemplate.exchange("/api/v1/users/1/likes", HttpMethod.GET, requestWithUser(1L), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().productIds()).containsExactly(kept);
            assertThat(likeJpaRepository.existsByUserIdAndProductId(1L, removed)).isTrue();
        }

        @DisplayName("다른 사용자의 좋아요 목록을 조회하면, 404를 반환한다.")
        @Test
        void returns404_whenRequesterIsNotOwner() {
            // arrange
            likeJpaRepository.save(new LikeModel(2L, 10L));
            String requestUrl = "/api/v1/users/2/likes";

            // act
            ParameterizedTypeReference<ApiResponse<UserLikeV1Dto.LikeListResponse>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<UserLikeV1Dto.LikeListResponse>> response =
                testRestTemplate.exchange(requestUrl, HttpMethod.GET, requestWithUser(1L), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
