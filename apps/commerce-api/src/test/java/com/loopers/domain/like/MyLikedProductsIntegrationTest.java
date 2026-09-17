package com.loopers.domain.like;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductQueryResult;
import com.loopers.domain.user.UserModel;
import com.loopers.fixture.BrandFixture;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("LikeService 는 자신의 좋아요 목록만 상품 정보와 함께 조회한다.")
@SpringBootTest
class MyLikedProductsIntegrationTest {

    @Autowired
    private LikeService likeService;
    @Autowired
    private UserFixture userFixture;
    @Autowired
    private BrandFixture brandFixture;
    @Autowired
    private ProductFixture productFixture;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private PageResult<ProductQueryResult> myLikes(UserModel user, ListSort sort) {
        return likeService.getMyLikedProducts(user.getId(), PageCommand.of(null, null), sort);
    }

    @DisplayName("소유 범위")
    @Nested
    class Ownership {
        @DisplayName("다른 사용자의 좋아요는 목록에 포함하지 않는다.")
        @Test
        void returnsOnlyOwnLikes() {
            UserModel me = userFixture.createUserWithPoint();
            UserModel other = userFixture.createUserWithPoint();
            ProductModel mine = productFixture.createProduct("내 것", 1_000L, 5L);
            ProductModel theirs = productFixture.createProduct("남의 것", 1_000L, 5L);
            likeService.like(me.getId(), mine.getId());
            likeService.like(other.getId(), theirs.getId());

            PageResult<ProductQueryResult> result = myLikes(me, ListSort.LATEST);

            assertAll(
                () -> assertThat(result.totalElements()).isEqualTo(1L),
                () -> assertThat(result.items()).extracting(ProductQueryResult::id).containsExactly(mine.getId())
            );
        }

        @DisplayName("좋아요가 없으면 빈 페이지를 반환한다.")
        @Test
        void returnsEmptyPage() {
            UserModel user = userFixture.createUserWithPoint();

            PageResult<ProductQueryResult> result = myLikes(user, ListSort.LATEST);

            assertAll(
                () -> assertThat(result.items()).isEmpty(),
                () -> assertThat(result.totalElements()).isZero(),
                () -> assertThat(result.totalPages()).isZero()
            );
        }
    }

    @DisplayName("상품 정보")
    @Nested
    class ProductInformation {
        @DisplayName("상품 목록과 같은 브랜드명·좋아요 수·현재 재고 수량을 함께 반환한다.")
        @Test
        void includesBrandAndLikeCount() {
            BrandModel brand = brandFixture.createBrand("나이키");
            ProductModel shirt = productFixture.createProduct(brand.getId(), "티셔츠", 19_900L, 5L);
            UserModel me = userFixture.createUserWithPoint();
            UserModel other = userFixture.createUserWithPoint();
            likeService.like(me.getId(), shirt.getId());
            likeService.like(other.getId(), shirt.getId());

            ProductQueryResult result = myLikes(me, ListSort.LATEST).items().get(0);

            assertAll(
                () -> assertThat(result.brandName()).isEqualTo("나이키"),
                () -> assertThat(result.likeCount()).isEqualTo(2L),
                () -> assertThat(result.stockQuantity()).isEqualTo(5L),
                () -> assertThat(result.price()).isEqualTo(19_900L)
            );
        }

        @DisplayName("삭제된 상품은 내 좋아요 목록에서 제외한다.")
        @Test
        void excludesDeletedProduct() {
            UserModel me = userFixture.createUserWithPoint();
            ProductModel active = productFixture.createProduct("판매 중", 1_000L, 5L);
            ProductModel removed = productFixture.createProduct("단종 예정", 1_000L, 5L);
            likeService.like(me.getId(), active.getId());
            likeService.like(me.getId(), removed.getId());
            productFixture.deleteProduct(removed.getId());

            PageResult<ProductQueryResult> result = myLikes(me, ListSort.LATEST);

            assertAll(
                () -> assertThat(result.totalElements()).isEqualTo(1L),
                () -> assertThat(result.items()).extracting(ProductQueryResult::id).containsExactly(active.getId())
            );
        }

        @DisplayName("좋아요를 취소하면 목록과 상품의 좋아요 수에서 함께 빠진다.")
        @Test
        void reflectsCancel() {
            UserModel me = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 1_000L, 5L);
            likeService.like(me.getId(), shirt.getId());
            assertThat(myLikes(me, ListSort.LATEST).items().get(0).likeCount()).isEqualTo(1L);

            likeService.cancel(me.getId(), shirt.getId());

            assertThat(myLikes(me, ListSort.LATEST).items()).isEmpty();
        }
    }

    @DisplayName("정렬과 페이지")
    @Nested
    class Paging {
        @DisplayName("Like 를 기준으로 latest 는 최근 좋아요부터, oldest 는 그 반대로 반환한다.")
        @Test
        void sortsByLikeCreatedAt() {
            UserModel me = userFixture.createUserWithPoint();
            ProductModel first = productFixture.createProduct("첫번째", 1_000L, 5L);
            ProductModel second = productFixture.createProduct("두번째", 1_000L, 5L);
            ProductModel third = productFixture.createProduct("세번째", 1_000L, 5L);
            likeService.like(me.getId(), first.getId());
            likeService.like(me.getId(), second.getId());
            likeService.like(me.getId(), third.getId());

            assertAll(
                () -> assertThat(myLikes(me, ListSort.LATEST).items()).extracting(ProductQueryResult::id)
                    .containsExactly(third.getId(), second.getId(), first.getId()),
                () -> assertThat(myLikes(me, ListSort.OLDEST).items()).extracting(ProductQueryResult::id)
                    .containsExactly(first.getId(), second.getId(), third.getId())
            );
        }

        @DisplayName("페이지 크기만큼 나누고 전체 좋아요 수를 함께 반환한다.")
        @Test
        void returnsPagedResult() {
            UserModel me = userFixture.createUserWithPoint();
            ProductModel first = productFixture.createProduct("첫번째", 1_000L, 5L);
            ProductModel second = productFixture.createProduct("두번째", 1_000L, 5L);
            productFixture.createProduct("세번째", 1_000L, 5L);
            likeService.like(me.getId(), first.getId());
            likeService.like(me.getId(), second.getId());

            PageResult<ProductQueryResult> result =
                likeService.getMyLikedProducts(me.getId(), PageCommand.of(0, 1), ListSort.OLDEST);

            assertAll(
                () -> assertThat(result.items()).extracting(ProductQueryResult::id).containsExactly(first.getId()),
                () -> assertThat(result.totalElements()).isEqualTo(2L),
                () -> assertThat(result.totalPages()).isEqualTo(2)
            );
        }
    }
}
