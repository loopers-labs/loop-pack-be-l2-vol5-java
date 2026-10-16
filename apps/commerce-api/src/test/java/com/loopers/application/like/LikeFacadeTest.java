package com.loopers.application.like;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.FakeBrandRepository;
import com.loopers.domain.like.FakeLikeRepository;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.FakeProductLikeCountRepository;
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

/**
 * 좋아요 등록의 LIK-01 은 상품과 좋아요 두 도메인의 협력이라 Facade 에서 확인함 (설계 4.4).
 * 좋아요 수는 관계가 실제로 생기거나 지워졌을 때만 바뀜 (4주차 설계 3.2). 동시 실행은 LikeConcurrencyTest 에서 확인함
 */
class LikeFacadeTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;

    private FakeLikeRepository likeRepository;
    private ProductService productService;
    private LikeFacade likeFacade;
    private Brand brand;

    @BeforeEach
    void setUp() {
        likeRepository = new FakeLikeRepository();
        productService = new ProductService(new FakeProductRepository(), new FakeProductLikeCountRepository(), Clock.systemDefaultZone());
        likeFacade = new LikeFacade(new LikeService(likeRepository), productService);
        brand = new FakeBrandRepository().save(new Brand("브랜드", null));
    }

    // 모든 상품이 좋아요 수 행을 갖도록 생성 입구로 만든다 (4주차 설계 2.2)
    private Product createProduct() {
        return productService.create(brand, "상품", 1_000L);
    }

    @DisplayName("살아 있는 상품에 등록하면, 관계가 생기고 좋아요 수 1 을 돌려준다.")
    @Test
    void likesActiveProduct() {
        Product product = createProduct();

        LikeInfo result = likeFacade.like(USER_ID, product.getId());

        assertThat(result.likeCount()).isEqualTo(1);
    }

    @DisplayName("같은 사용자가 두 번 등록하면, 좋아요 수는 1 이다. (LIK-02)")
    @Test
    void countsOnce_whenLikedTwice() {
        // arrange
        Product product = createProduct();
        likeFacade.like(USER_ID, product.getId());

        // act
        LikeInfo result = likeFacade.like(USER_ID, product.getId());

        // assert
        assertThat(result.likeCount()).isEqualTo(1);
    }

    @DisplayName("다른 사용자들이 등록하면, 사용자 수만큼 늘어난다.")
    @Test
    void countsEachUser() {
        // arrange
        Product product = createProduct();
        likeFacade.like(USER_ID, product.getId());

        // act
        LikeInfo result = likeFacade.like(OTHER_USER_ID, product.getId());

        // assert
        assertThat(result.likeCount()).isEqualTo(2);
    }

    @DisplayName("자신의 관계를 취소하면, 좋아요 수가 1 줄어든다.")
    @Test
    void decreases_whenUnliked() {
        // arrange
        Product product = createProduct();
        likeFacade.like(USER_ID, product.getId());
        likeFacade.like(OTHER_USER_ID, product.getId());

        // act
        LikeInfo result = likeFacade.unlike(USER_ID, product.getId());

        // assert
        assertThat(result.likeCount()).isEqualTo(1);
    }

    @DisplayName("관계가 없는데 취소하면, 좋아요 수는 그대로다. (LIK-03)")
    @Test
    void keepsCount_whenUnlikedWithoutRelation() {
        // arrange
        Product product = createProduct();
        likeFacade.like(USER_ID, product.getId());

        // act
        LikeInfo result = likeFacade.unlike(OTHER_USER_ID, product.getId());

        // assert
        assertThat(result.likeCount()).isEqualTo(1);
    }

    @DisplayName("상품이 삭제된 뒤에 취소해도, 관계가 지워지고 좋아요 수가 줄어든다. (LIK-03)")
    @Test
    void decreases_whenUnlikedAfterProductDeleted() {
        // arrange
        Product product = createProduct();
        likeFacade.like(USER_ID, product.getId());
        product.delete();

        // act
        LikeInfo result = likeFacade.unlike(USER_ID, product.getId());

        // assert
        assertAll(
            () -> assertThat(result.likeCount()).isZero(),
            () -> assertThat(likeRepository.find(USER_ID, product.getId())).isEmpty()
        );
    }

    @DisplayName("삭제된 상품이면, PRODUCT_NOT_FOUND 예외가 발생하고 관계가 만들어지지 않는다. (LIK-01)")
    @Test
    void throwsProductNotFound_whenProductIsDeleted() {
        // arrange
        Product product = createProduct();
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
