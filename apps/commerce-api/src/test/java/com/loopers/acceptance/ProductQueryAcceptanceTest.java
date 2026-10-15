package com.loopers.acceptance;

import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 상품 조회 계약 — 인덱스·쿼리·캐시를 바꾼 뒤에도 그대로여야 하는 순서·페이지·비노출·오류 (docs/week4/design.md 2·3번 섹션).
 *
 * <p>A1(순서·페이지), A2(비노출), A11(오류).
 */
@DisplayName("상품 조회 계약")
class ProductQueryAcceptanceTest extends AcceptanceTest {

    private Long brandA;
    private Long brandB;

    // 브랜드 A: 가격 동점(apple·cherry 1,000원)과 좋아요 동점(banana·elder 2개)을 일부러 만든다.
    // 상품은 (브랜드와 무관하게) 이름의 역순으로 등록한다 — latest(createdAt DESC)에서 등록 시각이 같아져도
    // 동점 기준(name ASC)이 같은 순서를 내므로, 기대 순서가 시각 해상도에 흔들리지 않는다.
    private Long elder;
    private Long date;
    private Long cherry;
    private Long banana;
    private Long apple;
    private Long fig;
    private Long grape;

    @BeforeEach
    void setUp() {
        brandA = AdminSteps.createBrand("브랜드A");
        brandB = AdminSteps.createBrand("브랜드B");

        grape = AdminSteps.createProduct(brandB, "grape", 700L, 10);
        fig = AdminSteps.createProduct(brandB, "fig", 4_000L, 10);
        elder = AdminSteps.createProduct(brandA, "elder", 3_000L, 10);
        date = AdminSteps.createProduct(brandA, "date", 500L, 10);
        cherry = AdminSteps.createProduct(brandA, "cherry", 1_000L, 10);
        banana = AdminSteps.createProduct(brandA, "banana", 2_000L, 10);
        apple = AdminSteps.createProduct(brandA, "apple", 1_000L, 10);

        // 좋아요: date 3, banana 2, elder 2, apple 1, cherry 0 / grape 4, fig 0
        Long u1 = givenUser();
        Long u2 = givenUser();
        Long u3 = givenUser();
        Long u4 = givenUser();
        CustomerSteps.like(u1, date);
        CustomerSteps.like(u2, date);
        CustomerSteps.like(u3, date);
        CustomerSteps.like(u1, banana);
        CustomerSteps.like(u2, banana);
        CustomerSteps.like(u1, elder);
        CustomerSteps.like(u2, elder);
        CustomerSteps.like(u1, apple);
        for (Long u : List.of(u1, u2, u3, u4)) {
            CustomerSteps.like(u, grape);
        }
    }

    @DisplayName("A1 — 정렬과 페이지")
    @Nested
    class SortAndPage {

        @DisplayName("price_asc는 가격 오름차순이고, 가격이 같으면 이름·id 순이다.")
        @Test
        void priceAsc_withTieBreakByName() {
            List<Long> ids = CustomerSteps.getProductIds(Map.of("brandId", brandA, "sort", "price_asc"));

            assertThat(ids).containsExactly(date, apple, cherry, banana, elder);
        }

        @DisplayName("가격·좋아요가 같은 상품이 셋 이상이어도, 동점 순서는 id가 아니라 이름순이다.")
        @Test
        void tieBreak_byNameNotById() {
            // 등록 순서(id): melon < lemon < mango. 이름순(lemon, mango, melon)은 id 정순과도 역순과도 다르다 —
            // 동점이 둘뿐이면 이름순이 id 정순·역순 중 하나와 반드시 겹쳐서, 동점 기준이 id로 바뀌어도 테스트가 통과한다.
            Long brandC = AdminSteps.createBrand("브랜드C");
            Long melon = AdminSteps.createProduct(brandC, "melon", 1_000L, 10);
            Long lemon = AdminSteps.createProduct(brandC, "lemon", 1_000L, 10);
            Long mango = AdminSteps.createProduct(brandC, "mango", 1_000L, 10);

            assertAll(
                () -> assertThat(CustomerSteps.getProductIds(Map.of("brandId", brandC, "sort", "price_asc")))
                    .containsExactly(lemon, mango, melon),
                () -> assertThat(CustomerSteps.getProductIds(Map.of("brandId", brandC, "sort", "likes_desc")))
                    .containsExactly(lemon, mango, melon)
            );
        }

