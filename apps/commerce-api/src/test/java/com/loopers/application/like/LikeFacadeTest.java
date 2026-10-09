package com.loopers.application.like;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.FakeBrandRepository;
import com.loopers.domain.like.FakeLikeRepository;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.FakeProductRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductErrorCode;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 좋아요 등록의 LIK-01 은 상품과 좋아요 두 도메인의 협력이라 Facade 에서 확인함 (설계 4.4) */
class LikeFacadeTest {

    private static final Long USER_ID = 1L;

    private FakeLikeRepository likeRepository;
    private FakeProductRepository productRepository;
    private LikeFacade likeFacade;
    private Brand brand;

    @BeforeEach
    void setUp() {
        likeRepository = new FakeLikeRepository();
        productRepository = new FakeProductRepository();
        likeFacade = new LikeFacade(new LikeService(likeRepository), new ProductService(productRepository, Clock.systemDefaultZone()));
        brand = new FakeBrandRepository().save(new Brand("브랜드", null));
    }

    @DisplayName("살아 있는 상품에 등록하면, 관계가 생기고 좋아요 수 1 을 돌려준다.")
    @Test
    void likesActiveProduct() {
        Product product = productRepository.save(new Product(brand, "상품", 1_000L));

        LikeInfo result = likeFacade.like(USER_ID, product.getId());

        assertThat(result.likeCount()).isEqualTo(1);
    }

    @DisplayName("삭제된 상품이면, PRODUCT_NOT_FOUND 예외가 발생하고 관계가 만들어지지 않는다. (LIK-01)")
    @Test
    void throwsProductNotFound_whenProductIsDeleted() {
        // arrange
        Product product = productRepository.save(new Product(brand, "상품", 1_000L));
        product.delete();

        // act
        CoreException result = assertThrows(CoreException.class, () -> likeFacade.like(USER_ID, product.getId()));

        // assert
        assertAll(
            () -> assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND),
            () -> assertThat(likeRepository.find(USER_ID, product.getId())).isEmpty()
        );
    }

    @DisplayName("없는 상품이면, PRODUCT_NOT_FOUND 예외가 발생한다. (LIK-01)")
    @Test
    void throwsProductNotFound_whenProductDoesNotExist() {
        CoreException result = assertThrows(CoreException.class, () -> likeFacade.like(USER_ID, 999L));

        assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
    }
}
