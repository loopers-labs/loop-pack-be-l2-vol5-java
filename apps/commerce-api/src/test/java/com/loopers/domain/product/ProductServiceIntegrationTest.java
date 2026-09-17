package com.loopers.domain.product;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.user.UserModel;
import com.loopers.fixture.BrandFixture;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("ProductService 는 브랜드명·좋아요 수·현재 재고를 포함한 상품 조회를 담당한다.")
@SpringBootTest
class ProductServiceIntegrationTest {

    @Autowired
    private ProductService productService;
    @Autowired
    private LikeService likeService;
    @Autowired
    private BrandFixture brandFixture;
    @Autowired
    private ProductFixture productFixture;
    @Autowired
    private UserFixture userFixture;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private PageResult<ProductQueryResult> page(Long brandId, ProductSort sort) {
        return productService.getProducts(brandId, PageCommand.of(null, null), sort);
    }

    @DisplayName("상세 조회")
    @Nested
    class Detail {
        @DisplayName("상품·브랜드명·좋아요 수·현재 재고 수량을 함께 반환한다.")
        @Test
        void returnsDetailWithBrandAndLikeCount() {
            BrandModel brand = brandFixture.createBrand("나이키");
            ProductModel shirt = productFixture.createProduct(brand.getId(), "티셔츠", 19_900L, 5L);
            UserModel first = userFixture.createUserWithPoint();
            UserModel second = userFixture.createUserWithPoint();
            likeService.like(first.getId(), shirt.getId());
            likeService.like(second.getId(), shirt.getId());

            ProductQueryResult result = productService.getProduct(shirt.getId());

            assertAll(
                () -> assertThat(result.id()).isEqualTo(shirt.getId()),
                () -> assertThat(result.brandId()).isEqualTo(brand.getId()),
                () -> assertThat(result.brandName()).isEqualTo("나이키"),
                () -> assertThat(result.name()).isEqualTo("티셔츠"),
                () -> assertThat(result.price()).isEqualTo(19_900L),
                () -> assertThat(result.likeCount()).isEqualTo(2L),
                () -> assertThat(result.stockQuantity()).isEqualTo(5L)
            );
        }

        @DisplayName("좋아요가 없으면 좋아요 수는 0 이다.")
        @Test
        void returnsZeroLikeCount() {
            ProductModel shirt = productFixture.createProduct("티셔츠", 19_900L, 5L);

            assertThat(productService.getProduct(shirt.getId()).likeCount()).isZero();
        }

        @DisplayName("관리자가 재고를 바꾸면 조회 시점의 수량을 반환한다.")
        @Test
        void returnsCurrentStockQuantity() {
            ProductModel shirt = productFixture.createProduct("티셔츠", 19_900L, 5L);
            assertThat(productService.getProduct(shirt.getId()).stockQuantity()).isEqualTo(5L);

            productService.changeStock(shirt.getId(), 2L);

            assertThat(productService.getProduct(shirt.getId()).stockQuantity()).isEqualTo(2L);
        }

        @DisplayName("없는 상품과 삭제된 상품은 PRODUCT_NOT_FOUND 로 응답한다.")
        @Test
        void rejectsMissingOrDeletedProduct() {
            ProductModel deleted = productFixture.createDeletedProduct("단종 티셔츠", 19_900L, 5L);

            assertAll(
                () -> assertThatThrownBy(() -> productService.getProduct(999_999L))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType").isEqualTo(ErrorType.PRODUCT_NOT_FOUND),
                () -> assertThatThrownBy(() -> productService.getProduct(deleted.getId()))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType").isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
            );
        }
    }

    @DisplayName("목록 조회")
    @Nested
    class ListProducts {
        @DisplayName("삭제된 상품은 목록에서 제외한다.")
        @Test
        void excludesDeletedProduct() {
            ProductModel active = productFixture.createProduct("티셔츠", 19_900L, 5L);
            productFixture.createDeletedProduct("단종 티셔츠", 19_900L, 5L);

            PageResult<ProductQueryResult> result = page(null, ProductSort.LATEST);

            assertAll(
                () -> assertThat(result.totalElements()).isEqualTo(1L),
                () -> assertThat(result.items()).extracting(ProductQueryResult::id).containsExactly(active.getId())
            );
        }

        @DisplayName("브랜드로 거르면 해당 브랜드의 상품만 반환한다.")
        @Test
        void filtersByBrand() {
            BrandModel nike = brandFixture.createBrand("나이키");
            BrandModel adidas = brandFixture.createBrand("아디다스");
            ProductModel nikeShirt = productFixture.createProduct(nike.getId(), "나이키 티셔츠", 19_900L, 5L);
            productFixture.createProduct(adidas.getId(), "아디다스 티셔츠", 29_900L, 5L);

            PageResult<ProductQueryResult> result = page(nike.getId(), ProductSort.LATEST);

            assertAll(
                () -> assertThat(result.totalElements()).isEqualTo(1L),
                () -> assertThat(result.items()).extracting(ProductQueryResult::id)
                    .containsExactly(nikeShirt.getId())
            );
        }

