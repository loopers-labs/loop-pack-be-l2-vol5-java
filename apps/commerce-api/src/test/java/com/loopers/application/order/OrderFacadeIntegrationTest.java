package com.loopers.application.order;

import com.loopers.application.order.query.OrderReader;
import com.loopers.application.product.ProductFacade;
import com.loopers.application.point.PointFacade;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.user.UserModel;
import com.loopers.support.error.ErrorType;
import com.loopers.support.fixture.Fixtures;
import com.loopers.support.paging.PageQuery;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderFacadeIntegrationTest {

    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private OrderReader orderReader;
    @Autowired
    private ProductFacade productFacade;
    @Autowired
    private PointFacade pointFacade;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private UserModel admin;
    private BrandModel brand;
    private ProductModel product;

    @BeforeEach
    void setUp() {
        user = fixtures.userWithBalance(10_000L);
        admin = fixtures.admin();
        brand = fixtures.brand("브랜드");
        product = fixtures.product(brand.getId(), "상품", 1_000L, 5);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private static OrderItemCommand item(Long productId, Integer quantity) {
        return new OrderItemCommand(productId, quantity);
    }

    /** 실패 후 재고·잔액·상태가 전부 그대로인지 (ASM-14). */
    private void assertNothingChanged(Long orderId, int expectedStock, long expectedBalance) {
        assertThat(fixtures.reloadProduct(product.getId()).getStock()).isEqualTo(expectedStock);
        assertThat(fixtures.balanceOf(user.getId())).isEqualTo(expectedBalance);
        OrderModel order = fixtures.reloadOrder(orderId);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(order.getPaidAmount()).isNull();
    }

    @Nested
    @DisplayName("FR-ORDER-01 주문 생성")
    class CreateOrder {
        @DisplayName("[FR-ORDER-01][INV-06][INV-12] DRAFT 주문이 저장되고 단가는 생성 시점 가격 사본. 재고·잔액은 변화 없음.")
        @Test
        void createsDraftOrder() {
            OrderInfo info = orderFacade.createOrder(user.getId(), List.of(item(product.getId(), 2)));

            assertThat(info.status()).isEqualTo("DRAFT");
            assertThat(info.totalAmount()).isEqualTo(2_000L);
            assertThat(info.paidAmount()).isNull();
            assertThat(info.items()).hasSize(1);
            assertThat(info.items().get(0).unitPrice()).isEqualTo(1_000L);
            assertThat(info.items().get(0).lineAmount()).isEqualTo(2_000L);
            assertThat(fixtures.reloadProduct(product.getId()).getStock()).isEqualTo(5);
            assertThat(fixtures.balanceOf(user.getId())).isEqualTo(10_000L);
        }

        @DisplayName("[FR-ORDER-01][INV-08][ASM-11] 같은 상품 품목은 합산되어 하나로 저장된다.")
        @Test
        void mergesDuplicateItems() {
            OrderInfo info = orderFacade.createOrder(user.getId(), List.of(item(product.getId(), 2), item(product.getId(), 3)));

            assertThat(info.items()).hasSize(1);
            assertThat(info.items().get(0).quantity()).isEqualTo(5);
            assertThat(info.totalAmount()).isEqualTo(5_000L);
        }

        @DisplayName("[FR-ORDER-01] 재고 부족 여부는 생성 시 확인하지 않는다 (원문: 확정 시 거절).")
        @Test
        void doesNotCheckStock() {
            OrderInfo info = orderFacade.createOrder(user.getId(), List.of(item(product.getId(), 100)));

            assertThat(info.status()).isEqualTo("DRAFT");
        }

        @DisplayName("[FR-ORDER-01][ASM-10] 이후 가격 수정은 기존 주문 단가에 영향 없다.")
        @Test
        void unitPriceIsSnapshot() {
            OrderInfo info = orderFacade.createOrder(user.getId(), List.of(item(product.getId(), 1)));

            productFacade.updateProduct(admin.getId(), product.getId(), "상품", 9_999L);

            assertThat(orderReader.getMyOrder(user.getId(), info.id()).items().get(0).unitPrice()).isEqualTo(1_000L);
        }

        @DisplayName("[FR-ORDER-01 EMPTY_ORDER_ITEMS] 품목이 없으면(null·빈 목록) 주문이 생성되지 않는다.")
        @Test
        void throwsEmptyOrderItems() {
            assertThrowsErrorType(() -> orderFacade.createOrder(user.getId(), null), ErrorType.EMPTY_ORDER_ITEMS);
            assertThrowsErrorType(() -> orderFacade.createOrder(user.getId(), List.of()), ErrorType.EMPTY_ORDER_ITEMS);

            assertThat(orderReader.listMyOrders(user.getId(), PageQuery.of(0, 10)).totalCount()).isZero();
        }

        @DisplayName("[FR-ORDER-01 PRODUCT_NOT_FOUND] 어느 품목의 상품이 없거나 삭제됐으면 주문이 생성되지 않는다.")
        @Test
        void throwsProductNotFound() {
            ProductModel deleted = fixtures.deletedProduct(brand.getId(), "삭제됨", 100L, 1);

            assertThrowsErrorType(() -> orderFacade.createOrder(user.getId(), List.of(item(product.getId(), 1), item(999L, 1))), ErrorType.PRODUCT_NOT_FOUND);
            assertThrowsErrorType(() -> orderFacade.createOrder(user.getId(), List.of(item(deleted.getId(), 1))), ErrorType.PRODUCT_NOT_FOUND);
            assertThrowsErrorType(() -> orderFacade.createOrder(user.getId(), List.of(item(null, 1))), ErrorType.PRODUCT_NOT_FOUND);

            assertThat(orderReader.listMyOrders(user.getId(), PageQuery.of(0, 10)).totalCount()).isZero();
        }

        @DisplayName("[FR-ORDER-01 INVALID_QUANTITY] 어느 품목의 수량이 0 이하·누락이면 주문이 생성되지 않는다.")
        @Test
        void throwsInvalidQuantity() {
            assertThrowsErrorType(() -> orderFacade.createOrder(user.getId(), List.of(item(product.getId(), 0))), ErrorType.INVALID_QUANTITY);
            assertThrowsErrorType(() -> orderFacade.createOrder(user.getId(), List.of(item(product.getId(), null))), ErrorType.INVALID_QUANTITY);

            assertThat(orderReader.listMyOrders(user.getId(), PageQuery.of(0, 10)).totalCount()).isZero();
        }

        @DisplayName("[FR-ORDER-01 AMOUNT_OUT_OF_RANGE] 합계가 표현 범위를 초과하면 주문이 생성되지 않는다.")
        @Test
        void throwsAmountOutOfRange() {
            ProductModel expensive = fixtures.product(brand.getId(), "최고가", Long.MAX_VALUE, 10);

            assertThrowsErrorType(() -> orderFacade.createOrder(user.getId(), List.of(item(expensive.getId(), 2))), ErrorType.AMOUNT_OUT_OF_RANGE);

            assertThat(orderReader.listMyOrders(user.getId(), PageQuery.of(0, 10)).totalCount()).isZero();
        }

        @DisplayName("[FR-ORDER-01 USER_NOT_FOUND]")
        @Test
        void throwsUserNotFound() {
            assertThrowsErrorType(() -> orderFacade.createOrder(999L, List.of(item(product.getId(), 1))), ErrorType.USER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("FR-ORDER-02 주문 확정")
    class ConfirmOrder {
        @DisplayName("[FR-ORDER-02][INV-01][INV-03][INV-09][ST-03] 재고·잔액이 차감되고 CONFIRMED, 결제액 = 합계, 결제 결과 저장.")
        @Test
        void confirmsOrder() {
            OrderModel order = fixtures.draftOrder(user.getId(), product, 2);

            OrderInfo info = orderFacade.confirmOrder(user.getId(), order.getId());

            assertThat(info.status()).isEqualTo("CONFIRMED");
            assertThat(info.paidAmount()).isEqualTo(2_000L);
            assertThat(info.confirmedAt()).isNotNull();
            assertThat(fixtures.reloadProduct(product.getId()).getStock()).isEqualTo(3);
            assertThat(fixtures.balanceOf(user.getId())).isEqualTo(8_000L);
        }

        @DisplayName("[FR-ORDER-02][ASM-10] 확정 시 가격을 다시 조회하지 않는다. 결제액 = 생성 시 저장된 합계.")
        @Test
        void paysSnapshotTotal_notCurrentPrice() {
            OrderModel order = fixtures.draftOrder(user.getId(), product, 1);
            productFacade.updateProduct(admin.getId(), product.getId(), "상품", 5_000L);

            OrderInfo info = orderFacade.confirmOrder(user.getId(), order.getId());

            assertThat(info.paidAmount()).isEqualTo(1_000L);
            assertThat(fixtures.balanceOf(user.getId())).isEqualTo(9_000L);
        }

        @DisplayName("[FR-ORDER-02] 재고·잔액이 정확히 필요한 만큼이면 확정된다 (경계).")
        @Test
        void confirms_atExactBoundary() {
            UserModel exact = fixtures.userWithBalance(5_000L);
            OrderModel order = fixtures.draftOrder(exact.getId(), product, 5);

            orderFacade.confirmOrder(exact.getId(), order.getId());

            assertThat(fixtures.reloadProduct(product.getId()).getStock()).isZero();
            assertThat(fixtures.balanceOf(exact.getId())).isZero();
        }

        @DisplayName("[FR-ORDER-02 ORDER_NOT_FOUND] 주문이 없으면 거절.")
        @Test
        void throwsOrderNotFound() {
            assertThrowsErrorType(() -> orderFacade.confirmOrder(user.getId(), 999L), ErrorType.ORDER_NOT_FOUND);
        }

        @DisplayName("[FR-ORDER-02 NOT_OWNER] 남의 주문은 거절, 변화 없음.")
        @Test
        void throwsNotOwner() {
            UserModel other = fixtures.userWithBalance(10_000L);
            OrderModel order = fixtures.draftOrder(user.getId(), product, 1);

            assertThrowsErrorType(() -> orderFacade.confirmOrder(other.getId(), order.getId()), ErrorType.NOT_OWNER);

            assertNothingChanged(order.getId(), 5, 10_000L);
        }

        @DisplayName("[FR-ORDER-02 ORDER_NOT_DRAFT][ST-03] 이미 확정된 주문은 거절. 재고·잔액 재차감 없음, CONFIRMED 유지.")
        @Test
        void throwsOrderNotDraft() {
            OrderModel order = fixtures.draftOrder(user.getId(), product, 1);
            orderFacade.confirmOrder(user.getId(), order.getId());

            assertThrowsErrorType(() -> orderFacade.confirmOrder(user.getId(), order.getId()), ErrorType.ORDER_NOT_DRAFT);

            assertThat(fixtures.reloadProduct(product.getId()).getStock()).isEqualTo(4);
            assertThat(fixtures.balanceOf(user.getId())).isEqualTo(9_000L);
            assertThat(fixtures.reloadOrder(order.getId()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        }

        @DisplayName("[FR-ORDER-02 PRODUCT_NOT_FOUND][ASM-22] 품목의 상품이 삭제됐으면 거절, DRAFT 유지.")
        @Test
        void throwsProductNotFound_whenProductDeleted() {
            OrderModel order = fixtures.draftOrder(user.getId(), product, 1);
            productFacade.deleteProduct(admin.getId(), product.getId());

            assertThrowsErrorType(() -> orderFacade.confirmOrder(user.getId(), order.getId()), ErrorType.PRODUCT_NOT_FOUND);

            assertNothingChanged(order.getId(), 5, 10_000L);
        }

        @DisplayName("[FR-ORDER-02 INSUFFICIENT_STOCK][ASM-12][ASM-14] 재고 부족이면 거절, DRAFT 유지, 잔액 변화 없음.")
        @Test
        void throwsInsufficientStock_rollsBack() {
            OrderModel order = fixtures.draftOrder(user.getId(), product, 6);

            assertThrowsErrorType(() -> orderFacade.confirmOrder(user.getId(), order.getId()), ErrorType.INSUFFICIENT_STOCK);

            assertNothingChanged(order.getId(), 5, 10_000L);
        }

        @DisplayName("[FR-ORDER-02 INSUFFICIENT_STOCK][ASM-14] 여러 품목 중 하나만 부족해도 앞 품목의 재고 차감이 롤백된다 (전부 또는 전무).")
        @Test
        void throwsInsufficientStock_rollsBackEarlierDeductions() {
            ProductModel scarce = fixtures.product(brand.getId(), "품절 임박", 100L, 1);
            OrderModel order = fixtures.reloadOrder(orderFacade.createOrder(user.getId(),
                List.of(item(product.getId(), 2), item(scarce.getId(), 2))).id());

            assertThrowsErrorType(() -> orderFacade.confirmOrder(user.getId(), order.getId()), ErrorType.INSUFFICIENT_STOCK);

            assertNothingChanged(order.getId(), 5, 10_000L);
            assertThat(fixtures.reloadProduct(scarce.getId()).getStock()).isEqualTo(1);
        }

        @DisplayName("[FR-ORDER-02 INSUFFICIENT_POINT][ASM-12][ASM-14] 잔액 부족이면 거절, 이미 차감한 재고도 롤백, DRAFT 유지.")
        @Test
        void throwsInsufficientPoint_rollsBackStock() {
            UserModel poor = fixtures.userWithBalance(1_999L);
            OrderModel order = fixtures.draftOrder(poor.getId(), product, 2);

            assertThrowsErrorType(() -> orderFacade.confirmOrder(poor.getId(), order.getId()), ErrorType.INSUFFICIENT_POINT);

            assertThat(fixtures.reloadProduct(product.getId()).getStock()).isEqualTo(5);
            assertThat(fixtures.balanceOf(poor.getId())).isEqualTo(1_999L);
            assertThat(fixtures.reloadOrder(order.getId()).getStatus()).isEqualTo(OrderStatus.DRAFT);
        }

        @DisplayName("[FR-ORDER-02][ASM-12] 실패한 DRAFT 는 조건이 갖춰지면 재시도로 확정된다.")
        @Test
        void retrySucceeds_afterFailure() {
            UserModel poor = fixtures.userWithBalance(500L);
            OrderModel order = fixtures.draftOrder(poor.getId(), product, 1);
            assertThrowsErrorType(() -> orderFacade.confirmOrder(poor.getId(), order.getId()), ErrorType.INSUFFICIENT_POINT);

            pointFacade.charge(poor.getId(), 500L);
            OrderInfo info = orderFacade.confirmOrder(poor.getId(), order.getId());

            assertThat(info.status()).isEqualTo("CONFIRMED");
            assertThat(fixtures.balanceOf(poor.getId())).isZero();
            assertThat(fixtures.reloadProduct(product.getId()).getStock()).isEqualTo(4);
        }
    }
}
