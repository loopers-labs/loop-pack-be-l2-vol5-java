package com.loopers.interfaces.api.product;

import com.loopers.application.like.LikeFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.application.brand.BrandFacade;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.Price;
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
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductV1ApiE2ETest {

    private static final String CUSTOMER = "/api/v1/products";
    private static final Long USER = 1L;
    private static final Long OTHER_USER = 2L;

    private final TestRestTemplate testRestTemplate;
    private final LikeService likeService;
    private final DatabaseCleanUp databaseCleanUp;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;
    private final LikeFacade likeFacade;

    private Long brandId;

    @Autowired
    ProductV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        LikeService likeService,
        DatabaseCleanUp databaseCleanUp,
        BrandFacade brandFacade,
        ProductFacade productFacade,
        LikeFacade likeFacade
    ) {
        this.testRestTemplate = testRestTemplate;
        this.likeService = likeService;
        this.databaseCleanUp = databaseCleanUp;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
        this.likeFacade = likeFacade;
    }

    @BeforeEach
    void setUp() {
        brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private static final ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductResponse>> PRODUCT =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductPageResponse>> PAGE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<Object>> ANY =
        new ParameterizedTypeReference<>() {};

    private HttpEntity<Void> as(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(userId));
        return new HttpEntity<>(headers);
    }

    private Long product(String name, long price) {
        return productFacade.register(brandId, name, Price.of(price)).getId();
    }

    private List<ProductV1Dto.ProductResponse> list(String query) {
        return testRestTemplate.exchange(CUSTOMER + query, HttpMethod.GET, as(USER), PAGE)
            .getBody().data().items();
    }

    @Nested
    @DisplayName("상세 — Q-2 · 재고 수량을 주지 않는다")
    class Detail {
        @DisplayName("브랜드 정보 · 좋아요 수 · liked · 품절 여부가 담긴다")
        @Test
        void returnsDetail() {
            Long productId = product("코트", 129_000);
            productFacade.adjustStock(productId, Quantity.of(3));
            likeFacade.like(OTHER_USER, productId);

            ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> response =
                testRestTemplate.exchange(CUSTOMER + "/" + productId, HttpMethod.GET, as(USER), PRODUCT);

            assertAll(
                () -> assertThat(response.getStatusCode().value()).isEqualTo(200),
                () -> assertThat(response.getBody().data().brandId()).isEqualTo(brandId),
                () -> assertThat(response.getBody().data().brandName()).isEqualTo("무신사"),
                () -> assertThat(response.getBody().data().likeCount()).isEqualTo(1),
                () -> assertThat(response.getBody().data().liked()).isFalse(),
                () -> assertThat(response.getBody().data().soldOut()).isFalse()
            );
        }

        @DisplayName("Q-2 · 고객 응답 어디에도 stock · quantity 필드가 없다")
        @Test
        void neverExposesStockQuantity() {
            Long productId = product("코트", 129_000);
            productFacade.adjustStock(productId, Quantity.of(7));

            ResponseEntity<String> detail = testRestTemplate.exchange(
                CUSTOMER + "/" + productId, HttpMethod.GET, as(USER), String.class);
            ResponseEntity<String> listBody = testRestTemplate.exchange(
                CUSTOMER, HttpMethod.GET, as(USER), String.class);

            assertAll(
                () -> assertThat(detail.getBody()).doesNotContain("\"stock\"").doesNotContain("\"quantity\""),
                () -> assertThat(listBody.getBody()).doesNotContain("\"stock\"").doesNotContain("\"quantity\""),
                () -> assertThat(detail.getBody()).contains("\"soldOut\"")
            );
        }

        @DisplayName("PRODUCT-022 · 재고가 0이면 soldOut 이지만 여전히 200 이다")
        @Test
        void soldOutIsStillVisible() {
            Long productId = product("코트", 129_000);

            ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> response =
                testRestTemplate.exchange(CUSTOMER + "/" + productId, HttpMethod.GET, as(USER), PRODUCT);

            assertAll(
                () -> assertThat(response.getStatusCode().value()).isEqualTo(200),
                () -> assertThat(response.getBody().data().soldOut()).isTrue()
            );
        }

        @DisplayName("PRODUCT-005 · 삭제된 상품의 상세는 404 다")
        @Test
        void hidesDeleted() {
            Long productId = product("코트", 129_000);
            productFacade.delete(productId);

            ResponseEntity<ApiResponse<Object>> response =
                testRestTemplate.exchange(CUSTOMER + "/" + productId, HttpMethod.GET, as(USER), ANY);

            assertAll(
                () -> assertThat(response.getStatusCode().value()).isEqualTo(404),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("PRODUCT_NOT_FOUND")
            );
        }

        @DisplayName("PRODUCT-008 · liked 는 요청자마다 다르다")
        @Test
        void likedIsPerRequester() {
            Long productId = product("코트", 129_000);
            likeFacade.like(USER, productId);

            boolean byUser = testRestTemplate.exchange(
                CUSTOMER + "/" + productId, HttpMethod.GET, as(USER), PRODUCT).getBody().data().liked();
            boolean byOther = testRestTemplate.exchange(
                CUSTOMER + "/" + productId, HttpMethod.GET, as(OTHER_USER), PRODUCT).getBody().data().liked();

            assertAll(
                () -> assertThat(byUser).isTrue(),
                () -> assertThat(byOther).isFalse()
            );
        }

        @DisplayName("COMMON-001 · X-USER-ID 가 없으면 400 이다. 401 이 아니다 — 인증이 아니라 식별이다")
        @Test
        void rejectsMissingUserHeader() {
            Long productId = product("코트", 129_000);

            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                CUSTOMER + "/" + productId, HttpMethod.GET, HttpEntity.EMPTY, ANY);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("목록 — 한 쿼리로 집계까지")
    class ListProducts {
        @DisplayName("PRODUCT-005 · 삭제된 상품은 목록에 없다")
        @Test
        void excludesDeleted() {
            Long kept = product("코트", 129_000);
            Long removed = product("셔츠", 59_000);
            productFacade.delete(removed);

            assertThat(list("")).extracting(ProductV1Dto.ProductResponse::id).containsExactly(kept);
        }

        @DisplayName("PRODUCT-011 · price_asc 는 싼 것부터다")
        @Test
        void sortsByPriceAsc() {
            product("코트", 129_000);
            product("셔츠", 59_000);

            assertThat(list("?sort=price_asc"))
                .extracting(ProductV1Dto.ProductResponse::name).containsExactly("셔츠", "코트");
        }

        @DisplayName("PRODUCT-013 · likes_desc 는 좋아요가 많은 것부터다. 수는 관계를 세어 얻는다")
        @Test
        void sortsByLikesDesc() {
            Long few = product("코트", 129_000);
            Long many = product("셔츠", 59_000);
            likeFacade.like(USER, many);
            likeFacade.like(OTHER_USER, many);
            likeFacade.like(USER, few);

            List<ProductV1Dto.ProductResponse> items = list("?sort=likes_desc");

            assertAll(
                () -> assertThat(items).extracting(ProductV1Dto.ProductResponse::id).containsExactly(many, few),
                () -> assertThat(items.get(0).likeCount()).isEqualTo(2)
            );
        }

        @DisplayName("PRODUCT-012 · 동률이면 id 내림차순이다. 같은 요청은 항상 같은 순서다")
        @Test
        void breaksTiesByIdDesc() {
            Long first = product("코트", 10_000);
            Long second = product("셔츠", 10_000);

            assertThat(list("?sort=price_asc"))
                .extracting(ProductV1Dto.ProductResponse::id).containsExactly(second, first);
        }

        @DisplayName("PRODUCT-010 · brandId 로 거를 수 있다")
        @Test
        void filtersByBrand() {
            Long mine = product("코트", 129_000);
            Long other = brandFacade.register("29CM", "셀렉트샵").getId();
            productFacade.register(other, "셔츠", Price.of(59_000));

            assertThat(list("?brandId=" + brandId))
                .extracting(ProductV1Dto.ProductResponse::id).containsExactly(mine);
        }

        @DisplayName("PRODUCT-011 · 모르는 정렬은 400 이다. 조용히 기본값으로 넘어가지 않는다")
        @Test
        void rejectsUnknownSort() {
            ResponseEntity<ApiResponse<Object>> response =
                testRestTemplate.exchange(CUSTOMER + "?sort=name", HttpMethod.GET, as(USER), ANY);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }

        @DisplayName("COMMON-008 · size 가 100 을 넘으면 400 이다")
        @Test
        void rejectsTooLargeSize() {
            ResponseEntity<ApiResponse<Object>> response =
                testRestTemplate.exchange(CUSTOMER + "?size=1000", HttpMethod.GET, as(USER), ANY);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("거절되면 아무것도 바뀌지 않는다")
    class RejectionKeepsState {
        @DisplayName("LIKE-004 · 삭제된 상품에 좋아요가 거절되면 좋아요 수가 그대로다")
        @Test
        void rejectedLikeKeepsCount() {
            Long productId = product("코트", 129_000);
            likeFacade.like(OTHER_USER, productId);
            productFacade.delete(productId);

            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                CUSTOMER + "/" + productId + "/likes", HttpMethod.POST, as(USER), ANY);

            assertAll(
                () -> assertThat(response.getStatusCode().value()).isEqualTo(404),
                () -> assertThat(likeService.countOf(productId)).isEqualTo(1)
            );
        }

        @DisplayName("잘못된 정렬로 거절돼도 데이터는 그대로고, 바른 요청은 계속 동작한다")
        @Test
        void rejectedQueryChangesNothing() {
            Long productId = product("코트", 129_000);

            testRestTemplate.exchange(CUSTOMER + "?sort=name", HttpMethod.GET, as(USER), ANY);

            assertThat(list("")).extracting(ProductV1Dto.ProductResponse::id).containsExactly(productId);
        }
    }
}
