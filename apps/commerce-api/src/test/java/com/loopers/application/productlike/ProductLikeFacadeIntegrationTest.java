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

import java.util.List;

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
            assertThat(productReader.getProduct(user.getId(), product.getId()).likeCount()).isEqualTo(1);
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
}