        @DisplayName("존재하지 않거나 삭제된 브랜드로 거르면 빈 페이지를 반환한다.")
        @Test
        void returnsEmptyPageForUnknownBrand() {
            BrandModel deleted = brandFixture.createDeletedBrand("사라진 브랜드");
            productFixture.createProduct("티셔츠", 19_900L, 5L);

            assertAll(
                () -> assertThat(page(999_999L, ProductSort.LATEST).items()).isEmpty(),
                () -> assertThat(page(999_999L, ProductSort.LATEST).totalElements()).isZero(),
                () -> assertThat(page(deleted.getId(), ProductSort.LATEST).items()).isEmpty()
            );
        }

        @DisplayName("latest 는 최근 등록한 상품부터 반환한다.")
        @Test
        void sortsByLatest() {
            ProductModel first = productFixture.createProduct("첫번째", 3_000L, 5L);
            ProductModel second = productFixture.createProduct("두번째", 2_000L, 5L);
            ProductModel third = productFixture.createProduct("세번째", 1_000L, 5L);

            assertThat(page(null, ProductSort.LATEST).items()).extracting(ProductQueryResult::id)
                .containsExactly(third.getId(), second.getId(), first.getId());
        }

        @DisplayName("price_asc 는 가격 오름차순으로 반환한다.")
        @Test
        void sortsByPriceAsc() {
            ProductModel expensive = productFixture.createProduct("비싼 것", 30_000L, 5L);
            ProductModel cheap = productFixture.createProduct("싼 것", 1_000L, 5L);
            ProductModel middle = productFixture.createProduct("중간 것", 10_000L, 5L);

            assertThat(page(null, ProductSort.PRICE_ASC).items()).extracting(ProductQueryResult::id)
                .containsExactly(cheap.getId(), middle.getId(), expensive.getId());
        }

        @DisplayName("가격이 같으면 상품 ID 오름차순으로 반환한다.")
        @Test
        void sortsBySameePriceThenId() {
            ProductModel first = productFixture.createProduct("첫번째", 5_000L, 5L);
            ProductModel second = productFixture.createProduct("두번째", 5_000L, 5L);

            assertThat(page(null, ProductSort.PRICE_ASC).items()).extracting(ProductQueryResult::id)
                .containsExactly(first.getId(), second.getId());
        }

        @DisplayName("likes_desc 는 좋아요 수 내림차순으로 반환하고 동률은 상품 ID 내림차순으로 반환한다.")
        @Test
        void sortsByLikesDesc() {
            ProductModel popular = productFixture.createProduct("인기 상품", 1_000L, 5L);
            ProductModel noLike1 = productFixture.createProduct("무관심 1", 1_000L, 5L);
            ProductModel noLike2 = productFixture.createProduct("무관심 2", 1_000L, 5L);
            UserModel first = userFixture.createUserWithPoint();
            UserModel second = userFixture.createUserWithPoint();
            likeService.like(first.getId(), popular.getId());
            likeService.like(second.getId(), popular.getId());

            assertThat(page(null, ProductSort.LIKES_DESC).items()).extracting(ProductQueryResult::id)
                .containsExactly(popular.getId(), noLike2.getId(), noLike1.getId());
        }

        @DisplayName("페이지 크기만큼 나누고 전체 상품 수를 함께 반환한다.")
        @Test
        void returnsPagedResult() {
            productFixture.createProduct("첫번째", 1_000L, 5L);
            productFixture.createProduct("두번째", 2_000L, 5L);
            productFixture.createProduct("세번째", 3_000L, 5L);

            PageResult<ProductQueryResult> result =
                productService.getProducts(null, PageCommand.of(0, 2), ProductSort.PRICE_ASC);

            assertAll(
                () -> assertThat(result.items()).hasSize(2),
                () -> assertThat(result.totalElements()).isEqualTo(3L),
                () -> assertThat(result.totalPages()).isEqualTo(2)
            );
        }
    }

    @DisplayName("정렬 입력")
    @Nested
    class SortInput {
        @DisplayName("생략하면 latest 를 사용하고 지원하지 않는 값은 INVALID_SORT 로 거절한다.")
        @Test
        void parsesSortValue() {
            assertAll(
                () -> assertThat(ProductSort.from(null)).isEqualTo(ProductSort.LATEST),
                () -> assertThat(ProductSort.from("price_asc")).isEqualTo(ProductSort.PRICE_ASC),
                () -> assertThat(ProductSort.from("likes_desc")).isEqualTo(ProductSort.LIKES_DESC),
                () -> assertThatThrownBy(() -> ProductSort.from("oldest"))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType").isEqualTo(ErrorType.INVALID_SORT)
            );
        }
    }
}
