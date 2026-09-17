package com.loopers.application.catalog;

import com.loopers.domain.catalog.BrandModel;
import com.loopers.domain.catalog.ProductLikeRepository;
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

import java.util.List;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProductLikeFacadeIntegrationTest {

    @Autowired
    private ProductLikeFacade productLikeFacade;
    @Autowired
    private ProductFacade productFacade;
    @Autowired
    private ProductLikeRepository productLikeRepository;
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

    private long likeCount(Long productId) {
        return productLikeRepository.countByProductIds(List.of(productId)).getOrDefault(productId, 0L);
    }

    @Nested
    @DisplayName("FR-LIKE-01 좋아요 등록")
    class Like {
        @DisplayName("[FR-LIKE-01][INV-05] 관계가 생성되고 좋아요 수가 관계 개수와 같다.")
        @Test
        void createsRelation() {
            productLikeFacade.like(user.getId(), product.getId());

            assertThat(productLikeRepository.find(user.getId(), product.getId())).isPresent();
            assertThat(productFacade.getProduct(user.getId(), product.getId()).likeCount()).isEqualTo(1);
        }

        @DisplayName("[FR-LIKE-01][INV-04][ASM-06] 같은 쌍을 다시 등록해도 관계는 하나뿐이고 성공한다 (멱등).")
        @Test
        void isIdempotent() {
            productLikeFacade.like(user.getId(), product.getId());
            productLikeFacade.like(user.getId(), product.getId());

            assertThat(likeCount(product.getId())).isEqualTo(1);
        }

        @DisplayName("[FR-LIKE-01 PRODUCT_NOT_FOUND] 존재하지 않는 상품.")
        @Test
        void throwsProductNotFound_whenMissing() {
            assertThrowsErrorType(() -> productLikeFacade.like(user.getId(), 999L), ErrorType.PRODUCT_NOT_FOUND);
        }

        @DisplayName("[FR-LIKE-01 PRODUCT_NOT_FOUND][ASM-07] 삭제된 상품에는 새로 등록할 수 없다.")
        @Test
        void throwsProductNotFound_whenDeleted() {
            ProductModel deleted = fixtures.deletedProduct(brand.getId(), "삭제됨", 1000L, 1);

            assertThrowsErrorType(() -> productLikeFacade.like(user.getId(), deleted.getId()), ErrorType.PRODUCT_NOT_FOUND);

            assertThat(likeCount(deleted.getId())).isZero();
        }

        @DisplayName("[FR-LIKE-01 USER_NOT_FOUND] 요청자가 없으면 거절.")
        @Test
        void throwsUserNotFound() {
            assertThrowsErrorType(() -> productLikeFacade.like(999L, product.getId()), ErrorType.USER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("FR-LIKE-02 좋아요 취소")
    class Unlike {
        @DisplayName("[FR-LIKE-02][INV-05] 관계가 삭제되고 좋아요 수가 줄어든다.")
        @Test
        void deletesRelation() {
            fixtures.like(user.getId(), product.getId());

            productLikeFacade.unlike(user.getId(), product.getId());

            assertThat(productLikeRepository.find(user.getId(), product.getId())).isEmpty();
            assertThat(likeCount(product.getId())).isZero();
        }

        @DisplayName("[FR-LIKE-02][ASM-06] 관계가 없어도 변화 없이 성공한다.")
        @Test
        void succeeds_whenNoRelation() {
            productLikeFacade.unlike(user.getId(), product.getId());

            assertThat(likeCount(product.getId())).isZero();
        }

        @DisplayName("[FR-LIKE-02][ASM-07] 삭제된 상품에 남아 있는 관계는 취소할 수 있다.")
        @Test
        void succeeds_whenProductDeleted() {
            ProductModel deleted = fixtures.deletedProduct(brand.getId(), "삭제됨", 1000L, 1);
            fixtures.like(user.getId(), deleted.getId());

            productLikeFacade.unlike(user.getId(), deleted.getId());

            assertThat(likeCount(deleted.getId())).isZero();
        }

        @DisplayName("[FR-LIKE-02 PRODUCT_NOT_FOUND] 존재하지 않는 상품.")
        @Test
        void throwsProductNotFound() {
            assertThrowsErrorType(() -> productLikeFacade.unlike(user.getId(), 999L), ErrorType.PRODUCT_NOT_FOUND);
        }
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

            PageResult<LikeInfo> page = productLikeFacade.listMyLikes(user.getId(), user.getId(), PageQuery.of(0, 10));

            assertThat(page.totalCount()).isEqualTo(2);
            assertThat(page.items()).extracting(item -> item.product().id())
                .containsExactly(second.getId(), product.getId());
            assertThat(page.items().get(0).product().brand().name()).isEqualTo("브랜드");
            assertThat(page.items().get(0).likedAt()).isNotNull();
        }

        @DisplayName("[FR-LIKE-03] 다른 사용자의 좋아요는 포함되지 않는다.")
        @Test
        void excludesOtherUsers() {
            UserModel other = fixtures.user();
            fixtures.like(other.getId(), product.getId());

            PageResult<LikeInfo> page = productLikeFacade.listMyLikes(user.getId(), user.getId(), PageQuery.of(0, 10));

            assertThat(page.totalCount()).isZero();
        }

        @DisplayName("[FR-LIKE-03 NOT_OWNER][ASM-09] userId 가 요청자와 다르면 거절.")
        @Test
        void throwsNotOwner() {
            UserModel other = fixtures.user();

            assertThrowsErrorType(() -> productLikeFacade.listMyLikes(user.getId(), other.getId(), PageQuery.of(0, 10)), ErrorType.NOT_OWNER);
        }

        @DisplayName("[FR-LIKE-03 INVALID_PAGE]")
        @Test
        void throwsInvalidPage() {
            assertThrowsErrorType(() -> productLikeFacade.listMyLikes(user.getId(), user.getId(), PageQuery.of(0, -1)), ErrorType.INVALID_PAGE);
        }
    }
}
