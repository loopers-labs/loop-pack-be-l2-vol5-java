package com.loopers.application.product;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.like.LikeFacade;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSort;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 좋아요 등록 · 취소는 목록 캐시를 무효화하지 않고, 정한 허용 신선도(TTL) 안에 반영된다 (4주차 docs/week4/cache-design.md 3.3, 5).
 * 만료를 테스트 안에서 기다릴 수 있도록 TTL 을 1초로 둔다. 운영 값은 60초다
 */
@SpringBootTest(properties = "product-list-cache.ttl-seconds=1")
class ProductListLikeFreshnessTest {

    private static final Pageable FIRST_PAGE = PageRequest.of(0, ProductFacade.CACHEABLE_SIZE);
    private static final long TTL_MILLIS = 1_000;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private AdminProductFacade adminProductFacade;

    @Autowired
    private BrandFacade brandFacade;

    @Autowired
    private LikeFacade likeFacade;

    @Autowired
    private ProductService productService;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private Long brandId;
    private Long popular;
    private Long middle;
    private Long unliked;

    /** 좋아요 2 · 1 · 0 개 상품 */
    @BeforeEach
    void setUp() {
        brandId = brandFacade.createBrand("브랜드", null).id();
        popular = adminProductFacade.createProduct(brandId, "인기 상품", 1_000L).id();
        middle = adminProductFacade.createProduct(brandId, "보통 상품", 1_000L).id();
        unliked = adminProductFacade.createProduct(brandId, "좋아요 없는 상품", 1_000L).id();
        likeFacade.like(1L, popular);
        likeFacade.like(2L, popular);
        likeFacade.like(1L, middle);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private Page<ProductInfo> likesDesc() {
        return productFacade.getProducts(brandId, ProductSort.LIKES_DESC, FIRST_PAGE);
    }

    @DisplayName("좋아요가 바뀌어도 TTL 동안은 이전 좋아요 수와 순서를 돌려주고, 만료 뒤에는 DB 와 같은 수와 순서를 돌려준다.")
    @Test
    void reflectsLikes_afterTtl() throws InterruptedException {
        // arrange
        Page<ProductInfo> before = likesDesc();

        // act
        for (long userId = 1; userId <= 3; userId++) {
            likeFacade.like(userId, unliked);
        }
        likeFacade.unlike(1L, middle);
        Page<ProductInfo> withinTtl = likesDesc();
        Thread.sleep(TTL_MILLIS * 2);
        Page<ProductInfo> afterTtl = likesDesc();
        List<ProductInfo> database = productService.getActiveProductViews(brandId, ProductSort.LIKES_DESC, FIRST_PAGE)
            .map(ProductInfo::from).getContent();

        // assert
        assertAll(
            () -> assertThat(before.getContent()).extracting(ProductInfo::id).containsExactly(popular, middle, unliked),
            () -> assertThat(withinTtl.getContent()).isEqualTo(before.getContent()),
            () -> assertThat(afterTtl.getContent()).extracting(ProductInfo::id).containsExactly(unliked, popular, middle),
            () -> assertThat(afterTtl.getContent()).extracting(ProductInfo::likeCount).containsExactly(3L, 2L, 0L),
            () -> assertThat(afterTtl.getContent()).isEqualTo(database)
        );
    }
}