        @DisplayName("latest는 최근 등록순이다.")
        @Test
        void latest_newestFirst() {
            List<Long> ids = CustomerSteps.getProductIds(Map.of("brandId", brandA, "sort", "latest"));

            assertThat(ids).containsExactly(apple, banana, cherry, date, elder);
        }

        @DisplayName("likes_desc는 좋아요 수 내림차순이고, 같으면 이름·id 순이다.")
        @Test
        void likesDesc_withTieBreakByName() {
            List<Long> ids = CustomerSteps.getProductIds(Map.of("brandId", brandA, "sort", "likes_desc"));

            assertThat(ids).containsExactly(date, banana, elder, apple, cherry);
        }

        @DisplayName("likes_desc는 페이지 안이 아니라 전체 후보를 정렬한 뒤 자른다 — 페이지를 이어 붙이면 전체 순서와 같다.")
        @Test
        void likesDesc_sortsAllCandidatesBeforePaging() {
            List<Long> joined = new ArrayList<>();
            for (int page = 0; page < 3; page++) {
                joined.addAll(CustomerSteps.getProductIds(Map.of("brandId", brandA, "sort", "likes_desc", "page", page, "size", 2)));
            }

            assertThat(joined).containsExactly(date, banana, elder, apple, cherry);
        }

        @DisplayName("페이지를 넘겨도 빠지거나 겹치는 상품이 없고, 페이지 정보가 전체 건수와 맞는다.")
        @Test
        void paging_noGapNoOverlap_withPageInfo() {
            ExtractableResponse<Response> first = CustomerSteps.getProducts(Map.of("brandId", brandA, "sort", "price_asc", "page", 0, "size", 2));
            List<Long> joined = new ArrayList<>(first.jsonPath().getList("data.products.id", Long.class));
            joined.addAll(CustomerSteps.getProductIds(Map.of("brandId", brandA, "sort", "price_asc", "page", 1, "size", 2)));
            joined.addAll(CustomerSteps.getProductIds(Map.of("brandId", brandA, "sort", "price_asc", "page", 2, "size", 2)));

            assertAll(
                () -> assertThat(joined).containsExactly(date, apple, cherry, banana, elder),
                () -> assertThat(first.jsonPath().getInt("data.page")).isEqualTo(0),
                () -> assertThat(first.jsonPath().getInt("data.size")).isEqualTo(2),
                () -> assertThat(first.jsonPath().getLong("data.totalElements")).isEqualTo(5L),
                () -> assertThat(first.jsonPath().getInt("data.totalPages")).isEqualTo(3)
            );
        }

        @DisplayName("likes_desc의 전체 건수도 상품 수와 같다 — 좋아요 JOIN으로 행이 늘어나도 건수가 부풀지 않는다.")
        @Test
        void likesDesc_totalElementsCountsProductsNotLikes() {
            ExtractableResponse<Response> response = CustomerSteps.getProducts(Map.of("brandId", brandA, "sort", "likes_desc", "size", 2));

            assertAll(
                () -> assertThat(response.jsonPath().getLong("data.totalElements")).isEqualTo(5L),
                () -> assertThat(response.jsonPath().getInt("data.totalPages")).isEqualTo(3)
            );
        }

        @DisplayName("브랜드 필터가 없으면 모든 브랜드의 상품을 같은 정렬 기준으로 섞어서 보여준다.")
        @Test
        void withoutBrandFilter_allBrands() {
            List<Long> byPrice = CustomerSteps.getProductIds(Map.of("sort", "price_asc"));
            List<Long> byLikes = CustomerSteps.getProductIds(Map.of("sort", "likes_desc"));

            assertAll(
                () -> assertThat(byPrice).containsExactly(date, grape, apple, cherry, banana, elder, fig),
                () -> assertThat(byLikes).containsExactly(grape, date, banana, elder, apple, cherry, fig)
            );
        }

        @DisplayName("파라미터가 없으면 sort=latest, page=0, size=20이다.")
        @Test
        void defaults() {
            ExtractableResponse<Response> response = CustomerSteps.getProducts(Map.of());

            assertAll(
                () -> assertThat(response.statusCode()).isEqualTo(200),
                () -> assertThat(response.jsonPath().getList("data.products.id", Long.class))
                    .containsExactly(apple, banana, cherry, date, elder, fig, grape),
                () -> assertThat(response.jsonPath().getInt("data.page")).isEqualTo(0),
                () -> assertThat(response.jsonPath().getInt("data.size")).isEqualTo(20)
            );
        }

