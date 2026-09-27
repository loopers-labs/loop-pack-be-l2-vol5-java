package com.loopers.application.product.query;

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

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProductReaderIntegrationTest {

    @Autowired
    private ProductReader productReader;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private BrandModel brand;

    @BeforeEach
    void setUp() {
        user = fixtures.user();
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

            PageResult<ProductView.Summary> page = productReader.listProducts(user.getId(), null, PageQuery.of(0, 10));

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

            PageResult<ProductView.Summary> page = productReader.listProducts(user.getId(), "price_asc", PageQuery.of(0, 10));

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

            PageResult<ProductView.Summary> page = productReader.listProducts(user.getId(), "likes_desc", PageQuery.of(0, 10));

            assertThat(page.items()).extracting(ProductView.Summary::id)
                .containsExactly(twoLikes.getId(), oneLike.getId(), noLike.getId());
            assertThat(page.items()).extracting(ProductView.Summary::likeCount).containsExactly(2L, 1L, 0L);
        }

        @DisplayName("[FR-PRODUCT-01] likes_desc 도 페이지 크기·totalCount 가 맞다.")
        @Test
        void likesDesc_pagesCorrectly() {
            for (int i = 0; i < 5; i++) {
                fixtures.product(brand.getId(), "상품" + i, 100L, 1);
            }

            PageResult<ProductView.Summary> page = productReader.listProducts(user.getId(), "likes_desc", PageQuery.of(1, 2));

            assertThat(page.items()).hasSize(2);
            assertThat(page.totalCount()).isEqualTo(5);
        }

        @DisplayName("[FR-PRODUCT-01 INVALID_SORT] 허용 목록 밖 정렬값은 거절.")
        @Test
        void throwsInvalidSort() {
            assertThrowsErrorType(() -> productReader.listProducts(user.getId(), "name_asc", PageQuery.of(0, 10)), ErrorType.INVALID_SORT);
        }

        @DisplayName("[FR-PRODUCT-01 INVALID_PAGE] 페이지 값이 잘못되면 거절.")
        @Test
        void throwsInvalidPage() {
            assertThrowsErrorType(() -> productReader.listProducts(user.getId(), null, PageQuery.of(0, 0)), ErrorType.INVALID_PAGE);
        }

        @DisplayName("[FR-PRODUCT-01 USER_NOT_FOUND] 요청자가 없으면 거절.")
        @Test
        void throwsUserNotFound() {
            assertThrowsErrorType(() -> productReader.listProducts(999L, null, PageQuery.of(0, 10)), ErrorType.USER_NOT_FOUND);
        }
    }
}
