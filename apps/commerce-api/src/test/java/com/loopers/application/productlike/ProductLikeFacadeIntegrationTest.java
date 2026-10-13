package com.loopers.application.productlike;

import com.loopers.application.product.query.ProductReader;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.productlike.ProductLikeRepository;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.support.error.ErrorType;
import com.loopers.support.fixture.Fixtures;
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
class ProductLikeFacadeIntegrationTest {

    @Autowired
    private ProductLikeFacade productLikeFacade;
    @Autowired
    private ProductReader productReader;
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

    /** 저장된 좋아요 수 (TB-08, DR-36). */
    private long likeCount(Long productId) {
        return fixtures.reloadStat(productId).getLikeCount();
    }

    @Nested
    @DisplayName("FR-LIKE-01 좋아요 등록")
    class Like {
        @DisplayName("[FR-LIKE-01][INV-05] 관계가 생성되고 좋아요 수가 1 늘어난다.")
        @Test
        void createsRelation() {
            productLikeFacade.like(user.getId(), product.getId());

            assertThat(productLikeRepository.find(user.getId(), product.getId())).isPresent();
            assertThat(likeCount(product.getId())).isEqualTo(1);
            assertThat(productReader.getProduct(user.getId(), product.getId()).likeCount()).isEqualTo(1);
        }

        @DisplayName("[FR-LIKE-01][INV-04][INV-05][ASM-06] 같은 쌍을 다시 등록해도 관계는 하나뿐이고 좋아요 수도 늘지 않는다 (멱등).")
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

        @DisplayName("[FR-LIKE-02][INV-05][ASM-06] 관계가 없어도 성공하고 좋아요 수는 줄지 않는다.")
        @Test
        void succeeds_whenNoRelation() {
            UserModel other = fixtures.user();
            fixtures.like(other.getId(), product.getId());

            productLikeFacade.unlike(user.getId(), product.getId());

            assertThat(likeCount(product.getId())).isEqualTo(1);
        }

        @DisplayName("[FR-LIKE-02][INV-05][ASM-07] 삭제된 상품에 남아 있는 관계는 취소할 수 있고 좋아요 수도 줄어든다.")
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
    @DisplayName("DR-37 캐시 revision")
    class CacheRevision {
        @DisplayName("[DR-37] 좋아요 수는 캐시 대상이 아니므로 등록·취소는 상품·브랜드·목록 revision 을 올리지 않는다.")
        @Test
        void likeAndUnlike_keepRevisions() {
            fixtures.catalogRevision();
            long listBefore = fixtures.listRevision();

            productLikeFacade.like(user.getId(), product.getId());
            productLikeFacade.unlike(user.getId(), product.getId());

            assertThat(fixtures.reloadProduct(product.getId()).getRevision()).isZero();
            assertThat(fixtures.reloadBrand(brand.getId()).getRevision()).isZero();
            assertThat(fixtures.listRevision()).isEqualTo(listBefore);
        }
    }
}
