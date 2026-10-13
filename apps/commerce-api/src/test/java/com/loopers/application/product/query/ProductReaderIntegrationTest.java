package com.loopers.application.product.query;

import com.loopers.application.product.ProductFacade;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.support.error.ErrorType;
import com.loopers.support.fixture.Fixtures;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProductReaderIntegrationTest {

    @Autowired
    private ProductReader productReader;
    /** 삭제를 실제 경로(상품·좋아요 수 행 함께)로 만들 때만 쓴다. */
    @Autowired
    private ProductFacade productFacade;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private UserModel admin;
    private BrandModel brand;

    @BeforeEach
    void setUp() {
        user = fixtures.user();
        admin = fixtures.admin();
        brand = fixtures.brand("브랜드");
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Nested
    @DisplayName("FR-PRODUCT-01 상품 목록 조회")
    class ListProducts {
        @DisplayName("[FR-PRODUCT-01] 삭제되지 않은 상품만, 브랜드 정보와 좋아요 수 포함 (INV-05). latest 는 최신순.")
        @Test
        void listsActiveProducts_latest() {
            ProductModel p1 = fixtures.product(brand.getId(), "상품1", 1000L, 1);
            ProductModel p2 = fixtures.product(brand.getId(), "상품2", 2000L, 1);
            fixtures.deletedProduct(brand.getId(), "삭제됨", 500L, 1);
            fixtures.like(user.getId(), p1.getId());

            PageResult<ProductView.Summary> page = productReader.listProducts(user.getId(), null, ProductSort.LATEST, PageQuery.of(0, 10));

            assertThat(page.totalCount()).isEqualTo(2);
            assertThat(page.items()).extracting(ProductView.Summary::id).containsExactly(p2.getId(), p1.getId());
            assertThat(page.items().get(1).likeCount()).isEqualTo(1);
            assertThat(page.items().get(1).brandName()).isEqualTo("브랜드");
        }

        @DisplayName("[FR-PRODUCT-01] price_asc: 가격 오름차순, 동률은 id 내림차순 (ASM-08).")
        @Test
        void sortsByPriceAsc_tieBrokenByIdDesc() {
            ProductModel cheapOld = fixtures.product(brand.getId(), "싼것-옛", 100L, 1);
            ProductModel expensive = fixtures.product(brand.getId(), "비싼것", 900L, 1);
            ProductModel cheapNew = fixtures.product(brand.getId(), "싼것-새", 100L, 1);

            PageResult<ProductView.Summary> page = productReader.listProducts(user.getId(), null, ProductSort.PRICE_ASC, PageQuery.of(0, 10));

            assertThat(page.items()).extracting(ProductView.Summary::id)
                .containsExactly(cheapNew.getId(), cheapOld.getId(), expensive.getId());
        }

        @DisplayName("[FR-PRODUCT-01] likes_desc: 좋아요 수 내림차순, 동률은 id 내림차순. 좋아요 0 인 상품도 포함.")
        @Test
        void sortsByLikesDesc() {
            UserModel other = fixtures.user();
            ProductModel noLike = fixtures.product(brand.getId(), "0개", 100L, 1);
            ProductModel twoLikes = fixtures.product(brand.getId(), "2개", 100L, 1);
            ProductModel oneLike = fixtures.product(brand.getId(), "1개", 100L, 1);
            fixtures.like(user.getId(), twoLikes.getId());
            fixtures.like(other.getId(), twoLikes.getId());
            fixtures.like(user.getId(), oneLike.getId());

            PageResult<ProductView.Summary> page = productReader.listProducts(user.getId(), null, ProductSort.LIKES_DESC, PageQuery.of(0, 10));

            assertThat(page.items()).extracting(ProductView.Summary::id)
                .containsExactly(twoLikes.getId(), oneLike.getId(), noLike.getId());
            assertThat(page.items()).extracting(ProductView.Summary::likeCount).containsExactly(2L, 1L, 0L);
        }

        @DisplayName("[FR-PRODUCT-01] likes_desc 도 페이지 크기·totalCount 가 맞고, 정렬 뒤의 해당 구간을 돌려준다.")
        @Test
        void likesDesc_pagesCorrectly() {
            List<ProductModel> products = new ArrayList<>();
            List<UserModel> likers = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                products.add(fixtures.product(brand.getId(), "상품" + i, 100L, 1));
                likers.add(fixtures.user());
            }
            // 상품 i 의 좋아요 수 = i. 정렬 순서는 상품4, 3, 2, 1, 0.
            for (int i = 0; i < 5; i++) {
                for (int j = 0; j < i; j++) {
                    fixtures.like(likers.get(j).getId(), products.get(i).getId());
                }
            }

            PageResult<ProductView.Summary> page = productReader.listProducts(user.getId(), null, ProductSort.LIKES_DESC, PageQuery.of(1, 2));

            assertThat(page.items()).extracting(ProductView.Summary::id)
                .containsExactly(products.get(2).getId(), products.get(1).getId());
            assertThat(page.items()).extracting(ProductView.Summary::likeCount).containsExactly(2L, 1L);
            assertThat(page.totalCount()).isEqualTo(5);
        }

        @DisplayName("[FR-PRODUCT-01][ASM-08] likes_desc 동률이면 id 내림차순.")
        @Test
        void likesDesc_tieBrokenByIdDesc() {
            ProductModel older = fixtures.product(brand.getId(), "옛", 100L, 1);
            ProductModel newer = fixtures.product(brand.getId(), "새", 100L, 1);
            fixtures.like(user.getId(), older.getId());
            fixtures.like(user.getId(), newer.getId());

            PageResult<ProductView.Summary> page = productReader.listProducts(user.getId(), null, ProductSort.LIKES_DESC, PageQuery.of(0, 10));

            assertThat(page.items()).extracting(ProductView.Summary::id).containsExactly(newer.getId(), older.getId());
        }

        @DisplayName("[FR-PRODUCT-01][DR-36] likes_desc 는 좋아요가 가장 많아도 삭제된 상품을 빼고, 브랜드 필터에서도 같다.")
        @Test
        void likesDesc_excludesDeletedProducts() {
            UserModel other = fixtures.user();
            ProductModel active = fixtures.product(brand.getId(), "판매 중", 100L, 1);
            ProductModel deleted = fixtures.product(brand.getId(), "삭제 예정", 100L, 1);
            fixtures.like(user.getId(), deleted.getId());
            fixtures.like(other.getId(), deleted.getId());
            productFacade.deleteProduct(admin.getId(), deleted.getId());

            PageResult<ProductView.Summary> all = productReader.listProducts(user.getId(), null, ProductSort.LIKES_DESC, PageQuery.of(0, 10));
            PageResult<ProductView.Summary> byBrand = productReader.listProducts(user.getId(), brand.getId(), ProductSort.LIKES_DESC, PageQuery.of(0, 10));

            assertThat(all.items()).extracting(ProductView.Summary::id).containsExactly(active.getId());
            assertThat(all.totalCount()).isEqualTo(1);
            assertThat(byBrand.items()).extracting(ProductView.Summary::id).containsExactly(active.getId());
            assertThat(byBrand.totalCount()).isEqualTo(1);
        }

        @DisplayName("[FR-PRODUCT-01] brandId 가 있으면 세 정렬 모두 그 브랜드의 삭제되지 않은 상품만, totalCount 도 그 기준 (DR-35).")
        @Test
        void filtersByBrand_allSorts() {
            BrandModel other = fixtures.brand("다른 브랜드");
            ProductModel cheap = fixtures.product(brand.getId(), "싼것", 100L, 1);
            ProductModel expensive = fixtures.product(brand.getId(), "비싼것", 900L, 1);
            fixtures.deletedProduct(brand.getId(), "삭제됨", 500L, 1);
            ProductModel otherBrands = fixtures.product(other.getId(), "다른 브랜드 상품", 50L, 1);
            fixtures.like(user.getId(), cheap.getId());
            fixtures.like(user.getId(), otherBrands.getId());

            PageResult<ProductView.Summary> latest = productReader.listProducts(user.getId(), brand.getId(), ProductSort.LATEST, PageQuery.of(0, 10));
            PageResult<ProductView.Summary> priceAsc = productReader.listProducts(user.getId(), brand.getId(), ProductSort.PRICE_ASC, PageQuery.of(0, 10));
            PageResult<ProductView.Summary> likesDesc = productReader.listProducts(user.getId(), brand.getId(), ProductSort.LIKES_DESC, PageQuery.of(0, 10));

            assertThat(latest.totalCount()).isEqualTo(2);
            assertThat(latest.items()).extracting(ProductView.Summary::id).containsExactly(expensive.getId(), cheap.getId());
            assertThat(priceAsc.totalCount()).isEqualTo(2);
            assertThat(priceAsc.items()).extracting(ProductView.Summary::id).containsExactly(cheap.getId(), expensive.getId());
            assertThat(likesDesc.totalCount()).isEqualTo(2);
            assertThat(likesDesc.items()).extracting(ProductView.Summary::id).containsExactly(cheap.getId(), expensive.getId());
        }

        @DisplayName("[FR-PRODUCT-01] 없는 브랜드·삭제된 브랜드의 brandId 는 거절하지 않고 빈 목록 (DR-35).")
        @Test
        void unknownOrDeletedBrand_returnsEmpty() {
            BrandModel deletedBrand = fixtures.deletedBrand("삭제된 브랜드");
            fixtures.product(brand.getId(), "상품", 100L, 1);

            PageResult<ProductView.Summary> unknown = productReader.listProducts(user.getId(), 999L, ProductSort.LATEST, PageQuery.of(0, 10));
            PageResult<ProductView.Summary> deleted = productReader.listProducts(user.getId(), deletedBrand.getId(), ProductSort.LATEST, PageQuery.of(0, 10));

            assertThat(unknown.items()).isEmpty();
            assertThat(unknown.totalCount()).isZero();
            assertThat(deleted.items()).isEmpty();
            assertThat(deleted.totalCount()).isZero();
        }

        @DisplayName("[FR-PRODUCT-01 INVALID_PAGE] 페이지 값이 잘못되면 거절.")
        @Test
        void throwsInvalidPage() {
            assertThrowsErrorType(() -> productReader.listProducts(user.getId(), null, ProductSort.LATEST, PageQuery.of(0, 0)), ErrorType.INVALID_PAGE);
        }

        @DisplayName("[FR-PRODUCT-01 USER_NOT_FOUND] 요청자가 없으면 거절.")
        @Test
        void throwsUserNotFound() {
            assertThrowsErrorType(() -> productReader.listProducts(999L, null, ProductSort.LATEST, PageQuery.of(0, 10)), ErrorType.USER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("FR-PRODUCT-02 상품 상세 조회")
    class GetProduct {
        @DisplayName("[FR-PRODUCT-02] 상품 정보 + 브랜드 정보 + 좋아요 수 (INV-05, INV-10).")
        @Test
        void returnsProductWithBrandAndLikeCount() {
            ProductModel product = fixtures.product(brand.getId(), "상품", 1000L, 3);
            fixtures.like(user.getId(), product.getId());

            ProductView.Summary info = productReader.getProduct(user.getId(), product.getId());

            assertThat(info.name()).isEqualTo("상품");
            assertThat(info.brandId()).isEqualTo(brand.getId());
            assertThat(info.likeCount()).isEqualTo(1);
        }

        @DisplayName("[FR-PRODUCT-02 PRODUCT_NOT_FOUND] 존재하지 않는 상품.")
        @Test
        void throwsProductNotFound_whenMissing() {
            assertThrowsErrorType(() -> productReader.getProduct(user.getId(), 999L), ErrorType.PRODUCT_NOT_FOUND);
        }

        @DisplayName("[FR-PRODUCT-02 PRODUCT_NOT_FOUND] 삭제된 상품도 같은 실패.")
        @Test
        void throwsProductNotFound_whenDeleted() {
            ProductModel deleted = fixtures.deletedProduct(brand.getId(), "삭제됨", 1000L, 3);

            assertThrowsErrorType(() -> productReader.getProduct(user.getId(), deleted.getId()), ErrorType.PRODUCT_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("FR-ADMIN-PRODUCT-01 상품 목록 (관리자)")
    class ListProductsForAdmin {
        @DisplayName("[FR-ADMIN-PRODUCT-01] 삭제 포함, 재고·삭제 여부·좋아요 수 포함, 최신순.")
        @Test
        void listsAllIncludingDeleted() {
            ProductModel active = fixtures.product(brand.getId(), "활성", 1000L, 7);
            ProductModel deleted = fixtures.deletedProduct(brand.getId(), "삭제됨", 1000L, 0);

            PageResult<ProductView.Admin> page = productReader.listProductsForAdmin(admin.getId(), PageQuery.of(0, 10));

            assertThat(page.totalCount()).isEqualTo(2);
            assertThat(page.items()).extracting(ProductView.Admin::id).containsExactly(deleted.getId(), active.getId());
            assertThat(page.items()).extracting(ProductView.Admin::deleted).containsExactly(true, false);
            assertThat(page.items().get(1).stock()).isEqualTo(7);
        }

        @DisplayName("[FR-ADMIN-PRODUCT-01 INVALID_PAGE] 페이지 값이 잘못되면 거절.")
        @Test
        void throwsInvalidPage() {
            assertThrowsErrorType(() -> productReader.listProductsForAdmin(admin.getId(), PageQuery.of(0, 101)), ErrorType.INVALID_PAGE);
        }

        @DisplayName("[FR-ADMIN-PRODUCT-01 NOT_ADMIN] 관리자가 아니면 거절.")
        @Test
        void throwsNotAdmin() {
            assertThrowsErrorType(() -> productReader.listProductsForAdmin(user.getId(), PageQuery.of(0, 10)), ErrorType.NOT_ADMIN);
        }
    }

    @Nested
    @DisplayName("FR-ADMIN-PRODUCT-03 상품 상세 (관리자)")
    class GetProductForAdmin {
        @DisplayName("[FR-ADMIN-PRODUCT-03] 삭제된 상품도 조회되며 재고·삭제 여부·좋아요 수 포함.")
        @Test
        void returnsDeletedProduct() {
            ProductModel deleted = fixtures.deletedProduct(brand.getId(), "삭제됨", 1000L, 4);
            fixtures.like(user.getId(), deleted.getId());

            ProductView.Admin info = productReader.getProductForAdmin(admin.getId(), deleted.getId());

            assertThat(info.deleted()).isTrue();
            assertThat(info.stock()).isEqualTo(4);
            assertThat(info.likeCount()).isEqualTo(1);
        }

        @DisplayName("[FR-ADMIN-PRODUCT-03 PRODUCT_NOT_FOUND]")
        @Test
        void throwsProductNotFound() {
            assertThrowsErrorType(() -> productReader.getProductForAdmin(admin.getId(), 999L), ErrorType.PRODUCT_NOT_FOUND);
        }
    }
}
