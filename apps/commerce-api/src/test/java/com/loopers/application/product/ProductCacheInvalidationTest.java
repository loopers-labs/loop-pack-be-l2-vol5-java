package com.loopers.application.product;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.like.LikeFacade;
import com.loopers.application.order.OrderCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderInfo;
import com.loopers.config.cache.ProductCacheRedisConfig;
import com.loopers.domain.point.Point;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import com.loopers.utils.SqlCounter;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 쓰기 경로가 캐시된 목록에 반영되는지 실제 MySQL + Redis 로 확인한다.
 * 각 장면: 목록 조회로 캐시를 채움 → 기존 Facade/Service 로 변경 → 바로 다시 조회 → 새 결과.
 * seed 의 브랜드 7 첫 페이지(ProductQueryFixture)를 기준으로 하며, 첫 상품은 5406(좋아요 14, 사용자 1~14 가 누름)이다.
 */
@SpringBootTest
@Sql("/sql/product-perf-seed.sql")
class ProductCacheInvalidationTest {

    private static final Long FIRST_PRODUCT_ID = 5406L;
    private static final String CACHE_KEY = ProductQueryFixture.CACHE_KEY;

    private final ProductFacade productFacade;
    private final BrandFacade brandFacade;
    private final LikeFacade likeFacade;
    private final OrderFacade orderFacade;
    private final PointJpaRepository pointJpaRepository;
    private final RedisTemplate<String, String> cacheRedisTemplate;
    private final EntityManagerFactory entityManagerFactory;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    private SqlCounter sqlCounter;

    @Autowired
    public ProductCacheInvalidationTest(
        ProductFacade productFacade,
        BrandFacade brandFacade,
        LikeFacade likeFacade,
        OrderFacade orderFacade,
        PointJpaRepository pointJpaRepository,
        @Qualifier(ProductCacheRedisConfig.PRODUCT_CACHE_REDIS_TEMPLATE) RedisTemplate<String, String> cacheRedisTemplate,
        EntityManagerFactory entityManagerFactory,
        DatabaseCleanUp databaseCleanUp,
        RedisCleanUp redisCleanUp
    ) {
        this.productFacade = productFacade;
        this.brandFacade = brandFacade;
        this.likeFacade = likeFacade;
        this.orderFacade = orderFacade;
        this.pointJpaRepository = pointJpaRepository;
        this.cacheRedisTemplate = cacheRedisTemplate;
        this.entityManagerFactory = entityManagerFactory;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
    }

    @BeforeEach
    void setUp() {
        sqlCounter = new SqlCounter(entityManagerFactory);
    }

