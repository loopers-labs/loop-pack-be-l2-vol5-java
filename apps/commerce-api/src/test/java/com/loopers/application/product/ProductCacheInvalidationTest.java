package com.loopers.application.product;

import com.loopers.application.brand.AdminBrandFacade;
import com.loopers.application.like.LikeFacade;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.dto.OrderInfo;
import com.loopers.application.order.dto.OrderItemCommand;
import com.loopers.application.product.cache.ProductListCache;
import com.loopers.application.product.dto.ProductInfo;
import com.loopers.application.product.dto.ProductListItemInfo;
import com.loopers.application.product.dto.ProductListQuery;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.Stock;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.fixture.ExposedProducts;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 쓰기가 commit 된 뒤 상품 목록 캐시를 비우는지 실제 MySQL·Redis 로 확인한다.
 * 각 테스트는 먼저 목록을 조회해 캐시를 채운 뒤 쓰기를 하고, 캐시가 남았는지와 다시 조회한 결과를 본다.
 */
@SpringBootTest
class ProductCacheInvalidationTest {

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private AdminProductFacade adminProductFacade;

    @Autowired
    private AdminBrandFacade adminBrandFacade;

    @Autowired
    private LikeFacade likeFacade;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private ProductListCache productListCache;

    @Autowired
    private ExposedProducts exposedProducts;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private Brand loopers;
    private Brand other;
    private Product shoes;
    private Product shirt;
    private Product socks;
    private Product otherProduct;

