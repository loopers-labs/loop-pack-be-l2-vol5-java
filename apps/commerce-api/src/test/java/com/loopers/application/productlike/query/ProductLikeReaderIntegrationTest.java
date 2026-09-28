package com.loopers.application.productlike.query;

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
class ProductLikeReaderIntegrationTest {

    @Autowired
    private ProductLikeReader productLikeReader;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private BrandModel brand;
    private ProductModel product;

    @BeforeEach
    void setUp() {
        user = fixtures.user();
        brand = fixtures.brand("브랜드");
        product = fixtures.product(brand.getId(), "상품", 1000L, 1);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Nested
    @DisplayName("FR-LIKE-03 내 좋아요 목록")
    class ListMyLikes {
        @DisplayName("[FR-LIKE-03][ASM-07] 삭제되지 않은 상품만, 등록 최신순, 상품 정보 포함.")
        @Test
        void listsActiveLikedProducts_latestFirst() {
            ProductModel second = fixtures.product(brand.getId(), "둘째", 2000L, 1);
            ProductModel deleted = fixtures.deletedProduct(brand.getId(), "삭제됨", 3000L, 1);
            fixtures.like(user.getId(), product.getId());
            fixtures.like(user.getId(), second.getId());
            fixtures.like(user.getId(), deleted.getId());

            PageResult<ProductLikeView.Item> page = productLikeReader.listMyLikes(user.getId(), user.getId(), PageQuery.of(0, 10));

            assertThat(page.totalCount()).isEqualTo(2);
            assertThat(page.items()).extracting(ProductLikeView.Item::productId)
                .containsExactly(second.getId(), product.getId());
            assertThat(page.items().get(0).brandName()).isEqualTo("브랜드");
            assertThat(page.items().get(0).likedAt()).isNotNull();
        }

        @DisplayName("[FR-LIKE-03] 다른 사용자의 좋아요는 포함되지 않는다.")
        @Test
        void excludesOtherUsers() {
            UserModel other = fixtures.user();
            fixtures.like(other.getId(), product.getId());

            PageResult<ProductLikeView.Item> page = productLikeReader.listMyLikes(user.getId(), user.getId(), PageQuery.of(0, 10));

            assertThat(page.totalCount()).isZero();
        }

        @DisplayName("[FR-LIKE-03 NOT_OWNER][ASM-09] userId 가 요청자와 다르면 거절.")
        @Test
        void throwsNotOwner() {
            UserModel other = fixtures.user();

            assertThrowsErrorType(() -> productLikeReader.listMyLikes(user.getId(), other.getId(), PageQuery.of(0, 10)), ErrorType.NOT_OWNER);
        }

        @DisplayName("[FR-LIKE-03 INVALID_PAGE]")
        @Test
        void throwsInvalidPage() {
            assertThrowsErrorType(() -> productLikeReader.listMyLikes(user.getId(), user.getId(), PageQuery.of(0, -1)), ErrorType.INVALID_PAGE);
        }
    }
}