    @AfterEach
    void tearDown() {
        sqlCounter.disable();
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private List<ProductInfo> queryFixturePage() {
        return productFacade.getProducts(
            ProductQueryFixture.BRAND_ID, ProductQueryFixture.SORT, ProductQueryFixture.PAGE, ProductQueryFixture.SIZE);
    }

    private void fillCache() {
        queryFixturePage();
        assertThat(cacheRedisTemplate.hasKey(CACHE_KEY)).isTrue();
    }

    /**
     * 첫 상품(5406)이 빠졌을 때의 기대 목록: 기존 2~20번째 + 21번째.
     */
    private List<ProductInfo> expectedWithoutFirst() {
        List<ProductInfo> expected = new ArrayList<>(ProductQueryFixture.EXPECTED_PAGE_0.subList(1, 20));
        expected.add(ProductQueryFixture.EXPECTED_21ST);
        return expected;
    }

    @DisplayName("캐시를 무효화하는 쓰기 경로 — ")
    @Nested
    class EvictingPaths {
        @DisplayName("상품 생성(가격순 맨 앞 가격) 후 바로 조회하면, 새 상품이 첫 번째로 나오고 캐시 키는 지워져 있다.")
        @Test
        void createProduct() {
            // arrange
            fillCache();

            // act
            ProductInfo created = productFacade.createProduct(7L, "신상품", 1L);
            boolean keyExistsAfterWrite = cacheRedisTemplate.hasKey(CACHE_KEY);
            List<ProductInfo> result = queryFixturePage();

            // assert
            assertThat(keyExistsAfterWrite).isFalse();
            assertThat(result).hasSize(20);
            assertThat(result.get(0)).isEqualTo(new ProductInfo(created.productId(), "신상품", 1L, 0, 7L, "brand-7", 0L));
            assertThat(result.subList(1, 20)).containsExactlyElementsOf(ProductQueryFixture.EXPECTED_PAGE_0.subList(0, 19));
        }

        @DisplayName("상품 가격·이름 수정 후 바로 조회하면, 비싸진 상품은 첫 페이지에서 빠지고 캐시 키는 지워져 있다.")
        @Test
        void updateProduct() {
            // arrange
            fillCache();

            // act
            productFacade.updateProduct(FIRST_PRODUCT_ID, "비싸진 상품", 99_999L);
            boolean keyExistsAfterWrite = cacheRedisTemplate.hasKey(CACHE_KEY);
            List<ProductInfo> result = queryFixturePage();

            // assert
            assertThat(keyExistsAfterWrite).isFalse();
            assertThat(result).containsExactlyElementsOf(expectedWithoutFirst());
        }

        @DisplayName("상품 삭제 후 바로 조회하면, 삭제 상품이 빠지고 캐시 키는 지워져 있다. 삭제는 SQL 2개(상품 조회, UPDATE)로 끝난다.")
        @Test
        void deleteProduct() {
            // arrange
            fillCache();
            sqlCounter.reset();

            // act
            productFacade.deleteProduct(FIRST_PRODUCT_ID);
            long deleteStatements = sqlCounter.count();
            boolean keyExistsAfterWrite = cacheRedisTemplate.hasKey(CACHE_KEY);
            List<ProductInfo> result = queryFixturePage();

            // assert
            assertThat(keyExistsAfterWrite).isFalse();
            assertThat(result).containsExactlyElementsOf(expectedWithoutFirst());
            assertThat(deleteStatements).isEqualTo(2);
        }

        @DisplayName("브랜드 이름 수정 후 바로 조회하면, 모든 상품의 브랜드명이 새 이름이고 캐시 키는 지워져 있다.")
        @Test
        void updateBrand() {
            // arrange
            fillCache();

            // act
            brandFacade.updateBrand(7L, "새 브랜드명");
            boolean keyExistsAfterWrite = cacheRedisTemplate.hasKey(CACHE_KEY);
            List<ProductInfo> result = queryFixturePage();

            // assert
            assertThat(keyExistsAfterWrite).isFalse();
            assertThat(result).hasSize(20);
            assertThat(result).extracting(ProductInfo::brandName).containsOnly("새 브랜드명");
            assertThat(result).extracting(ProductInfo::productId)
                .containsExactlyElementsOf(ProductQueryFixture.EXPECTED_PAGE_0.stream().map(ProductInfo::productId).toList());
        }

        @DisplayName("브랜드 일괄 삭제 후 바로 조회하면, 빈 목록이 나오고 캐시 키는 지워져 있다.")
        @Test
        void deleteBrand() {
            // arrange
            fillCache();

            // act
            brandFacade.deleteBrand(7L);
            boolean keyExistsAfterWrite = cacheRedisTemplate.hasKey(CACHE_KEY);
            List<ProductInfo> result = queryFixturePage();

            // assert
            assertThat(keyExistsAfterWrite).isFalse();
            assertThat(result).isEmpty();
        }

        @DisplayName("좋아요 등록 후 바로 조회하면, 그 상품의 좋아요 수가 1 늘고 캐시 키는 지워져 있다.")
        @Test
        void like() {
            // arrange
            fillCache();

            // act
            boolean created = likeFacade.like(15L, FIRST_PRODUCT_ID);
            boolean keyExistsAfterWrite = cacheRedisTemplate.hasKey(CACHE_KEY);
            List<ProductInfo> result = queryFixturePage();

            // assert
            assertThat(created).isTrue();
            assertThat(keyExistsAfterWrite).isFalse();
            assertThat(result.get(0).productId()).isEqualTo(FIRST_PRODUCT_ID);
            assertThat(result.get(0).likeCount()).isEqualTo(15L);
        }

        @DisplayName("좋아요 취소 후 바로 조회하면, 그 상품의 좋아요 수가 1 줄고 캐시 키는 지워져 있다.")
        @Test
        void unlike() {
            // arrange
            fillCache();

            // act
            likeFacade.unlike(1L, FIRST_PRODUCT_ID);
            boolean keyExistsAfterWrite = cacheRedisTemplate.hasKey(CACHE_KEY);
            List<ProductInfo> result = queryFixturePage();

            // assert
            assertThat(keyExistsAfterWrite).isFalse();
            assertThat(result.get(0).productId()).isEqualTo(FIRST_PRODUCT_ID);
            assertThat(result.get(0).likeCount()).isEqualTo(13L);
        }
    }

    @DisplayName("캐시를 무효화하지 않는 쓰기 경로 — ")
    @Nested
    class NonEvictingPaths {
        @DisplayName("재고 설정 후 바로 조회하면, 캐시 키는 남아 있고 조회 결과의 재고는 새 값이다.")
        @Test
        void changeStock() {
            // arrange
            fillCache();

            // act
            productFacade.changeStock(FIRST_PRODUCT_ID, 7);
            boolean keyExistsAfterWrite = cacheRedisTemplate.hasKey(CACHE_KEY);
            List<ProductInfo> result = queryFixturePage();

            // assert
            assertThat(keyExistsAfterWrite).isTrue();
            assertThat(result.get(0).productId()).isEqualTo(FIRST_PRODUCT_ID);
            assertThat(result.get(0).stockQuantity()).isEqualTo(7);
            assertThat(result.subList(1, 20)).containsExactlyElementsOf(ProductQueryFixture.EXPECTED_PAGE_0.subList(1, 20));
        }

        @DisplayName("주문 확정(재고 차감) 후 바로 조회하면, 캐시 키는 남아 있고 조회 결과의 재고는 차감된 값이다.")
        @Test
        void confirmOrder() {
            // arrange
            Long userId = 1L;
            Point point = new Point(userId);
            point.charge(10_000L);
            pointJpaRepository.save(point);
            Long orderId = orderFacade.createOrder(userId, List.of(new OrderCommand.Item(FIRST_PRODUCT_ID, 2))).orderId();
            fillCache();

            // act
            orderFacade.confirmOrder(userId, orderId);
            boolean keyExistsAfterWrite = cacheRedisTemplate.hasKey(CACHE_KEY);
            List<ProductInfo> result = queryFixturePage();

            // assert
            assertThat(keyExistsAfterWrite).isTrue();
            assertThat(result.get(0).productId()).isEqualTo(FIRST_PRODUCT_ID);
            assertThat(result.get(0).stockQuantity()).isEqualTo(98);
        }
    }

    @DisplayName("과거 주문 유지 — ")
    @Nested
    class PastOrdersPreserved {
        private static final String OTHER_BRAND_CACHE_KEY = "products:v1:brand=8:sort=price_asc:page=0:size=20";

        private OrderInfo confirmOrderOfFirstProduct(Long userId) {
            Point point = new Point(userId);
            point.charge(10_000L);
            pointJpaRepository.save(point);
            Long orderId = orderFacade.createOrder(userId, List.of(new OrderCommand.Item(FIRST_PRODUCT_ID, 2))).orderId();
            return orderFacade.confirmOrder(userId, orderId);
        }

        @DisplayName("주문 확정 뒤 그 상품의 가격을 수정해도, 기존 주문의 단가·결제액은 변경 전 가격 그대로다.")
        @Test
        void keepsOrderAmounts_afterPriceChange() {
            // arrange
            Long userId = 1L;
            OrderInfo confirmed = confirmOrderOfFirstProduct(userId);
            fillCache();

            // act
            productFacade.updateProduct(FIRST_PRODUCT_ID, "비싸진 상품", 99_999L);
            OrderInfo reloaded = orderFacade.getOrder(userId, confirmed.orderId());
            List<ProductInfo> result = queryFixturePage();

            // assert
            assertThat(reloaded.items().get(0).unitPrice()).isEqualTo(122L);
            assertThat(reloaded.paidAmount()).isEqualTo(244L);
            assertThat(result).containsExactlyElementsOf(expectedWithoutFirst());
        }

        @DisplayName("주문 확정 뒤 그 상품의 브랜드를 일괄 삭제해도, 기존 주문은 조회되고 금액이 그대로이며 다른 브랜드의 목록·캐시 키는 유지된다.")
        @Test
        void keepsOrderAndOtherBrandCache_afterBrandDeletion() {
            // arrange
            Long userId = 1L;
            OrderInfo confirmed = confirmOrderOfFirstProduct(userId);
            fillCache();
            List<ProductInfo> otherBrandBefore = productFacade.getProducts(8L, "price_asc", 0, 20);
            assertThat(cacheRedisTemplate.hasKey(OTHER_BRAND_CACHE_KEY)).isTrue();

            // act
            brandFacade.deleteBrand(7L);
            boolean otherBrandKeyExists = cacheRedisTemplate.hasKey(OTHER_BRAND_CACHE_KEY);
            OrderInfo reloaded = orderFacade.getOrder(userId, confirmed.orderId());
            List<ProductInfo> otherBrandAfter = productFacade.getProducts(8L, "price_asc", 0, 20);

            // assert
            assertThat(reloaded.status()).isEqualTo(confirmed.status());
            assertThat(reloaded.items().get(0).productId()).isEqualTo(FIRST_PRODUCT_ID);
            assertThat(reloaded.items().get(0).unitPrice()).isEqualTo(122L);
            assertThat(reloaded.paidAmount()).isEqualTo(244L);
            assertThat(queryFixturePage()).isEmpty();
            assertThat(otherBrandKeyExists).isTrue();
            assertThat(otherBrandAfter).hasSize(20).containsExactlyElementsOf(otherBrandBefore);
        }
    }
}
