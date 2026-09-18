package com.loopers.domain.like;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.brand.FakeBrandRepository;
import com.loopers.domain.product.FakeProductRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductErrorCode;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        likeService = new LikeService(likeRepository, new ProductService(productRepository, new BrandService(brandRepository)));
        brand = brandRepository.save(new Brand("브랜드", null));
    }

    private Product saveProduct() {
        return productRepository.save(new Product(brand, "상품", 1_000L));
    }

    @DisplayName("좋아요를 등록할 때, ")
    @Nested
    class Like_ {
        @DisplayName("관계가 없으면, 관계를 만든다.")
        @Test
        void createsRelation() {
            // arrange
            Product product = saveProduct();

            // act
            likeService.like(USER_A, product.getId());

            // assert
            assertAll(
                () -> assertThat(likeRepository.find(USER_A, product.getId())).isPresent(),
                () -> assertThat(likeService.countLikes(product.getId())).isEqualTo(1)
            );
        }

        @DisplayName("이미 관계가 있으면, 그대로 두고 좋아요 수는 늘지 않는다. (LIK-02)")
        @Test
        void keepsSingleRelation_whenLikedTwice() {
            // arrange
            Product product = saveProduct();
            likeService.like(USER_A, product.getId());

            // act
            likeService.like(USER_A, product.getId());

            // assert
            assertThat(likeService.countLikes(product.getId())).isEqualTo(1);
        }

        @DisplayName("삭제된 상품이면, PRODUCT_NOT_FOUND 예외가 발생하고 관계가 만들어지지 않는다. (LIK-01)")
        @Test
        void throwsProductNotFound_whenProductIsDeleted() {
            // arrange
            Product product = saveProduct();
            product.delete();

            // act
            CoreException result = assertThrows(CoreException.class, () -> likeService.like(USER_A, product.getId()));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND),
                () -> assertThat(likeRepository.find(USER_A, product.getId())).isEmpty()
            );
        }

        @DisplayName("없는 상품이면, PRODUCT_NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsProductNotFound_whenProductDoesNotExist() {
            CoreException result = assertThrows(CoreException.class, () -> likeService.like(USER_A, 999L));

            assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
        }
    }

    @DisplayName("좋아요를 취소할 때, ")
    @Nested
    class Unlike {
        @DisplayName("자신의 관계를 지운다.")
        @Test
        void removesOwnRelation() {
            // arrange
            Product product = saveProduct();
            likeService.like(USER_A, product.getId());

            // act
            likeService.unlike(USER_A, product.getId());

            // assert
            assertThat(likeService.countLikes(product.getId())).isZero();
        }

        @DisplayName("다른 사용자가 취소해도 성공하지만, 내 관계는 남는다. (LIK-03)")
        @Test
        void keepsOthersRelation() {
            // arrange
            Product product = saveProduct();
            likeService.like(USER_A, product.getId());

            // act
            likeService.unlike(USER_B, product.getId());

            // assert
            assertThat(likeRepository.find(USER_A, product.getId())).isPresent();
        }

        @DisplayName("관계가 없으면, 아무것도 바꾸지 않고 성공한다.")
        @Test
        void succeeds_whenRelationDoesNotExist() {
            Product product = saveProduct();

            likeService.unlike(USER_A, product.getId());

            assertThat(likeService.countLikes(product.getId())).isZero();
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
