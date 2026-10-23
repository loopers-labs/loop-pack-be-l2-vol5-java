package com.loopers.application.product;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.like.LikeFacade;
import com.loopers.application.order.OrderDetailInfo;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderFacade.OrderRequestLine;
import com.loopers.application.order.facade.OrderPaymentFacade;
import com.loopers.application.order.facade.OrderPaymentFacade.ConfirmCommand;
import com.loopers.application.point.PointFacade;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.product.ProductSort;
import com.loopers.support.error.CoreException;
import com.loopers.support.pg.TestPgServer;
import com.loopers.support.redis.ProductCacheConnection;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 목록 캐시에 영향을 주는 쓰기 경로가 무효화를 부르는지 실제 DB · Redis 로 확인함 (4주차 docs/week4/cache-design.md 3.3, 3.4).
 * 무효화 여부는 다음 조회가 SQL 을 실행하는가(miss)로 판단하고, 결과는 DB 만 읽은 결과와 비교한다
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ProductListCacheInvalidationTest {

    private static final Pageable FIRST_PAGE = PageRequest.of(0, ProductFacade.CACHEABLE_SIZE);
    private static final Long BUYER = 1L;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private AdminProductFacade adminProductFacade;

    @Autowired
    private BrandFacade brandFacade;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private OrderPaymentFacade orderPaymentFacade;

    @Autowired
    private LikeFacade likeFacade;

    @Autowired
    private PointFacade pointFacade;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private ProductCacheConnection productCacheConnection;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private Long brandId;
    private Long otherBrandId;
    /** brandId 의 상품. 가격 1,000 · 2,000 · 3,000, 재고 모두 2 */
    private List<Long> productIds;

    private final TestPgServer pg = TestPgServer.shared();

    @BeforeEach
    void setUp() {
        productCacheConnection.connect();
        pg.reset();
        brandId = brandFacade.createBrand("브랜드", "설명").id();
        otherBrandId = brandFacade.createBrand("다른 브랜드", null).id();
        productIds = List.of(createProduct(brandId, 1_000L), createProduct(brandId, 2_000L), createProduct(brandId, 3_000L));
        createProduct(otherBrandId, 5_000L);
        pointFacade.charge(BUYER, 100_000L);
        warmUp();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private Long createProduct(Long brand, long price) {
        Long productId = adminProductFacade.createProduct(brand, "상품 " + price, price).id();
        adminProductFacade.changeStock(productId, 2);
        return productId;
    }

    /** 세 범위(이 브랜드 · 다른 브랜드 · 전체)의 첫 페이지를 캐시에 채움 */
    private void warmUp() {
        for (ProductSort sort : ProductSort.values()) {
            productFacade.getProducts(brandId, sort, FIRST_PAGE);
            productFacade.getProducts(otherBrandId, sort, FIRST_PAGE);
            productFacade.getProducts(null, sort, FIRST_PAGE);
        }
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    /** 이 범위의 첫 페이지를 읽을 때 SQL 을 실행했는가 (= 캐시가 무효화되어 miss 였는가) */
    private boolean missed(Long brand, ProductSort sort) {
        statistics().clear();
        productFacade.getProducts(brand, sort, FIRST_PAGE);
        return statistics().getPrepareStatementCount() > 0;
    }

    /** 재고는 결제 시작 때 확보되므로 품절 여부도 이때 바뀜 */
    private Long confirmOrderOf(Long productId, int quantity) {
        Long orderId = orderFacade.createOrder(BUYER, List.of(new OrderRequestLine(productId, quantity))).id();
        orderPaymentFacade.confirm(BUYER, orderId, new ConfirmCommand("attempt-" + orderId, "TEST", "0000-0000-0000-0000"));
        return orderId;
    }

    private Long paidOrderOf(Long productId, int quantity) {
        Long orderId = confirmOrderOf(productId, quantity);
        String transactionKey = pg.transactionsOf(Payment.providerOrderIdOf(orderId)).get(0).transactionKey();
        pg.complete(transactionKey, "SUCCESS");
        orderPaymentFacade.reconcile(BUYER, orderId);
        return orderId;
    }

    private void assertInvalidatedBrandAndAllOnly() {
        assertAll(
            () -> assertThat(missed(brandId, ProductSort.LATEST)).isTrue(),
            () -> assertThat(missed(brandId, ProductSort.PRICE_ASC)).isTrue(),
            () -> assertThat(missed(brandId, ProductSort.LIKES_DESC)).isTrue(),
            () -> assertThat(missed(null, ProductSort.LATEST)).isTrue(),
            () -> assertThat(missed(otherBrandId, ProductSort.LATEST)).isFalse()
        );
    }

    private void assertNotInvalidated() {
        assertAll(
            () -> assertThat(missed(brandId, ProductSort.LATEST)).isFalse(),
            () -> assertThat(missed(null, ProductSort.LATEST)).isFalse()
        );
    }

    @DisplayName("목록에 영향을 주는 쓰기는, 그 브랜드와 전체 목록만 무효화한다. (3.3)")
    @Nested
    class Invalidates {
        @DisplayName("상품 생성")
        @Test
        void onCreateProduct() {
            adminProductFacade.createProduct(brandId, "새 상품", 500L);
            assertInvalidatedBrandAndAllOnly();
        }

        @DisplayName("상품 수정")
        @Test
        void onUpdateProduct() {
            adminProductFacade.updateProduct(productIds.get(0), "바뀐 이름", 9_000L);
            assertInvalidatedBrandAndAllOnly();
        }

        @DisplayName("상품 삭제")
        @Test
        void onDeleteProduct() {
            adminProductFacade.deleteProduct(productIds.get(0));
            assertInvalidatedBrandAndAllOnly();
        }

        @DisplayName("관리자 재고 변경으로 품절이 될 때")
        @Test
        void onChangeStock_toSoldOut() {
            adminProductFacade.changeStock(productIds.get(0), 0);
            assertInvalidatedBrandAndAllOnly();
        }

        @DisplayName("관리자 재고 변경으로 품절에서 벗어날 때")
        @Test
        void onChangeStock_fromSoldOut() {
            // arrange
            adminProductFacade.changeStock(productIds.get(0), 0);
            warmUp();

            // act
            adminProductFacade.changeStock(productIds.get(0), 3);

            // assert
            assertInvalidatedBrandAndAllOnly();
        }

        @DisplayName("주문 확정으로 품절이 될 때")
        @Test
        void onConfirmOrder_toSoldOut() {
            confirmOrderOf(productIds.get(0), 2);
            assertInvalidatedBrandAndAllOnly();
        }

        @DisplayName("브랜드 수정")
        @Test
        void onUpdateBrand() {
            brandFacade.updateBrand(brandId, "새 이름", null);
            assertInvalidatedBrandAndAllOnly();
        }

        @DisplayName("브랜드 일괄 삭제")
        @Test
        void onDeleteBrand() {
            brandFacade.deleteBrand(brandId);
            assertInvalidatedBrandAndAllOnly();
        }
    }

    @DisplayName("목록의 담긴 값을 바꾸지 않거나 허용 신선도 안의 쓰기는, 무효화하지 않는다. (3.3, 5)")
    @Nested
    class DoesNotInvalidate {
        @DisplayName("재고가 남는 관리자 재고 변경")
        @Test
        void onChangeStock_keepingInStock() {
            adminProductFacade.changeStock(productIds.get(0), 5);
            assertNotInvalidated();
        }

        @DisplayName("재고가 남는 주문 확정")
        @Test
        void onConfirmOrder_keepingInStock() {
            confirmOrderOf(productIds.get(0), 1);
            assertNotInvalidated();
        }

        @DisplayName("좋아요 등록 · 취소 (좋아요 수는 TTL 동안 오래된 값을 허용)")
        @Test
        void onLikeAndUnlike() {
            likeFacade.like(BUYER, productIds.get(0));
            likeFacade.unlike(BUYER, productIds.get(1));
            assertNotInvalidated();
        }

        @DisplayName("rollback 된 주문 확정 (확보로 품절이 될 뻔했지만 다른 품목 재고 부족)")
        @Test
        void onRolledBackConfirm() {
            // arrange
            Long orderId = orderFacade.createOrder(BUYER, List.of(
                new OrderRequestLine(productIds.get(0), 2), new OrderRequestLine(productIds.get(1), 3))).id();
            ConfirmCommand command = new ConfirmCommand("attempt-1", "TEST", "0000-0000-0000-0000");

            // act
            assertThrows(CoreException.class, () -> orderPaymentFacade.confirm(BUYER, orderId, command));

            // assert
            assertNotInvalidated();
        }
    }

    @DisplayName("관리자가 가격을 수정한 뒤 다시 조회하면, 새 가격과 price_asc 순서가 반영되고 이미 확정한 주문의 금액은 그대로다.")
    @Test
    void reflectsPriceAndOrder_afterPriceUpdate_keepingPastOrderAmount() {
        // arrange
        Long pastOrderId = paidOrderOf(productIds.get(0), 1);
        Page<ProductInfo> before = productFacade.getProducts(brandId, ProductSort.PRICE_ASC, FIRST_PAGE);

        // act
        adminProductFacade.updateProduct(productIds.get(0), "가장 비싸진 상품", 10_000L);
        Page<ProductInfo> after = productFacade.getProducts(brandId, ProductSort.PRICE_ASC, FIRST_PAGE);
        OrderDetailInfo pastOrder = orderFacade.getMyOrder(BUYER, pastOrderId);

        // assert
        assertAll(
            () -> assertThat(before.getContent()).extracting(ProductInfo::id).containsExactly(productIds.get(0), productIds.get(1), productIds.get(2)),
            () -> assertThat(after.getContent()).extracting(ProductInfo::id).containsExactly(productIds.get(1), productIds.get(2), productIds.get(0)),
            () -> assertThat(after.getContent().get(2).price()).isEqualTo(10_000L),
            () -> assertThat(pastOrder.paymentAmount()).isEqualTo(1_000L),
            () -> assertThat(pastOrder.items()).extracting(OrderDetailInfo.Item::unitPrice).containsExactly(1_000L)
        );
    }

    @DisplayName("브랜드를 일괄 삭제한 뒤 다시 조회하면, 그 브랜드 목록은 비고 전체 목록에서도 빠진다. 다른 브랜드와 과거 주문은 그대로다.")
    @Test
    void excludesDeletedBrandProducts_afterBrandDelete_keepingPastOrder() {
        // arrange
        Long pastOrderId = paidOrderOf(productIds.get(0), 1);

        // act
        brandFacade.deleteBrand(brandId);
        OrderDetailInfo pastOrder = orderFacade.getMyOrder(BUYER, pastOrderId);

        // assert
        Page<ProductInfo> brandPage = productFacade.getProducts(brandId, ProductSort.LATEST, FIRST_PAGE);
        Page<ProductInfo> allPage = productFacade.getProducts(null, ProductSort.LATEST, FIRST_PAGE);
        assertAll(
            () -> assertThat(brandPage.getContent()).isEmpty(),
            () -> assertThat(brandPage.getTotalElements()).isZero(),
            () -> assertThat(allPage.getContent()).extracting(ProductInfo::brandId).containsOnly(otherBrandId),
            () -> assertThat(allPage.getTotalElements()).isEqualTo(1),
            () -> assertThat(pastOrder.status()).isEqualTo("CONFIRMED"),
            () -> assertThat(pastOrder.paymentAmount()).isEqualTo(1_000L),
            () -> assertThat(pastOrder.items()).extracting(OrderDetailInfo.Item::onSale).containsExactly(false)
        );
    }

    @DisplayName("품절이 된 뒤 다시 조회하면, 품절 여부가 바로 반영된다.")
    @Test
    void reflectsSoldOut_afterOrderConfirm() {
        // act
        confirmOrderOf(productIds.get(0), 2);

        // assert
        Page<ProductInfo> page = productFacade.getProducts(brandId, ProductSort.PRICE_ASC, FIRST_PAGE);
        assertThat(page.getContent().get(0).soldOut()).isTrue();
    }
}
