package com.loopers.application.catalog;

import com.loopers.domain.catalog.BrandModel;
import com.loopers.domain.catalog.ProductModel;
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

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProductFacadeIntegrationTest {

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
        @DisplayName("[FR-PRODUCT-01] 삭제되지 않은 상품만, 브랜드 정보와 좋아요 수 포함 (INV-05). 기본 정렬은 latest.")
        @Test
        void listsActiveProducts_latestByDefault() {
            ProductModel p1 = fixtures.product(brand.getId(), "상품1", 1000L, 1);
            ProductModel p2 = fixtures.product(brand.getId(), "상품2", 2000L, 1);
            fixtures.deletedProduct(brand.getId(), "삭제됨", 500L, 1);
            fixtures.like(user.getId(), p1.getId());

            PageResult<ProductInfo> page = productFacade.listProducts(user.getId(), null, PageQuery.of(0, 10));

            assertThat(page.totalCount()).isEqualTo(2);
            assertThat(page.items()).extracting(ProductInfo::id).containsExactly(p2.getId(), p1.getId());
            assertThat(page.items().get(1).likeCount()).isEqualTo(1);
            assertThat(page.items().get(1).brand().name()).isEqualTo("브랜드");
        }

        @DisplayName("[FR-PRODUCT-01] price_asc: 가격 오름차순, 동률은 id 내림차순 (ASM-08).")
        @Test
        void sortsByPriceAsc_tieBrokenByIdDesc() {
            ProductModel cheapOld = fixtures.product(brand.getId(), "싼것-옛", 100L, 1);
            ProductModel expensive = fixtures.product(brand.getId(), "비싼것", 900L, 1);
            ProductModel cheapNew = fixtures.product(brand.getId(), "싼것-새", 100L, 1);

            PageResult<ProductInfo> page = productFacade.listProducts(user.getId(), "price_asc", PageQuery.of(0, 10));

            assertThat(page.items()).extracting(ProductInfo::id)
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

            PageResult<ProductInfo> page = productFacade.listProducts(user.getId(), "likes_desc", PageQuery.of(0, 10));

            assertThat(page.items()).extracting(ProductInfo::id)
                .containsExactly(twoLikes.getId(), oneLike.getId(), noLike.getId());
            assertThat(page.items()).extracting(ProductInfo::likeCount).containsExactly(2L, 1L, 0L);
        }

        @DisplayName("[FR-PRODUCT-01] likes_desc 도 페이지 크기·totalCount 가 맞다.")
        @Test
        void likesDesc_pagesCorrectly() {
            for (int i = 0; i < 5; i++) {
                fixtures.product(brand.getId(), "상품" + i, 100L, 1);
            }

            PageResult<ProductInfo> page = productFacade.listProducts(user.getId(), "likes_desc", PageQuery.of(1, 2));

            assertThat(page.items()).hasSize(2);
            assertThat(page.totalCount()).isEqualTo(5);
        }

        @DisplayName("[FR-PRODUCT-01 INVALID_SORT] 허용 목록 밖 정렬값은 거절.")
        @Test
        void throwsInvalidSort() {
            assertThrowsErrorType(() -> productFacade.listProducts(user.getId(), "name_asc", PageQuery.of(0, 10)), ErrorType.INVALID_SORT);
        }

        @DisplayName("[FR-PRODUCT-01 INVALID_PAGE] 페이지 값이 잘못되면 거절.")
        @Test
        void throwsInvalidPage() {
            assertThrowsErrorType(() -> productFacade.listProducts(user.getId(), null, PageQuery.of(0, 0)), ErrorType.INVALID_PAGE);
        }

        @DisplayName("[FR-PRODUCT-01 USER_NOT_FOUND] 요청자가 없으면 거절.")
        @Test
        void throwsUserNotFound() {
            assertThrowsErrorType(() -> productFacade.listProducts(999L, null, PageQuery.of(0, 10)), ErrorType.USER_NOT_FOUND);
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

            ProductInfo info = productFacade.getProduct(user.getId(), product.getId());

            assertThat(info.name()).isEqualTo("상품");
            assertThat(info.brand().id()).isEqualTo(brand.getId());
            assertThat(info.likeCount()).isEqualTo(1);
        }

        @DisplayName("[FR-PRODUCT-02 PRODUCT_NOT_FOUND] 존재하지 않는 상품.")
        @Test
        void throwsProductNotFound_whenMissing() {
            assertThrowsErrorType(() -> productFacade.getProduct(user.getId(), 999L), ErrorType.PRODUCT_NOT_FOUND);
        }

        @DisplayName("[FR-PRODUCT-02 PRODUCT_NOT_FOUND] 삭제된 상품도 같은 실패.")
        @Test
        void throwsProductNotFound_whenDeleted() {
            ProductModel deleted = fixtures.deletedProduct(brand.getId(), "삭제됨", 1000L, 3);

            assertThrowsErrorType(() -> productFacade.getProduct(user.getId(), deleted.getId()), ErrorType.PRODUCT_NOT_FOUND);
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

            PageResult<ProductInfo> page = productFacade.listProductsForAdmin(admin.getId(), PageQuery.of(0, 10));

            assertThat(page.totalCount()).isEqualTo(2);
            assertThat(page.items()).extracting(ProductInfo::id).containsExactly(deleted.getId(), active.getId());
            assertThat(page.items()).extracting(ProductInfo::deleted).containsExactly(true, false);
            assertThat(page.items().get(1).stock()).isEqualTo(7);
        }

        @DisplayName("[FR-ADMIN-PRODUCT-01 INVALID_PAGE] 페이지 값이 잘못되면 거절.")
        @Test
        void throwsInvalidPage() {
            assertThrowsErrorType(() -> productFacade.listProductsForAdmin(admin.getId(), PageQuery.of(0, 101)), ErrorType.INVALID_PAGE);
        }

        @DisplayName("[FR-ADMIN-PRODUCT-01 NOT_ADMIN] 관리자가 아니면 거절.")
        @Test
        void throwsNotAdmin() {
            assertThrowsErrorType(() -> productFacade.listProductsForAdmin(user.getId(), PageQuery.of(0, 10)), ErrorType.NOT_ADMIN);
        }
    }

    @Nested
    @DisplayName("FR-ADMIN-PRODUCT-02 상품 생성")
    class CreateProduct {
        @DisplayName("[FR-ADMIN-PRODUCT-02][INV-10][INV-11] 생성되고 브랜드가 연결되며 고객 조회에 즉시 반영.")
        @Test
        void createsProduct() {
            ProductInfo created = productFacade.createProduct(admin.getId(), brand.getId(), "새 상품", 1500L, 10);

            assertThat(created.brand().id()).isEqualTo(brand.getId());
            assertThat(created.stock()).isEqualTo(10);
            assertThat(created.deleted()).isFalse();
            assertThat(productFacade.getProduct(user.getId(), created.id()).price()).isEqualTo(1500L);
        }

        @DisplayName("[FR-ADMIN-PRODUCT-02 BRAND_NOT_FOUND] 브랜드가 없으면 생성되지 않는다.")
        @Test
        void throwsBrandNotFound_whenMissing() {
            assertThrowsErrorType(() -> productFacade.createProduct(admin.getId(), 999L, "상품", 1000L, 1), ErrorType.BRAND_NOT_FOUND);
        }

        @DisplayName("[FR-ADMIN-PRODUCT-02 BRAND_NOT_FOUND][INV-10] 삭제된 브랜드에는 생성할 수 없다.")
        @Test
        void throwsBrandNotFound_whenDeleted() {
            BrandModel deleted = fixtures.deletedBrand("삭제됨");

            assertThrowsErrorType(() -> productFacade.createProduct(admin.getId(), deleted.getId(), "상품", 1000L, 1), ErrorType.BRAND_NOT_FOUND);

            assertThat(productFacade.listProductsForAdmin(admin.getId(), PageQuery.of(0, 10)).totalCount()).isZero();
        }

        @DisplayName("[FR-ADMIN-PRODUCT-02 INVALID_PRODUCT_NAME][INV-13]")
        @Test
        void throwsInvalidProductName() {
            assertThrowsErrorType(() -> productFacade.createProduct(admin.getId(), brand.getId(), "", 1000L, 1), ErrorType.INVALID_PRODUCT_NAME);
        }

        @DisplayName("[FR-ADMIN-PRODUCT-02 INVALID_PRODUCT_PRICE][INV-13]")
        @Test
        void throwsInvalidProductPrice() {
            assertThrowsErrorType(() -> productFacade.createProduct(admin.getId(), brand.getId(), "상품", -1L, 1), ErrorType.INVALID_PRODUCT_PRICE);
        }

        @DisplayName("[FR-ADMIN-PRODUCT-02 INVALID_STOCK][INV-03]")
        @Test
        void throwsInvalidStock() {
            assertThrowsErrorType(() -> productFacade.createProduct(admin.getId(), brand.getId(), "상품", 1000L, -1), ErrorType.INVALID_STOCK);
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

            ProductInfo info = productFacade.getProductForAdmin(admin.getId(), deleted.getId());

            assertThat(info.deleted()).isTrue();
            assertThat(info.stock()).isEqualTo(4);
            assertThat(info.likeCount()).isEqualTo(1);
        }

        @DisplayName("[FR-ADMIN-PRODUCT-03 PRODUCT_NOT_FOUND]")
        @Test
        void throwsProductNotFound() {
            assertThrowsErrorType(() -> productFacade.getProductForAdmin(admin.getId(), 999L), ErrorType.PRODUCT_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("FR-ADMIN-PRODUCT-04 상품 수정")
    class UpdateProduct {
        @DisplayName("[FR-ADMIN-PRODUCT-04][INV-11] 이름·가격만 바뀌고 브랜드·재고는 유지. 고객 조회에 즉시 반영.")
        @Test
        void updatesNameAndPrice() {
            ProductModel product = fixtures.product(brand.getId(), "옛 이름", 1000L, 5);

            ProductInfo updated = productFacade.updateProduct(admin.getId(), product.getId(), "새 이름", 2000L);

            assertThat(updated.name()).isEqualTo("새 이름");
            assertThat(updated.price()).isEqualTo(2000L);
            assertThat(updated.brand().id()).isEqualTo(brand.getId());
            assertThat(updated.stock()).isEqualTo(5);
            assertThat(productFacade.getProduct(user.getId(), product.getId()).price()).isEqualTo(2000L);
        }

        @DisplayName("[FR-ADMIN-PRODUCT-04 PRODUCT_NOT_FOUND] 존재하지 않음.")
        @Test
        void throwsProductNotFound_whenMissing() {
            assertThrowsErrorType(() -> productFacade.updateProduct(admin.getId(), 999L, "새 이름", 1L), ErrorType.PRODUCT_NOT_FOUND);
        }

        @DisplayName("[FR-ADMIN-PRODUCT-04 PRODUCT_NOT_FOUND] 삭제된 상품은 수정 대상이 아니다. 삭제됨 유지.")
        @Test
        void throwsProductNotFound_whenDeleted() {
            ProductModel deleted = fixtures.deletedProduct(brand.getId(), "삭제됨", 1000L, 5);

            assertThrowsErrorType(() -> productFacade.updateProduct(admin.getId(), deleted.getId(), "새 이름", 1L), ErrorType.PRODUCT_NOT_FOUND);

            assertThat(fixtures.reloadProduct(deleted.getId()).getName()).isEqualTo("삭제됨");
        }

        @DisplayName("[FR-ADMIN-PRODUCT-04 INVALID_PRODUCT_NAME] 기존 값 유지.")
        @Test
        void throwsInvalidProductName_keepsOld() {
            ProductModel product = fixtures.product(brand.getId(), "옛 이름", 1000L, 5);

            assertThrowsErrorType(() -> productFacade.updateProduct(admin.getId(), product.getId(), "a".repeat(201), 1L), ErrorType.INVALID_PRODUCT_NAME);

            assertThat(fixtures.reloadProduct(product.getId()).getName()).isEqualTo("옛 이름");
        }

        @DisplayName("[FR-ADMIN-PRODUCT-04 INVALID_PRODUCT_PRICE] 기존 값 유지.")
        @Test
        void throwsInvalidProductPrice_keepsOld() {
            ProductModel product = fixtures.product(brand.getId(), "옛 이름", 1000L, 5);

            assertThrowsErrorType(() -> productFacade.updateProduct(admin.getId(), product.getId(), "새 이름", -1L), ErrorType.INVALID_PRODUCT_PRICE);

            ProductModel reloaded = fixtures.reloadProduct(product.getId());
            assertThat(reloaded.getName()).isEqualTo("옛 이름");
            assertThat(reloaded.getPrice()).isEqualTo(1000L);
        }
    }

    @Nested
    @DisplayName("FR-ADMIN-PRODUCT-05 상품 삭제")
    class DeleteProduct {
        @DisplayName("[FR-ADMIN-PRODUCT-05][ST-02] 삭제되면 고객 조회에서 제외되고 좋아요 관계는 유지된다 (ASM-07).")
        @Test
        void deletesProduct_keepsLikes() {
            ProductModel product = fixtures.product(brand.getId(), "상품", 1000L, 5);
            fixtures.like(user.getId(), product.getId());

            productFacade.deleteProduct(admin.getId(), product.getId());

            assertThrowsErrorType(() -> productFacade.getProduct(user.getId(), product.getId()), ErrorType.PRODUCT_NOT_FOUND);
            ProductInfo adminView = productFacade.getProductForAdmin(admin.getId(), product.getId());
            assertThat(adminView.deleted()).isTrue();
            assertThat(adminView.likeCount()).isEqualTo(1);
        }

        @DisplayName("[FR-ADMIN-PRODUCT-05][ASM-22] 재고가 남아 있어도 삭제할 수 있다.")
        @Test
        void deletesProduct_evenWithStock() {
            ProductModel product = fixtures.product(brand.getId(), "상품", 1000L, 99);

            productFacade.deleteProduct(admin.getId(), product.getId());

            assertThat(fixtures.reloadProduct(product.getId()).isDeleted()).isTrue();
        }

        @DisplayName("[FR-ADMIN-PRODUCT-05 PRODUCT_NOT_FOUND] 존재하지 않음.")
        @Test
        void throwsProductNotFound_whenMissing() {
            assertThrowsErrorType(() -> productFacade.deleteProduct(admin.getId(), 999L), ErrorType.PRODUCT_NOT_FOUND);
        }

        @DisplayName("[FR-ADMIN-PRODUCT-05 PRODUCT_NOT_FOUND] 이미 삭제된 상품의 재삭제는 거절 (ASM-18).")
        @Test
        void throwsProductNotFound_whenAlreadyDeleted() {
            ProductModel deleted = fixtures.deletedProduct(brand.getId(), "삭제됨", 1000L, 5);

            assertThrowsErrorType(() -> productFacade.deleteProduct(admin.getId(), deleted.getId()), ErrorType.PRODUCT_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("FR-ADMIN-PRODUCT-06 상품 재고 변경")
    class UpdateStock {
        @DisplayName("[FR-ADMIN-PRODUCT-06][INV-03] 재고가 입력값으로 설정된다 (증감 아님).")
        @Test
        void setsStock() {
            ProductModel product = fixtures.product(brand.getId(), "상품", 1000L, 5);

            StockInfo info = productFacade.updateStock(admin.getId(), product.getId(), 42);

            assertThat(info.productId()).isEqualTo(product.getId());
            assertThat(info.stock()).isEqualTo(42);
            assertThat(fixtures.reloadProduct(product.getId()).getStock()).isEqualTo(42);
        }

        @DisplayName("[FR-ADMIN-PRODUCT-06 PRODUCT_NOT_FOUND] 존재하지 않음.")
        @Test
        void throwsProductNotFound_whenMissing() {
            assertThrowsErrorType(() -> productFacade.updateStock(admin.getId(), 999L, 1), ErrorType.PRODUCT_NOT_FOUND);
        }

        @DisplayName("[FR-ADMIN-PRODUCT-06 PRODUCT_NOT_FOUND] 삭제된 상품은 재고 변경 대상이 아니다.")
        @Test
        void throwsProductNotFound_whenDeleted() {
            ProductModel deleted = fixtures.deletedProduct(brand.getId(), "삭제됨", 1000L, 5);

            assertThrowsErrorType(() -> productFacade.updateStock(admin.getId(), deleted.getId(), 1), ErrorType.PRODUCT_NOT_FOUND);

            assertThat(fixtures.reloadProduct(deleted.getId()).getStock()).isEqualTo(5);
        }

        @DisplayName("[FR-ADMIN-PRODUCT-06 INVALID_STOCK] 음수·누락이면 기존 값 유지.")
        @Test
        void throwsInvalidStock_keepsOld() {
            ProductModel product = fixtures.product(brand.getId(), "상품", 1000L, 5);

            assertThrowsErrorType(() -> productFacade.updateStock(admin.getId(), product.getId(), -1), ErrorType.INVALID_STOCK);
            assertThrowsErrorType(() -> productFacade.updateStock(admin.getId(), product.getId(), null), ErrorType.INVALID_STOCK);

            assertThat(fixtures.reloadProduct(product.getId()).getStock()).isEqualTo(5);
        }
    }
}
