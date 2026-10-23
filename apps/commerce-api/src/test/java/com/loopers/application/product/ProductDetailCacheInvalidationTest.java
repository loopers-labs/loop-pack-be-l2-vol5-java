package com.loopers.application.product;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.like.LikeFacade;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderFacade.OrderRequestLine;
import com.loopers.application.order.facade.OrderPaymentFacade;
import com.loopers.application.order.facade.OrderPaymentFacade.ConfirmCommand;
import com.loopers.application.point.PointFacade;
import com.loopers.domain.product.ProductErrorCode;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 상품 상세 캐시의 조회 · 무효화를 실제 DB · Redis 로 확인함 (4주차 docs/week4/detail-cache-design.md 3.2, 3.3, 5).
 * 무효화 여부는 다음 조회가 SQL 을 실행하는가(miss)로 판단한다
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ProductDetailCacheInvalidationTest {

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
    /** 가격 1,000, 재고 2 */
    private Long productId;
    /** 같은 브랜드의 다른 상품. 가격 2,000, 재고 2 */
    private Long otherProductId;

    private final TestPgServer pg = TestPgServer.shared();

    @BeforeEach
    void setUp() {
        productCacheConnection.connect();
        pg.reset();
        brandId = brandFacade.createBrand("브랜드", "설명").id();
        productId = createProduct(1_000L);
        otherProductId = createProduct(2_000L);
        pointFacade.charge(BUYER, 100_000L);
        productFacade.getProduct(productId);
        productFacade.getProduct(otherProductId);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private Long createProduct(long price) {
        Long id = adminProductFacade.createProduct(brandId, "상품 " + price, price).id();
        adminProductFacade.changeStock(id, 2);
        return id;
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    /** 이 상품의 상세를 읽을 때 SQL 을 실행했는가 (= 캐시가 무효화되어 miss 였는가). 404 도 조회로 센다 */
    private boolean missed(Long id) {
        statistics().clear();
        try {
            productFacade.getProduct(id);
        } catch (CoreException e) {
            assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
        }
        return statistics().getPrepareStatementCount() > 0;
    }

    /** 재고는 결제 시작 때 확보되므로 품절 여부도 이때 바뀜 */
    private void confirmOrderOf(Long id, int quantity) {
        Long orderId = orderFacade.createOrder(BUYER, List.of(new OrderRequestLine(id, quantity))).id();
        orderPaymentFacade.confirm(BUYER, orderId, new ConfirmCommand("attempt-" + orderId, "TEST", "0000-0000-0000-0000"));
    }

    private void assertInvalidatedThatProductOnly() {
        assertAll(
            () -> assertThat(missed(productId)).isTrue(),
            () -> assertThat(missed(otherProductId)).isFalse()
        );
    }

    private void assertNotInvalidated() {
        assertAll(
            () -> assertThat(missed(productId)).isFalse(),
            () -> assertThat(missed(otherProductId)).isFalse()
        );
    }

    @DisplayName("상세를 읽을 때, ")
    @Nested
    class Get {
        @DisplayName("두 번째부터는 SQL 없이 같은 결과를 돌려준다.")
        @Test
        void returnsSameInfoWithoutSql() {
            // arrange
            redisCleanUp.truncateAll();
            ProductInfo miss = productFacade.getProduct(productId);

            // act
            statistics().clear();
            ProductInfo hit = productFacade.getProduct(productId);

            // assert
            assertAll(
                () -> assertThat(statistics().getPrepareStatementCount()).isZero(),
                () -> assertThat(hit).isEqualTo(miss),
                () -> assertThat(hit.price()).isEqualTo(1_000L)
            );
        }

        @DisplayName("없는 상품이면 404 이고, 두 번째는 SQL 없이 404 다. (3.3)")
        @Test
        void cachesNotFound() {
            // arrange
            Long missingId = 9_999L;
            CoreException first = assertThrows(CoreException.class, () -> productFacade.getProduct(missingId));

            // act
            statistics().clear();
            CoreException second = assertThrows(CoreException.class, () -> productFacade.getProduct(missingId));

            // assert
            assertAll(
                () -> assertThat(first.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND),
                () -> assertThat(second.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND),
                () -> assertThat(statistics().getPrepareStatementCount()).isZero()
            );
        }
    }

    @DisplayName("상세에 영향을 주는 쓰기는, 그 상품의 상세만 무효화한다. (3.2)")
    @Nested
    class Invalidates {
        @DisplayName("상품 수정 — 다음 조회에 새 가격이 보인다")
        @Test
        void onUpdateProduct() {
            // act
            adminProductFacade.updateProduct(productId, "바뀐 이름", 9_000L);

            // assert
            assertAll(
                () -> assertThat(productFacade.getProduct(productId).price()).isEqualTo(9_000L),
                () -> assertThat(missed(otherProductId)).isFalse()
            );
        }

        @DisplayName("상품 삭제 — 다음 조회는 404 다")
        @Test
        void onDeleteProduct() {
            // act
            adminProductFacade.deleteProduct(productId);

            // assert
            CoreException result = assertThrows(CoreException.class, () -> productFacade.getProduct(productId));
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND),
                () -> assertThat(missed(otherProductId)).isFalse()
            );
        }

        @DisplayName("관리자 재고 변경으로 품절이 될 때 — 다음 조회에 품절이 보인다")
        @Test
        void onChangeStock_toSoldOut() {
            // act
            adminProductFacade.changeStock(productId, 0);

            // assert
            assertAll(
                () -> assertThat(productFacade.getProduct(productId).soldOut()).isTrue(),
                () -> assertThat(missed(otherProductId)).isFalse()
            );
        }

        @DisplayName("관리자 재고 변경으로 품절에서 벗어날 때")
        @Test
        void onChangeStock_fromSoldOut() {
            // arrange
            adminProductFacade.changeStock(productId, 0);
            productFacade.getProduct(productId);

            // act
            adminProductFacade.changeStock(productId, 3);

            // assert
            assertAll(
                () -> assertThat(productFacade.getProduct(productId).soldOut()).isFalse(),
                () -> assertThat(missed(otherProductId)).isFalse()
            );
        }

        @DisplayName("주문 확정으로 품절이 될 때")
        @Test
        void onConfirmOrder_toSoldOut() {
            confirmOrderOf(productId, 2);
            assertInvalidatedThatProductOnly();
        }

        @DisplayName("상품 생성 — 먼저 조회해 \"없음\"이 저장된 ID 로 생기면, 다음 조회는 200 이다 (3.3)")
        @Test
        void onCreateProduct_afterNotFoundWasCached() {
            // arrange: 다음에 생길 ID 를 먼저 조회해 "없음"을 저장한다
            Long nextId = otherProductId + 1;
            assertThrows(CoreException.class, () -> productFacade.getProduct(nextId));

            // act
            Long createdId = adminProductFacade.createProduct(brandId, "새 상품", 500L).id();

            // assert
            assertAll(
                () -> assertThat(createdId).isEqualTo(nextId),
                () -> assertThat(productFacade.getProduct(createdId).price()).isEqualTo(500L)
            );
        }
    }

    @DisplayName("상세에 담긴 값을 바꾸지 않거나 허용 신선도 안의 쓰기는, 무효화하지 않는다. (3.2, 5)")
    @Nested
    class DoesNotInvalidate {
        @DisplayName("재고가 남는 관리자 재고 변경")
        @Test
        void onChangeStock_keepingInStock() {
            adminProductFacade.changeStock(productId, 5);
            assertNotInvalidated();
        }

        @DisplayName("재고가 남는 주문 확정")
        @Test
        void onConfirmOrder_keepingInStock() {
            confirmOrderOf(productId, 1);
            assertNotInvalidated();
        }

        @DisplayName("좋아요 등록 · 취소 — TTL 동안 이전 좋아요 수가 보인다")
        @Test
        void onLikeAndUnlike() {
            // arrange
            long before = productFacade.getProduct(productId).likeCount();

            // act
            likeFacade.like(BUYER, productId);

            // assert
            assertAll(
                () -> assertThat(productFacade.getProduct(productId).likeCount()).isEqualTo(before),
                () -> assertThat(missed(productId)).isFalse()
            );
        }

        @DisplayName("브랜드 수정 — TTL 동안 이전 브랜드명이 보인다")
        @Test
        void onUpdateBrand() {
            // act
            brandFacade.updateBrand(brandId, "새 이름", null);

            // assert
            assertAll(
                () -> assertThat(productFacade.getProduct(productId).brandName()).isEqualTo("브랜드"),
                () -> assertNotInvalidated()
            );
        }

        @DisplayName("브랜드 일괄 삭제 — TTL 동안 지워진 상품이 보인다")
        @Test
        void onDeleteBrand() {
            brandFacade.deleteBrand(brandId);
            assertNotInvalidated();
        }

        @DisplayName("rollback 된 주문 확정 (확보로 품절이 될 뻔했지만 다른 품목 재고 부족)")
        @Test
        void onRolledBackConfirm() {
            // arrange
            Long orderId = orderFacade.createOrder(BUYER, List.of(
                new OrderRequestLine(productId, 2), new OrderRequestLine(otherProductId, 3))).id();
            ConfirmCommand command = new ConfirmCommand("attempt-1", "TEST", "0000-0000-0000-0000");

            // act
            assertThrows(CoreException.class, () -> orderPaymentFacade.confirm(BUYER, orderId, command));

            // assert
            assertNotInvalidated();
        }
    }
}
