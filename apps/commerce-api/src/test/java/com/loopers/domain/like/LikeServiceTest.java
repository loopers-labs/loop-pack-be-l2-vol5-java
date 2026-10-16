package com.loopers.domain.like;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.FakeBrandRepository;
import com.loopers.domain.product.FakeProductRepository;
import com.loopers.domain.product.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class LikeServiceTest {

    private static final Long USER_A = 1L;
    private static final Long USER_B = 2L;

    private FakeLikeRepository likeRepository;
    private FakeProductRepository productRepository;
    private LikeService likeService;
    private Brand brand;

    @BeforeEach
    void setUp() {
        FakeBrandRepository brandRepository = new FakeBrandRepository();
        productRepository = new FakeProductRepository();
        likeRepository = new FakeLikeRepository();
        likeService = new LikeService(likeRepository);
        brand = brandRepository.save(new Brand("브랜드", null));
    }

    private Product saveProduct() {
        return productRepository.save(new Product(brand, "상품", 1_000L));
    }

    @DisplayName("좋아요를 등록할 때, ")
    @Nested
    class Like_ {
        @DisplayName("관계가 없으면, 관계를 만들고 만들었다고 알린다.")
        @Test
        void createsRelation() {
            // arrange
            Product product = saveProduct();

            // act
            boolean created = likeService.like(USER_A, product.getId());

            // assert
            assertAll(
                () -> assertThat(created).isTrue(),
                () -> assertThat(likeRepository.find(USER_A, product.getId())).isPresent()
            );
        }

        @DisplayName("이미 관계가 있으면, 그대로 두고 만들지 않았다고 알린다. (LIK-02)")
        @Test
        void keepsSingleRelation_whenLikedTwice() {
            // arrange
            Product product = saveProduct();
            likeService.like(USER_A, product.getId());

            // act
            boolean created = likeService.like(USER_A, product.getId());

            // assert
            assertAll(
                () -> assertThat(created).isFalse(),
                () -> assertThat(likeRepository.find(USER_A, product.getId())).isPresent()
            );
        }
    }

    @DisplayName("좋아요를 취소할 때, ")
    @Nested
    class Unlike {
        @DisplayName("자신의 관계를 지우고 지웠다고 알린다.")
        @Test
        void removesOwnRelation() {
            // arrange
            Product product = saveProduct();
            likeService.like(USER_A, product.getId());

            // act
            boolean deleted = likeService.unlike(USER_A, product.getId());

            // assert
            assertAll(
                () -> assertThat(deleted).isTrue(),
                () -> assertThat(likeRepository.find(USER_A, product.getId())).isEmpty()
            );
        }

        @DisplayName("다른 사용자가 취소해도 성공하지만, 내 관계는 남는다. (LIK-03)")
        @Test
        void keepsOthersRelation() {
            // arrange
            Product product = saveProduct();
            likeService.like(USER_A, product.getId());

            // act
            boolean deleted = likeService.unlike(USER_B, product.getId());

            // assert
            assertAll(
                () -> assertThat(deleted).isFalse(),
                () -> assertThat(likeRepository.find(USER_A, product.getId())).isPresent()
            );
        }

        @DisplayName("관계가 없으면, 아무것도 바꾸지 않고 지우지 않았다고 알린다.")
        @Test
        void succeeds_whenRelationDoesNotExist() {
            Product product = saveProduct();

            boolean deleted = likeService.unlike(USER_A, product.getId());

            assertThat(deleted).isFalse();
        }

        @DisplayName("상품이 삭제되었어도, 남아 있는 자신의 관계를 지운다. (LIK-03)")
        @Test
        void removesRelation_evenWhenProductIsDeleted() {
            // arrange
            Product product = saveProduct();
            likeService.like(USER_A, product.getId());
            product.delete();

            // act
            likeService.unlike(USER_A, product.getId());

            // assert
            assertThat(likeRepository.find(USER_A, product.getId())).isEmpty();
        }
    }
}
