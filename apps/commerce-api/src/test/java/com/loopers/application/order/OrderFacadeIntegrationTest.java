package com.loopers.application.order;

import com.loopers.application.order.PaymentGatewayResult.Outcome;
import com.loopers.application.order.query.OrderReader;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductStockLedgerModel;
import com.loopers.domain.product.StockLedgerType;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.user.UserModel;
import com.loopers.support.FakePaymentGateway;
import com.loopers.support.error.ErrorType;
import com.loopers.support.fixture.Fixtures;
import com.loopers.support.paging.PageQuery;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static com.loopers.support.FakePaymentGateway.failed;
import static com.loopers.support.FakePaymentGateway.pending;
import static com.loopers.support.FakePaymentGateway.success;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
class OrderFacadeIntegrationTest {

    private static final String KEY = "attempt-1";
    private static final String CARD_TYPE = "SAMSUNG";
    private static final String CARD_NO = "1234-5678-9814-1451";

    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private OrderReader orderReader;
    @Autowired
    private ProductFacade productFacade;
    @Autowired
    private FakePaymentGateway fakePg;
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
        fixtures.catalogRevision();
        user = fixtures.userWithBalance(10_000L);
        admin = fixtures.admin();
        brand = fixtures.brand("브랜드");
        product = fixtures.product(brand.getId(), "상품", 1_000L, 5);
    }

    @AfterEach
    void tearDown() {
        fakePg.reset();
        databaseCleanUp.truncateAllTables();
    }

    private static OrderItemCommand item(Long productId, Integer quantity) {
        return new OrderItemCommand(productId, quantity);
    }

    private OrderInfo confirm(Long userId, Long orderId) {
        return orderFacade.confirmOrder(userId, orderId, KEY, CARD_TYPE, CARD_NO);
    }

    private static String txOf(OrderModel order) {
        return FakePaymentGateway.transactionKeyOf(order.getId());
    }

    /** PG 가 PENDING 으로 접수한 결제 대기 주문. 재고 예약·거래 키 기록까지 끝났고, PG 호출 기록은 비운다. */
    private OrderModel awaitingOrder(int quantity) {
        OrderModel order = fixtures.draftOrder(user.getId(), product, quantity);
        fakePg.willRespond(pending(txOf(order)));
        confirm(user.getId(), order.getId());
        fakePg.reset();
        return order;
    }

    /** PG 응답을 받지 못한(UNKNOWN) 결제 대기 주문. 거래 키 없음. */
    private OrderModel unknownOrder(int quantity) {
        OrderModel order = fixtures.draftOrder(user.getId(), product, quantity);
        fakePg.willRespond(PaymentGatewayResult.unknown());
        confirm(user.getId(), order.getId());
        fakePg.reset();
        return order;
    }

    private OrderStatus statusOf(OrderModel order) {
        return fixtures.reloadOrder(order.getId()).getStatus();
    }

    private List<StockLedgerType> ledgerTypes() {
        return fixtures.ledgerOf(product.getId()).stream().map(ProductStockLedgerModel::getType).toList();
    }

    /** 거절 후 DRAFT·재고·원장 그대로, PG 를 부르지 않았는지 (ASM-14). */
    private void assertDraftUntouched(Long orderId, int expectedStock) {
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(expectedStock);
        assertThat(ledgerTypes()).containsExactly(StockLedgerType.ADJUSTMENT);
        OrderModel order = fixtures.reloadOrder(orderId);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(order.getPaidAmount()).isNull();
        assertThat(fakePg.requests()).isEmpty();
    }

    /** FAILED 이고 예약분이 RELEASE 로 한 번 돌아왔는지. */
    private void assertFailedAndReleased(OrderModel order, int quantity) {
        OrderModel reloaded = fixtures.reloadOrder(order.getId());
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(reloaded.getPaidAmount()).isNull();
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(5);
        assertThat(fixtures.ledgerOf(product.getId()))
            .extracting(ProductStockLedgerModel::getType, ProductStockLedgerModel::getQuantity, ProductStockLedgerModel::getOrderId)
            .containsExactly(
                tuple(StockLedgerType.ADJUSTMENT, 5, null),
                tuple(StockLedgerType.RESERVE, -quantity, order.getId()),
                tuple(StockLedgerType.RELEASE, quantity, order.getId()));
    }

    /** AWAITING_PAYMENT 그대로, 예약된 재고도 그대로인지. */
    private void assertStillAwaiting(OrderModel order, int quantity) {
        OrderModel reloaded = fixtures.reloadOrder(order.getId());
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(reloaded.getPaidAmount()).isNull();
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(5 - quantity);
        assertThat(ledgerTypes()).containsExactly(StockLedgerType.ADJUSTMENT, StockLedgerType.RESERVE);
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
            assertThat(fixtures.stockOf(product.getId())).isEqualTo(5);
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
    @DisplayName("FR-ORDER-02 주문 확정 (PG 카드 결제)")
    class ConfirmOrder {
        @DisplayName("[FR-ORDER-02][INV-03][INV-09][ST-03] PG SUCCESS 면 재고가 차감되고 CONFIRMED, 결제액 = 합계, 거래 키 기록. 포인트는 쓰지 않는다.")
        @Test
        void confirmsOrder_whenPgSucceeds() {
            OrderModel order = fixtures.draftOrder(user.getId(), product, 2);

            OrderInfo info = confirm(user.getId(), order.getId());

            assertThat(info.status()).isEqualTo("CONFIRMED");
            assertThat(info.paidAmount()).isEqualTo(2_000L);
            assertThat(info.confirmedAt()).isNotNull();
            assertThat(fixtures.stockOf(product.getId())).isEqualTo(3);
            assertThat(fixtures.reloadOrder(order.getId()).getTransactionKey()).isEqualTo(txOf(order));
            assertThat(fixtures.balanceOf(user.getId())).isEqualTo(10_000L);
            assertThat(fakePg.requests()).containsExactly(
                new FakePaymentGateway.Request(user.getId(), order.getId(), CARD_TYPE, CARD_NO, 2_000L));
        }

        @DisplayName("[FR-ORDER-02][DR-38] 품목마다 주문 ID 를 가진 RESERVE 행(음수 수량)이 쌓이고, 현재 재고가 그만큼 준다.")
        @Test
        void recordsReservePerItem() {
            ProductModel other = fixtures.product(brand.getId(), "다른 상품", 100L, 3);
            OrderInfo order = orderFacade.createOrder(user.getId(), List.of(item(product.getId(), 2), item(other.getId(), 3)));

            confirm(user.getId(), order.id());

            assertThat(fixtures.ledgerOf(product.getId()))
                .extracting(ProductStockLedgerModel::getType, ProductStockLedgerModel::getQuantity, ProductStockLedgerModel::getOrderId)
                .containsExactly(tuple(StockLedgerType.ADJUSTMENT, 5, null), tuple(StockLedgerType.RESERVE, -2, order.id()));
            assertThat(fixtures.ledgerOf(other.getId()))
                .extracting(ProductStockLedgerModel::getType, ProductStockLedgerModel::getQuantity, ProductStockLedgerModel::getOrderId)
                .containsExactly(tuple(StockLedgerType.ADJUSTMENT, 3, null), tuple(StockLedgerType.RESERVE, -3, order.id()));
            assertThat(fixtures.stockOf(product.getId())).isEqualTo(3);
            assertThat(fixtures.stockOf(other.getId())).isZero();
        }

        @DisplayName("[FR-ORDER-02][ASM-10] 확정 시 가격을 다시 조회하지 않는다. PG 요청 금액·결제액 = 생성 시 저장된 합계.")
        @Test
        void paysSnapshotTotal_notCurrentPrice() {
            OrderModel order = fixtures.draftOrder(user.getId(), product, 1);
            productFacade.updateProduct(admin.getId(), product.getId(), "상품", 5_000L);

            OrderInfo info = confirm(user.getId(), order.getId());

            assertThat(info.paidAmount()).isEqualTo(1_000L);
            assertThat(fakePg.requests()).singleElement().extracting(FakePaymentGateway.Request::amount).isEqualTo(1_000L);
        }

        @DisplayName("[FR-ORDER-02] 재고가 정확히 필요한 만큼이면 확정된다 (경계).")
        @Test
        void confirms_atExactBoundary() {
            OrderModel order = fixtures.draftOrder(user.getId(), product, 5);

            confirm(user.getId(), order.getId());

            assertThat(fixtures.stockOf(product.getId())).isZero();
        }

        @DisplayName("[FR-ORDER-02] PG SUCCESS 금액이 합계와 다르면 확정하지 않는다. AWAITING_PAYMENT·예약 유지, 거래 키는 기록.")
        @Test
        void keepsAwaiting_whenPaidAmountDiffers() {
            OrderModel order = fixtures.draftOrder(user.getId(), product, 2);
            fakePg.willRespond(success(txOf(order), 1_999L));

            OrderInfo info = confirm(user.getId(), order.getId());

            assertThat(info.status()).isEqualTo("AWAITING_PAYMENT");
            assertStillAwaiting(order, 2);
            assertThat(fixtures.reloadOrder(order.getId()).getTransactionKey()).isEqualTo(txOf(order));
        }

        @DisplayName("[FR-ORDER-02] PG PENDING 이면 AWAITING_PAYMENT, 재고 예약 유지, 거래 키 기록 (결과는 callback·결제 대기 해소로).")
        @Test
        void keepsAwaiting_whenPgPending() {
            OrderModel order = fixtures.draftOrder(user.getId(), product, 2);
            fakePg.willRespond(pending(txOf(order)));

            OrderInfo info = confirm(user.getId(), order.getId());

            assertThat(info.status()).isEqualTo("AWAITING_PAYMENT");
            assertStillAwaiting(order, 2);
            assertThat(fixtures.reloadOrder(order.getId()).getTransactionKey()).isEqualTo(txOf(order));
        }

        @DisplayName("[FR-ORDER-02] PG 결과를 모르면(UNKNOWN) AWAITING_PAYMENT, 재고 예약 유지, 거래 키 없음. 실패로 보지 않는다.")
        @Test
        void keepsAwaiting_whenPgUnknown() {
            OrderModel order = fixtures.draftOrder(user.getId(), product, 2);
            fakePg.willRespond(PaymentGatewayResult.unknown());

            OrderInfo info = confirm(user.getId(), order.getId());

            assertThat(info.status()).isEqualTo("AWAITING_PAYMENT");
            assertStillAwaiting(order, 2);
            assertThat(fixtures.reloadOrder(order.getId()).getTransactionKey()).isNull();
        }

        @DisplayName("[FR-ORDER-02][ST-03] PG FAILED·요청 미전송(NOT_SENT)이면 FAILED, 예약분은 RELEASE 로 반환된다.")
        @ParameterizedTest
        @EnumSource(value = Outcome.class, names = {"FAILED", "NOT_SENT"})
        void failsAndReleases_whenPgFailsOrNotSent(Outcome outcome) {
            OrderModel order = fixtures.draftOrder(user.getId(), product, 2);
            fakePg.willRespond(new PaymentGatewayResult(outcome, null, null));

            OrderInfo info = confirm(user.getId(), order.getId());

            assertThat(info.status()).isEqualTo("FAILED");
            assertFailedAndReleased(order, 2);
        }

        @DisplayName("[FR-ORDER-02] 같은 결제 시도 키의 재요청은 PG 를 다시 부르지 않고 현재 상태를 돌려준다. 재고는 한 번만 예약.")
        @Test
        void sameAttemptKey_returnsCurrentState_withoutCallingPgAgain() {
            OrderModel order = fixtures.draftOrder(user.getId(), product, 2);
            fakePg.willRespond(pending(txOf(order)));
            confirm(user.getId(), order.getId());

            OrderInfo retried = confirm(user.getId(), order.getId());

            assertThat(retried.status()).isEqualTo("AWAITING_PAYMENT");
            assertThat(fakePg.requests()).hasSize(1);
            assertStillAwaiting(order, 2);
        }

        @DisplayName("[FR-ORDER-02] 확정된 주문에 같은 결제 시도 키로 다시 보내면 CONFIRMED 를 돌려준다. PG 재호출·재차감 없음.")
        @Test
        void sameAttemptKey_afterConfirmed_returnsConfirmed() {
            OrderModel order = fixtures.draftOrder(user.getId(), product, 1);
            confirm(user.getId(), order.getId());

            OrderInfo retried = confirm(user.getId(), order.getId());

            assertThat(retried.status()).isEqualTo("CONFIRMED");
            assertThat(fakePg.requests()).hasSize(1);
            assertThat(fixtures.stockOf(product.getId())).isEqualTo(4);
        }

        @DisplayName("[FR-ORDER-02 ORDER_NOT_DRAFT][ST-03] 다른 결제 시도 키로는 결제 대기·확정 주문을 다시 결제할 수 없다. PG 재호출·재차감 없음.")
        @Test
        void throwsOrderNotDraft_withDifferentAttemptKey() {
            OrderModel awaiting = awaitingOrder(1);
            OrderModel confirmed = fixtures.draftOrder(user.getId(), product, 1);
            confirm(user.getId(), confirmed.getId());
            fakePg.reset();

            assertThrowsErrorType(() -> orderFacade.confirmOrder(user.getId(), awaiting.getId(), "attempt-2", CARD_TYPE, CARD_NO), ErrorType.ORDER_NOT_DRAFT);
            assertThrowsErrorType(() -> orderFacade.confirmOrder(user.getId(), confirmed.getId(), "attempt-2", CARD_TYPE, CARD_NO), ErrorType.ORDER_NOT_DRAFT);

            assertThat(fakePg.requests()).isEmpty();
            assertThat(statusOf(awaiting)).isEqualTo(OrderStatus.AWAITING_PAYMENT);
            assertThat(statusOf(confirmed)).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(fixtures.stockOf(product.getId())).isEqualTo(3);
        }

        @DisplayName("[FR-ORDER-02 INVALID_PAYMENT_ATTEMPT_KEY] 결제 시도 키가 없으면 거절, DRAFT 유지, PG 호출 없음.")
        @Test
        void throwsInvalidPaymentAttemptKey() {
            OrderModel order = fixtures.draftOrder(user.getId(), product, 1);

            assertThrowsErrorType(() -> orderFacade.confirmOrder(user.getId(), order.getId(), null, CARD_TYPE, CARD_NO), ErrorType.INVALID_PAYMENT_ATTEMPT_KEY);
            assertThrowsErrorType(() -> orderFacade.confirmOrder(user.getId(), order.getId(), " ", CARD_TYPE, CARD_NO), ErrorType.INVALID_PAYMENT_ATTEMPT_KEY);

            assertDraftUntouched(order.getId(), 5);
        }

        @DisplayName("[FR-ORDER-02 ORDER_NOT_FOUND] 주문이 없으면 거절, PG 호출 없음.")
        @Test
        void throwsOrderNotFound() {
            assertThrowsErrorType(() -> confirm(user.getId(), 999L), ErrorType.ORDER_NOT_FOUND);

            assertThat(fakePg.requests()).isEmpty();
        }

        @DisplayName("[FR-ORDER-02 NOT_OWNER] 남의 주문은 거절, 변화 없음.")
        @Test
        void throwsNotOwner() {
            UserModel other = fixtures.userWithBalance(10_000L);
            OrderModel order = fixtures.draftOrder(user.getId(), product, 1);

            assertThrowsErrorType(() -> confirm(other.getId(), order.getId()), ErrorType.NOT_OWNER);

            assertDraftUntouched(order.getId(), 5);
        }

        @DisplayName("[FR-ORDER-02 PRODUCT_NOT_FOUND][ASM-22] 품목의 상품이 삭제됐으면 거절, DRAFT 유지.")
        @Test
        void throwsProductNotFound_whenProductDeleted() {
            OrderModel order = fixtures.draftOrder(user.getId(), product, 1);
            productFacade.deleteProduct(admin.getId(), product.getId());

            assertThrowsErrorType(() -> confirm(user.getId(), order.getId()), ErrorType.PRODUCT_NOT_FOUND);

            assertDraftUntouched(order.getId(), 5);
        }

        @DisplayName("[FR-ORDER-02 INSUFFICIENT_STOCK][ASM-12][ASM-14] 재고 부족이면 거절, DRAFT 유지, PG 호출 없음.")
        @Test
        void throwsInsufficientStock_rollsBack() {
            OrderModel order = fixtures.draftOrder(user.getId(), product, 6);

            assertThrowsErrorType(() -> confirm(user.getId(), order.getId()), ErrorType.INSUFFICIENT_STOCK);

            assertDraftUntouched(order.getId(), 5);
        }

        @DisplayName("[FR-ORDER-02 INSUFFICIENT_STOCK][ASM-14] 여러 품목 중 하나만 부족해도 앞 품목의 재고 예약이 롤백된다 (전부 또는 전무).")
        @Test
        void throwsInsufficientStock_rollsBackEarlierReservations() {
            ProductModel scarce = fixtures.product(brand.getId(), "품절 임박", 100L, 1);
            OrderModel order = fixtures.reloadOrder(orderFacade.createOrder(user.getId(),
                List.of(item(product.getId(), 2), item(scarce.getId(), 2))).id());

            assertThrowsErrorType(() -> confirm(user.getId(), order.getId()), ErrorType.INSUFFICIENT_STOCK);

            assertDraftUntouched(order.getId(), 5);
            assertThat(fixtures.stockOf(scarce.getId())).isEqualTo(1);
        }

        @DisplayName("[FR-ORDER-02][ASM-12] 재고 부족으로 거절된 DRAFT 는 같은 결제 시도 키로 재시도해 확정된다 (거절 시 키가 남지 않는다).")
        @Test
        void retrySucceeds_afterInsufficientStock() {
            OrderModel order = fixtures.draftOrder(user.getId(), product, 6);
            assertThrowsErrorType(() -> confirm(user.getId(), order.getId()), ErrorType.INSUFFICIENT_STOCK);

            productFacade.updateStock(admin.getId(), product.getId(), 10);
            OrderInfo info = confirm(user.getId(), order.getId());

            assertThat(info.status()).isEqualTo("CONFIRMED");
            assertThat(fixtures.stockOf(product.getId())).isEqualTo(4);
            assertThat(fakePg.requests()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("FR-ORDER-02 PG callback 결과 반영")
    class PaymentCallback {
        @DisplayName("[FR-ORDER-02][ST-03] PENDING 뒤 callback SUCCESS 면 CONFIRMED, 결제액 = 합계.")
        @Test
        void confirms_onSuccessCallback() {
            OrderModel order = awaitingOrder(2);

            OrderInfo info = orderFacade.applyPaymentCallback(PgOrderId.of(order.getId()), success(txOf(order), 2_000L));

            assertThat(info.status()).isEqualTo("CONFIRMED");
            assertThat(info.paidAmount()).isEqualTo(2_000L);
            assertThat(fixtures.stockOf(product.getId())).isEqualTo(3);
        }

        @DisplayName("[FR-ORDER-02][ST-03] callback FAILED 면 FAILED, 예약분 반환.")
        @Test
        void failsAndReleases_onFailedCallback() {
            OrderModel order = awaitingOrder(2);

            OrderInfo info = orderFacade.applyPaymentCallback(PgOrderId.of(order.getId()), failed(txOf(order)));

            assertThat(info.status()).isEqualTo("FAILED");
            assertFailedAndReleased(order, 2);
        }

        @DisplayName("[FR-ORDER-02] 같은 FAILED callback 이 두 번 와도 결과는 같다. 재고는 한 번만 반환된다.")
        @Test
        void duplicateFailedCallback_releasesOnce() {
            OrderModel order = awaitingOrder(2);

            orderFacade.applyPaymentCallback(PgOrderId.of(order.getId()), failed(txOf(order)));
            OrderInfo second = orderFacade.applyPaymentCallback(PgOrderId.of(order.getId()), failed(txOf(order)));

            assertThat(second.status()).isEqualTo("FAILED");
            assertFailedAndReleased(order, 2);
        }

        @DisplayName("[FR-ORDER-02] 이미 결론 난 주문에 늦게 온 다른 결과는 무시한다. CONFIRMED 유지, 재고 반환 없음.")
        @Test
        void ignoresLateCallback_afterConclusion() {
            OrderModel order = awaitingOrder(2);
            orderFacade.applyPaymentCallback(PgOrderId.of(order.getId()), success(txOf(order), 2_000L));

            OrderInfo info = orderFacade.applyPaymentCallback(PgOrderId.of(order.getId()), failed(txOf(order)));

            assertThat(info.status()).isEqualTo("CONFIRMED");
            assertThat(fixtures.stockOf(product.getId())).isEqualTo(3);
            assertThat(ledgerTypes()).containsExactly(StockLedgerType.ADJUSTMENT, StockLedgerType.RESERVE);
        }

        @DisplayName("[FR-ORDER-02] 저장된 거래 키와 다른 거래의 callback 은 무시한다. AWAITING_PAYMENT 유지.")
        @Test
        void ignoresCallback_withDifferentTransactionKey() {
            OrderModel order = awaitingOrder(2);

            OrderInfo info = orderFacade.applyPaymentCallback(PgOrderId.of(order.getId()), success("tx-other", 2_000L));

            assertThat(info.status()).isEqualTo("AWAITING_PAYMENT");
            assertStillAwaiting(order, 2);
            assertThat(fixtures.reloadOrder(order.getId()).getTransactionKey()).isEqualTo(txOf(order));
        }

        @DisplayName("[FR-ORDER-02] callback SUCCESS 금액이 합계와 다르면 확정하지 않는다. AWAITING_PAYMENT 유지.")
        @Test
        void keepsAwaiting_whenCallbackAmountDiffers() {
            OrderModel order = awaitingOrder(2);

            OrderInfo info = orderFacade.applyPaymentCallback(PgOrderId.of(order.getId()), success(txOf(order), 2_001L));

            assertThat(info.status()).isEqualTo("AWAITING_PAYMENT");
            assertStillAwaiting(order, 2);
        }

        @DisplayName("[FR-ORDER-02] 동기 응답을 못 받은(거래 키 없는) 주문도 callback 으로 확정되고, 그 거래 키가 기록된다.")
        @Test
        void confirmsUnknownOrder_andRecordsTransactionKey() {
            OrderModel order = unknownOrder(2);

            OrderInfo info = orderFacade.applyPaymentCallback(PgOrderId.of(order.getId()), success("tx-late", 2_000L));

            assertThat(info.status()).isEqualTo("CONFIRMED");
            assertThat(fixtures.reloadOrder(order.getId()).getTransactionKey()).isEqualTo("tx-late");
        }

        @DisplayName("[FR-ORDER-02] callback 이 동기 응답보다 먼저 반영되면, 뒤에 온 동기 응답은 무시된다. 재고는 한 번만 반환.")
        @Test
        void callbackBeforeSyncResponse_winsAndSyncIsIgnored() {
            OrderModel order = fixtures.draftOrder(user.getId(), product, 2);
            fakePg.willRespond(request -> {
                orderFacade.applyPaymentCallback(PgOrderId.of(request.orderId()), failed(txOf(order)));
                return success(txOf(order), 2_000L);
            });

            OrderInfo info = confirm(user.getId(), order.getId());

            assertThat(info.status()).isEqualTo("FAILED");
            assertFailedAndReleased(order, 2);
        }

        @DisplayName("[FR-ORDER-02 ORDER_NOT_FOUND] PG 주문 ID 형식이 아니거나 없는 주문이면 거절.")
        @Test
        void throwsOrderNotFound() {
            assertThrowsErrorType(() -> orderFacade.applyPaymentCallback("BAD-1", success("tx-1", 1_000L)), ErrorType.ORDER_NOT_FOUND);
            assertThrowsErrorType(() -> orderFacade.applyPaymentCallback(PgOrderId.of(999_999L), success("tx-1", 1_000L)), ErrorType.ORDER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("FR-ORDER-02 결제 대기 해소")
    class ResolveAwaitingPayment {
        @DisplayName("[FR-ORDER-02] PG 거래 조회가 SUCCESS 면 CONFIRMED. 새 결제 요청은 만들지 않는다.")
        @Test
        void confirms_whenLookupSucceeds() {
            OrderModel order = awaitingOrder(2);
            fakePg.willFind(success(txOf(order), 2_000L));

            OrderInfo info = orderFacade.resolveAwaitingPayment(user.getId(), order.getId());

            assertThat(info.status()).isEqualTo("CONFIRMED");
            assertThat(info.paidAmount()).isEqualTo(2_000L);
            assertThat(fakePg.finds()).containsExactly(order.getId());
            assertThat(fakePg.requests()).isEmpty();
        }

        @DisplayName("[FR-ORDER-02] PG 거래 조회가 FAILED 면 FAILED, 예약분 반환.")
        @Test
        void failsAndReleases_whenLookupFails() {
            OrderModel order = awaitingOrder(2);
            fakePg.willFind(failed(txOf(order)));

            OrderInfo info = orderFacade.resolveAwaitingPayment(user.getId(), order.getId());

            assertThat(info.status()).isEqualTo("FAILED");
            assertFailedAndReleased(order, 2);
        }

        @DisplayName("[FR-ORDER-02] PG 거래 조회가 PENDING 이거나 조회 실패(UNKNOWN)면 AWAITING_PAYMENT 그대로.")
        @Test
        void keepsAwaiting_whenLookupPendingOrUnknown() {
            OrderModel order = awaitingOrder(2);

            fakePg.willFind(pending(txOf(order)));
            assertThat(orderFacade.resolveAwaitingPayment(user.getId(), order.getId()).status()).isEqualTo("AWAITING_PAYMENT");
            fakePg.willFind(PaymentGatewayResult.unknown());
            assertThat(orderFacade.resolveAwaitingPayment(user.getId(), order.getId()).status()).isEqualTo("AWAITING_PAYMENT");

            assertStillAwaiting(order, 2);
            assertThat(fakePg.finds()).hasSize(2);
        }

        @DisplayName("[FR-ORDER-02] 동기 응답을 못 받은(거래 키 없는) 주문도 PG 주문 ID 로 조회해 확정하고 거래 키를 기록한다.")
        @Test
        void confirmsUnknownOrder_andRecordsTransactionKey() {
            OrderModel order = unknownOrder(2);
            fakePg.willFind(success("tx-found", 2_000L));

            OrderInfo info = orderFacade.resolveAwaitingPayment(user.getId(), order.getId());

            assertThat(info.status()).isEqualTo("CONFIRMED");
            assertThat(fixtures.reloadOrder(order.getId()).getTransactionKey()).isEqualTo("tx-found");
        }

        @DisplayName("[FR-ORDER-02] AWAITING_PAYMENT 가 아니면(DRAFT·CONFIRMED·FAILED) PG 를 부르지 않고 현재 상태를 돌려준다.")
        @Test
        void returnsCurrentState_withoutLookup_whenNotAwaiting() {
            OrderModel draft = fixtures.draftOrder(user.getId(), product, 1);
            OrderModel confirmed = fixtures.confirmedOrder(user.getId(), product, 1);
            OrderModel failedOrder = fixtures.draftOrder(user.getId(), product, 1);
            fakePg.willRespond(failed(null));
            confirm(user.getId(), failedOrder.getId());
            fakePg.willFind(success("tx-x", 1_000L));

            assertThat(orderFacade.resolveAwaitingPayment(user.getId(), draft.getId()).status()).isEqualTo("DRAFT");
            assertThat(orderFacade.resolveAwaitingPayment(user.getId(), confirmed.getId()).status()).isEqualTo("CONFIRMED");
            assertThat(orderFacade.resolveAwaitingPayment(user.getId(), failedOrder.getId()).status()).isEqualTo("FAILED");

            assertThat(fakePg.finds()).isEmpty();
        }

        @DisplayName("[FR-ORDER-02 ORDER_NOT_FOUND] / [NOT_OWNER] 없는 주문·남의 주문은 거절, PG 조회 없음.")
        @Test
        void throwsOrderNotFoundOrNotOwner() {
            OrderModel order = awaitingOrder(1);
            UserModel other = fixtures.userWithBalance(0L);

            assertThrowsErrorType(() -> orderFacade.resolveAwaitingPayment(user.getId(), 999_999L), ErrorType.ORDER_NOT_FOUND);
            assertThrowsErrorType(() -> orderFacade.resolveAwaitingPayment(other.getId(), order.getId()), ErrorType.NOT_OWNER);

            assertThat(fakePg.finds()).isEmpty();
            assertThat(statusOf(order)).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        }
    }

    @Nested
    @DisplayName("DR-37 캐시 revision")
    class CacheRevision {
        @DisplayName("[DR-37][DR-22] 주문 생성·확정은 재고만 바꾸므로 상품·브랜드·목록 revision 을 올리지 않는다.")
        @Test
        void createAndConfirm_keepRevisions() {
            long listBefore = fixtures.listRevision();

            OrderInfo order = orderFacade.createOrder(user.getId(), List.of(item(product.getId(), 2)));
            confirm(user.getId(), order.id());

            assertThat(fixtures.stockOf(product.getId())).isEqualTo(3);
            assertThat(fixtures.reloadProduct(product.getId()).getRevision()).isZero();
            assertThat(fixtures.reloadBrand(brand.getId()).getRevision()).isZero();
            assertThat(fixtures.listRevision()).isEqualTo(listBefore);
        }
    }
}