    // 루퍼스: 러닝화 30,000 / 티셔츠 20,000 / 양말 10,000, 다른 브랜드: 모자 15,000
    @BeforeEach
    void setUp() {
        loopers = brandRepository.save(Brand.create("루퍼스"));
        other = brandRepository.save(Brand.create("다른 브랜드"));
        shoes = saveProduct(loopers, "러닝화", 30_000);
        shirt = saveProduct(loopers, "티셔츠", 20_000);
        socks = saveProduct(loopers, "양말", 10_000);
        otherProduct = saveProduct(other, "모자", 15_000);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private Product saveProduct(Brand brand, String name, long price) {
        return exposedProducts.save(Product.create(brand.getId(), name, new Money(price), new Stock(10)));
    }

    private Page<ProductListItemInfo> list(Long brandId, String sort) {
        return productFacade.getList(brandId, sort, 0, 20);
    }

    private boolean cached(Long brandId, ProductSort sort) {
        return productListCache.find(new ProductListQuery(brandId, sort, 0, 20)).isPresent();
    }

    // 브랜드 필터 유무·정렬별 목록을 모두 조회해 캐시를 채운다.
    private void warmUp() {
        list(null, "latest");
        list(null, "price_asc");
        list(loopers.getId(), "latest");
        list(loopers.getId(), "price_asc");
        assertThat(cached(null, ProductSort.LATEST)).isTrue();
        assertThat(cached(loopers.getId(), ProductSort.PRICE_ASC)).isTrue();
    }

    private void assertAllEvicted() {
        assertThat(cached(null, ProductSort.LATEST)).isFalse();
        assertThat(cached(null, ProductSort.PRICE_ASC)).isFalse();
        assertThat(cached(loopers.getId(), ProductSort.LATEST)).isFalse();
        assertThat(cached(loopers.getId(), ProductSort.PRICE_ASC)).isFalse();
    }

    private OrderInfo orderOf(Product product) {
        User user = userRepository.save(User.create());
        return orderFacade.create(user.getId(), List.of(new OrderItemCommand(product.getId(), 1)));
    }

    @DisplayName("상품 쓰기")
    @Nested
    class ProductWrite {

        @DisplayName("가격을 수정하면 영역을 비워 새 가격과 가격순 위치가 보이고, 과거 주문 금액은 그대로다.")
        @Test
        void reflectsNewPriceAndOrder_andKeepsPastOrderAmount() {
            // arrange
            OrderInfo pastOrder = orderOf(socks);
            warmUp();

            // act: 가장 싼 양말을 가장 비싸게
            adminProductFacade.update(socks.getId(), "양말", 40_000);

            // assert
            assertAllEvicted();
            Page<ProductListItemInfo> priceAsc = list(loopers.getId(), "price_asc");
            assertThat(priceAsc.getContent()).extracting(ProductListItemInfo::id)
                .containsExactly(shirt.getId(), shoes.getId(), socks.getId());
            assertThat(priceAsc.getContent()).extracting(ProductListItemInfo::price)
                .containsExactly(20_000L, 30_000L, 40_000L);
            OrderInfo reloaded = orderFacade.getMine(pastOrder.userId(), pastOrder.id());
            assertThat(reloaded.totalAmount()).isEqualTo(10_000);
            assertThat(reloaded.items().get(0).unitPrice()).isEqualTo(10_000);
        }

        @DisplayName("이름을 수정하면 새 이름이 보인다.")
        @Test
        void reflectsNewName() {
            warmUp();

            adminProductFacade.update(shirt.getId(), "반팔 티셔츠", 20_000);

            assertAllEvicted();
            assertThat(list(null, "latest").getContent()).extracting(ProductListItemInfo::name)
                .contains("반팔 티셔츠").doesNotContain("티셔츠");
        }

        @DisplayName("상품을 등록하면 목록과 전체 개수에 보인다.")
        @Test
        void reflectsCreatedProduct() {
            warmUp();

            ProductInfo created = adminProductFacade.create(loopers.getId(), "가방", 50_000, 5);

            assertAllEvicted();
            Page<ProductListItemInfo> latest = list(null, "latest");
            assertThat(latest.getContent().get(0).id()).isEqualTo(created.id());
            assertThat(latest.getTotalElements()).isEqualTo(5);
        }

        @DisplayName("상품을 삭제하면 목록에서 빠지고, 다른 브랜드 상품과 과거 주문은 그대로다.")
        @Test
        void excludesDeletedProduct_andKeepsOthers() {
            // arrange
            OrderInfo pastOrder = orderOf(shoes);
            warmUp();

            // act
            adminProductFacade.delete(shoes.getId());

            // assert
            assertAllEvicted();
            Page<ProductListItemInfo> latest = list(null, "latest");
            assertThat(latest.getContent()).extracting(ProductListItemInfo::id)
                .containsExactly(otherProduct.getId(), socks.getId(), shirt.getId());
            assertThat(latest.getTotalElements()).isEqualTo(3);
            OrderInfo reloaded = orderFacade.getMine(pastOrder.userId(), pastOrder.id());
            assertThat(reloaded.items().get(0).productName()).isEqualTo("러닝화");
            assertThat(reloaded.totalAmount()).isEqualTo(30_000);
        }
    }

    @DisplayName("브랜드 쓰기")
    @Nested
    class BrandWrite {

        @DisplayName("브랜드명을 수정하면 그 브랜드 상품의 브랜드명이 바뀌어 보인다.")
        @Test
        void reflectsNewBrandName() {
            warmUp();

            adminBrandFacade.update(loopers.getId(), "루퍼스 스포츠");

            assertAllEvicted();
            assertThat(list(loopers.getId(), "latest").getContent())
                .extracting(info -> info.brand().name())
                .containsOnly("루퍼스 스포츠");
        }

        @DisplayName("브랜드를 삭제하면 연결 상품이 모두 빠지고, 다른 브랜드 상품은 그대로다.")
        @Test
        void excludesProductsOfDeletedBrand_andKeepsOtherBrand() {
            warmUp();
            list(other.getId(), "latest");

            adminBrandFacade.delete(loopers.getId());

            assertAllEvicted();
            assertThat(list(loopers.getId(), "latest").getContent()).isEmpty();
            assertThat(list(null, "latest").getContent()).extracting(ProductListItemInfo::id)
                .containsExactly(otherProduct.getId());
            assertThat(list(other.getId(), "price_asc").getContent()).extracting(ProductListItemInfo::id)
                .containsExactly(otherProduct.getId());
        }
    }

    @DisplayName("rollback")
    @Nested
    class Rollback {

        @DisplayName("쓰기가 rollback 되면 캐시를 비우지 않고, 이전 결과가 그대로 보인다.")
        @Test
        void keepsCache_whenWriteIsRolledBack() {
            // arrange
            warmUp();

            // act: 관리자 수정이 바깥 트랜잭션에 참여한 뒤 바깥이 rollback 된다(이벤트는 발행되었지만 commit 되지 않음)
            transactionTemplate.executeWithoutResult(status -> {
                adminProductFacade.update(socks.getId(), "양말", 40_000);
                status.setRollbackOnly();
            });

            // assert
            assertThat(cached(null, ProductSort.LATEST)).isTrue();
            assertThat(cached(loopers.getId(), ProductSort.PRICE_ASC)).isTrue();
            assertThat(list(loopers.getId(), "price_asc").getContent()).extracting(ProductListItemInfo::price)
                .containsExactly(10_000L, 20_000L, 30_000L);
            assertThat(adminProductFacade.get(socks.getId()).price()).isEqualTo(10_000);
        }
    }

    @DisplayName("목록 캐시를 비우지 않는 쓰기")
    @Nested
    class NonInvalidatingWrite {

        @DisplayName("재고 변경, 좋아요, 주문, 브랜드 등록은 고객 목록 값을 바꾸지 않아 캐시를 그대로 둔다.")
        @Test
        void keepsCache_whenWriteDoesNotChangeListPayload() {
            // arrange
            warmUp();
            User user = userRepository.save(User.create());

            // act
            adminProductFacade.changeStock(shoes.getId(), 3);
            likeFacade.like(user.getId(), shirt.getId());
            orderFacade.create(user.getId(), List.of(new OrderItemCommand(socks.getId(), 1)));
            adminBrandFacade.create("새 브랜드");

            // assert
            assertThat(cached(null, ProductSort.LATEST)).isTrue();
            assertThat(cached(null, ProductSort.PRICE_ASC)).isTrue();
            assertThat(cached(loopers.getId(), ProductSort.LATEST)).isTrue();
            assertThat(cached(loopers.getId(), ProductSort.PRICE_ASC)).isTrue();
            // 좋아요 수는 캐시 밖이라 비우지 않아도 반영된다.
            assertThat(list(null, "latest").getContent()).filteredOn(info -> info.id().equals(shirt.getId()))
                .extracting(ProductListItemInfo::likeCount).containsExactly(1L);
        }
    }
}