        @DisplayName("목록의 각 상품은 가격·재고·브랜드 정보·좋아요 수를 담는다.")
        @Test
        void listItemFields() {
            ExtractableResponse<Response> response = CustomerSteps.getProducts(Map.of("brandId", brandA, "sort", "price_asc", "size", 1));

            assertAll(
                () -> assertThat(response.jsonPath().getLong("data.products[0].id")).isEqualTo(date),
                () -> assertThat(response.jsonPath().getString("data.products[0].name")).isEqualTo("date"),
                () -> assertThat(response.jsonPath().getLong("data.products[0].price")).isEqualTo(500L),
                () -> assertThat(response.jsonPath().getInt("data.products[0].remainingStock")).isEqualTo(10),
                () -> assertThat(response.jsonPath().getLong("data.products[0].brand.id")).isEqualTo(brandA),
                () -> assertThat(response.jsonPath().getString("data.products[0].brand.name")).isEqualTo("브랜드A"),
                () -> assertThat(response.jsonPath().getLong("data.products[0].likeCount")).isEqualTo(3L)
            );
        }
    }

    @DisplayName("A2 — 비노출")
    @Nested
    class NonExposure {

        @DisplayName("삭제된 상품은 목록·전체 건수에서 빠지고, 상세는 404다.")
        @Test
        void deletedProduct_hiddenEverywhere() {
            AdminSteps.deleteProduct(date);

            ExtractableResponse<Response> list = CustomerSteps.getProducts(Map.of("brandId", brandA, "sort", "likes_desc"));

            assertAll(
                () -> assertThat(list.jsonPath().getList("data.products.id", Long.class)).doesNotContain(date),
                () -> assertThat(list.jsonPath().getLong("data.totalElements")).isEqualTo(4L),
                () -> assertThat(CustomerSteps.getProduct(date).statusCode()).isEqualTo(404)
            );
        }

        @DisplayName("삭제된 브랜드의 상품은 전체 목록에서 빠지고, 그 브랜드로 필터하면 200과 빈 목록이다.")
        @Test
        void deletedBrand_productsHidden() {
            AdminSteps.deleteBrand(brandB);

            ExtractableResponse<Response> filtered = CustomerSteps.getProducts(Map.of("brandId", brandB));

            assertAll(
                () -> assertThat(CustomerSteps.getProductIds(Map.of("sort", "price_asc"))).doesNotContain(grape, fig),
                () -> assertThat(filtered.statusCode()).isEqualTo(200),
                () -> assertThat(filtered.jsonPath().getList("data.products")).isEmpty(),
                () -> assertThat(filtered.jsonPath().getLong("data.totalElements")).isEqualTo(0L)
            );
        }

        @DisplayName("존재하지 않는 브랜드로 필터하면 404가 아니라 200과 빈 목록이다.")
        @Test
        void unknownBrand_emptyList() {
            ExtractableResponse<Response> response = CustomerSteps.getProducts(Map.of("brandId", 999_999L));

            assertAll(
                () -> assertThat(response.statusCode()).isEqualTo(200),
                () -> assertThat(response.jsonPath().getList("data.products")).isEmpty()
            );
        }
    }

    @DisplayName("A11 — 오류 계약")
    @Nested
    class Errors {

        @DisplayName("지원하지 않는 sort면 400이다.")
        @Test
        void unknownSort_badRequest() {
            assertThat(CustomerSteps.getProducts(Map.of("sort", "name_asc")).statusCode()).isEqualTo(400);
        }

        @DisplayName("page가 음수면 400이다.")
        @Test
        void negativePage_badRequest() {
            assertThat(CustomerSteps.getProducts(Map.of("page", -1)).statusCode()).isEqualTo(400);
        }

        @DisplayName("size가 1~100을 벗어나면 400이다.")
        @ParameterizedTest
        @ValueSource(ints = {0, 101})
        void sizeOutOfRange_badRequest(int size) {
            assertThat(CustomerSteps.getProducts(Map.of("size", size)).statusCode()).isEqualTo(400);
        }

        @DisplayName("size가 상한인 100이면 정상 응답이다.")
        @Test
        void maxSize_ok() {
            assertThat(CustomerSteps.getProducts(Map.of("size", 100)).statusCode()).isEqualTo(200);
        }
    }
}
