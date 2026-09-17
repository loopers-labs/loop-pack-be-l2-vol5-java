package com.loopers.domain.like;

import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.infrastructure.like.LikeJpaRepository;
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

@DisplayName("LikeService 는 사용자–상품 좋아요 관계를 등록·취소한다.")
@SpringBootTest
class LikeServiceIntegrationTest {

    @Autowired
    private LikeService likeService;
    @Autowired
    private UserFixture userFixture;
    @Autowired
    private ProductFixture productFixture;
    @Autowired
    private LikeJpaRepository likeJpaRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("등록")
    @Nested
    class Like {
        @DisplayName("사용자–상품 관계로 저장한다.")
        @Test
        void savesRelation() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);

            LikeModel like = likeService.like(user.getId(), shirt.getId());

            assertAll(
                () -> assertThat(like.getUserId()).isEqualTo(user.getId()),
                () -> assertThat(like.getProductId()).isEqualTo(shirt.getId()),
                () -> assertThat(likeJpaRepository.findAll()).hasSize(1)
            );
        }

        @DisplayName("이미 좋아요한 상품에 다시 등록하면 LIKE_ALREADY_EXISTS 로 거절하고 관계를 늘리지 않는다.")
        @Test
        void rejectsDuplicate() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            likeService.like(user.getId(), shirt.getId());

            assertThatThrownBy(() -> likeService.like(user.getId(), shirt.getId()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.LIKE_ALREADY_EXISTS);
            assertThat(likeJpaRepository.findAll()).hasSize(1);
        }

        @DisplayName("서로 다른 사용자는 같은 상품에 각각 좋아요할 수 있다.")
        @Test
        void allowsDifferentUsers() {
            UserModel first = userFixture.createUserWithPoint();
            UserModel second = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);

            likeService.like(first.getId(), shirt.getId());
            likeService.like(second.getId(), shirt.getId());

            assertThat(likeJpaRepository.findAll()).hasSize(2);
        }

        @DisplayName("존재하지 않는 상품은 PRODUCT_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsUnknownProduct() {
            UserModel user = userFixture.createUserWithPoint();

            assertThatThrownBy(() -> likeService.like(user.getId(), 999_999L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.PRODUCT_NOT_FOUND);
            assertThat(likeJpaRepository.findAll()).isEmpty();
        }

        @DisplayName("삭제된 상품에는 새 좋아요를 등록할 수 없다.")
        @Test
        void rejectsDeletedProduct() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel deleted = productFixture.createDeletedProduct("단종 티셔츠", 2_000L, 5L);

            assertThatThrownBy(() -> likeService.like(user.getId(), deleted.getId()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.PRODUCT_NOT_FOUND);
            assertThat(likeJpaRepository.findAll()).isEmpty();
        }
    }

    @DisplayName("취소")
    @Nested
    class Cancel {
        @DisplayName("자신의 좋아요 관계를 삭제한다.")
        @Test
        void removesRelation() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            likeService.like(user.getId(), shirt.getId());

            likeService.cancel(user.getId(), shirt.getId());

            assertThat(likeJpaRepository.findAll()).isEmpty();
        }

        @DisplayName("취소할 관계가 없으면 LIKE_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsMissingRelation() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);

            assertThatThrownBy(() -> likeService.cancel(user.getId(), shirt.getId()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.LIKE_NOT_FOUND);
        }

        @DisplayName("상품이 삭제되어도 삭제 전에 만든 자신의 좋아요는 취소할 수 있다.")
        @Test
        void allowsCancelOnDeletedProduct() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            likeService.like(user.getId(), shirt.getId());
            productFixture.deleteProduct(shirt.getId());

            likeService.cancel(user.getId(), shirt.getId());

            assertThat(likeJpaRepository.findAll()).isEmpty();
        }

        @DisplayName("다른 사용자의 좋아요는 취소할 수 없고 관계도 유지된다.")
        @Test
        void rejectsCancelingOthersRelation() {
            UserModel owner = userFixture.createUserWithPoint();
            UserModel other = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            likeService.like(owner.getId(), shirt.getId());

            assertThatThrownBy(() -> likeService.cancel(other.getId(), shirt.getId()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.LIKE_NOT_FOUND);
            assertThat(likeJpaRepository.findAll()).hasSize(1);
        }
    }
}
