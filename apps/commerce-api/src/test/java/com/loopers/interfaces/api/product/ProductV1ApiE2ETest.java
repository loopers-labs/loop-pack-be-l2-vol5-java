package com.loopers.interfaces.api.product;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.fixture.BrandFixture;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
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

@DisplayName("상품 API 는 활성 상품의 목록과 상세를 제공한다.")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductV1ApiE2ETest {

    private static final String ENDPOINT = "/api/v1/products";
    private static final String USER_ID_HEADER = "X-USER-ID";

    private static final ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductResponse>> DETAIL_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> PAGE_TYPE =
        new ParameterizedTypeReference<>() {};

    @Autowired
    private TestRestTemplate testRestTemplate;
    @Autowired
    private UserFixture userFixture;
    @Autowired
    private BrandFixture brandFixture;
    @Autowired
    private ProductFixture productFixture;
    @Autowired
    private LikeService likeService;
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

    private ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> getList(String query, String userId) {
        return testRestTemplate.exchange(ENDPOINT + query, HttpMethod.GET, request(userId), PAGE_TYPE);
    }

    private ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> getDetail(Object productId, String userId) {
        return testRestTemplate.exchange(ENDPOINT + "/" + productId, HttpMethod.GET, request(userId), DETAIL_TYPE);
    }

    @DisplayName("GET /api/v1/products/{productId}")
    @Nested
    class GetProduct {
        @DisplayName("상품·브랜드명·좋아요 수·현재 재고 수량을 200 으로 반환한다.")
        @Test
        void returnsProductDetail() {
            UserModel user = userFixture.createUserWithPoint();
            BrandModel nike = brandFixture.createBrand("나이키");
            ProductModel shoes = productFixture.createProduct(nike.getId(), "운동화", 89_000L, 7L);
            likeService.like(user.getId(), shoes.getId());

            ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> response =
                getDetail(shoes.getId(), String.valueOf(user.getId()));

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().id()).isEqualTo(shoes.getId()),
                () -> assertThat(response.getBody().data().brandId()).isEqualTo(nike.getId()),
                () -> assertThat(response.getBody().data().brandName()).isEqualTo("나이키"),
                () -> assertThat(response.getBody().data().name()).isEqualTo("운동화"),
                () -> assertThat(response.getBody().data().price()).isEqualTo(89_000L),
                () -> assertThat(response.getBody().data().likeCount()).isEqualTo(1L),
                () -> assertThat(response.getBody().data().stockQuantity()).isEqualTo(7L)
            );
        }

        @DisplayName("존재하지 않는 상품은 404 PRODUCT_NOT_FOUND 로 응답한다.")
        @Test
        void rejectsUnknownProduct() {
            UserModel user = userFixture.createUserWithPoint();

            ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> response =
                getDetail(999999L, String.valueOf(user.getId()));

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("PRODUCT_NOT_FOUND"),
                () -> assertThat(response.getBody().data()).isNull()
            );
        }

        @DisplayName("삭제된 상품은 노출하지 않고 404 PRODUCT_NOT_FOUND 로 응답한다.")
        @Test
        void hidesDeletedProduct() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel deleted = productFixture.createDeletedProduct("단종 운동화", 10_000L, 3L);

            ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> response =
                getDetail(deleted.getId(), String.valueOf(user.getId()));

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("PRODUCT_NOT_FOUND")
            );
        }

        @DisplayName("X-USER-ID 가 없으면 400 INVALID_REQUEST 로 거절한다.")
        @Test
        void rejectsMissingHeader() {
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 3L);

            ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> response = getDetail(shoes.getId(), null);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("INVALID_REQUEST")
            );
        }
    }

    @DisplayName("GET /api/v1/products")
    @Nested
    class GetProducts {
        @DisplayName("sort 를 생략하면 최신 등록순으로 활성 상품 페이지를 반환한다.")
        @Test
        void returnsLatestByDefault() {
            UserModel user = userFixture.createUserWithPoint();
            productFixture.createProduct("첫째", 1_000L, 1L);
            productFixture.createProduct("둘째", 2_000L, 2L);
            productFixture.createDeletedProduct("삭제됨", 3_000L, 3L);
            productFixture.createProduct("셋째", 3_000L, 3L);

            ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> response =
                getList("", String.valueOf(user.getId()));

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().totalElements()).isEqualTo(3L),
                () -> assertThat(response.getBody().data().items())
                    .extracting(ProductV1Dto.ProductResponse::name)
                    .containsExactly("셋째", "둘째", "첫째")
            );
        }

        @DisplayName("price_asc 는 가격 오름차순으로 반환한다.")
        @Test
        void sortsByPriceAsc() {
            UserModel user = userFixture.createUserWithPoint();
            productFixture.createProduct("비쌈", 30_000L, 1L);
            productFixture.createProduct("보통", 20_000L, 1L);
            productFixture.createProduct("저렴", 10_000L, 1L);

            ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> response =
                getList("?sort=price_asc", String.valueOf(user.getId()));

            assertThat(response.getBody().data().items())
                .extracting(ProductV1Dto.ProductResponse::name)
                .containsExactly("저렴", "보통", "비쌈");
        }

        @DisplayName("likes_desc 는 좋아요 수 내림차순으로 반환한다.")
        @Test
        void sortsByLikesDesc() {
            UserModel user = userFixture.createUserWithPoint();
            UserModel other = userFixture.createUserWithPoint();
            ProductModel popular = productFixture.createProduct("인기 상품", 10_000L, 1L);
            ProductModel unpopular = productFixture.createProduct("한산한 상품", 10_000L, 1L);
            likeService.like(user.getId(), popular.getId());
            likeService.like(other.getId(), popular.getId());

            ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> response =
                getList("?sort=likes_desc", String.valueOf(user.getId()));

            assertAll(
                () -> assertThat(response.getBody().data().items())
                    .extracting(ProductV1Dto.ProductResponse::id)
                    .containsExactly(popular.getId(), unpopular.getId()),
                () -> assertThat(response.getBody().data().items().get(0).likeCount()).isEqualTo(2L)
            );
        }

        @DisplayName("brandId 로 해당 브랜드의 상품만 반환한다.")
        @Test
        void filtersByBrand() {
            UserModel user = userFixture.createUserWithPoint();
            BrandModel nike = brandFixture.createBrand("나이키");
            BrandModel adidas = brandFixture.createBrand("아디다스");
            productFixture.createProduct(nike.getId(), "나이키 운동화", 10_000L, 1L);
            productFixture.createProduct(adidas.getId(), "아디다스 슬리퍼", 20_000L, 1L);

            ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> response =
                getList("?brandId=" + nike.getId(), String.valueOf(user.getId()));

            assertAll(
                () -> assertThat(response.getBody().data().totalElements()).isEqualTo(1L),
                () -> assertThat(response.getBody().data().items())
                    .extracting(ProductV1Dto.ProductResponse::name)
                    .containsExactly("나이키 운동화")
            );
        }

        @DisplayName("[잠정] 삭제되었거나 존재하지 않는 brandId 로 조회하면 200 과 빈 페이지를 반환한다.")
        @Test
        void returnsEmptyPageForUnknownBrand() {
            UserModel user = userFixture.createUserWithPoint();
            productFixture.createProduct("운동화", 10_000L, 1L);

            ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> response =
                getList("?brandId=999999", String.valueOf(user.getId()));

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().items()).isEmpty(),
                () -> assertThat(response.getBody().data().totalElements()).isZero()
            );
        }

        @DisplayName("page 와 size 로 끊어 전체 개수·전체 페이지 수와 함께 반환한다.")
        @Test
        void returnsRequestedPage() {
            UserModel user = userFixture.createUserWithPoint();
            productFixture.createProduct("첫째", 1_000L, 1L);
            productFixture.createProduct("둘째", 2_000L, 1L);
            productFixture.createProduct("셋째", 3_000L, 1L);

            ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> response =
                getList("?page=1&size=2&sort=price_asc", String.valueOf(user.getId()));

            assertAll(
                () -> assertThat(response.getBody().data().page()).isEqualTo(1),
                () -> assertThat(response.getBody().data().size()).isEqualTo(2),
                () -> assertThat(response.getBody().data().totalElements()).isEqualTo(3L),
                () -> assertThat(response.getBody().data().totalPages()).isEqualTo(2),
                () -> assertThat(response.getBody().data().items())
                    .extracting(ProductV1Dto.ProductResponse::name)
                    .containsExactly("셋째")
            );
        }

        @DisplayName("지원하지 않는 sort 는 400 INVALID_SORT 로 거절한다.")
        @Test
        void rejectsUnsupportedSort() {
            UserModel user = userFixture.createUserWithPoint();

            ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> response =
                getList("?sort=cheapest", String.valueOf(user.getId()));

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("INVALID_SORT")
            );
        }

        @DisplayName("허용 범위를 넘는 size 는 400 INVALID_PAGE_REQUEST 로 거절한다.")
        @Test
        void rejectsTooLargeSize() {
            UserModel user = userFixture.createUserWithPoint();

            ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> response =
                getList("?size=101", String.valueOf(user.getId()));

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("INVALID_PAGE_REQUEST")
            );
        }

        @DisplayName("저장되지 않은 사용자 ID 로 요청하면 404 USER_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsUnknownUser() {
            ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> response =
                getList("", "999999");

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("USER_NOT_FOUND")
            );
        }
    }
}
