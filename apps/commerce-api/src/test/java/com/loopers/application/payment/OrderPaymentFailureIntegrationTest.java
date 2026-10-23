package com.loopers.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.loopers.application.order.fixture.OrderFixture;
import com.loopers.application.payment.fixture.OrderPaymentFixture;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentResult;
import com.loopers.domain.order.StockReservationStatus;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.support.payment.LocalPgServer;
import com.loopers.utils.DatabaseCleanUp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest(properties = {
        "payment-provider.connect-timeout=500ms",
        "payment-provider.read-timeout=2s"
})
class OrderPaymentFailureIntegrationTest {
    private static final LocalPgServer PG = LocalPgServer.start();

    @Autowired private RequestOrderPaymentFacade requestPayment;
    @Autowired private ReconcileOrderPaymentFacade reconcilePayment;
    @Autowired private OrderPaymentFixture paymentFixture;
    @Autowired private OrderFixture orders;
    @Autowired private ProductFixture products;
    @Autowired private ProductRepository productRepository;
    @Autowired private DatabaseCleanUp cleanUp;
    @MockitoSpyBean private PaymentRepository paymentStore;

    @DynamicPropertySource
    static void configurePgAddress(DynamicPropertyRegistry registry) {
        registry.add("payment-provider.base-url", PG::baseUrl);
    }

    @BeforeEach
    void setUp() {
        PG.reset();
    }

    @AfterEach
    void tearDown() {
        cleanUp.deleteAllEntities();
    }

    @AfterAll
    static void stopPg() {
        PG.close();
    }

    @Test
    void PG에서_실패한_결제를_조회하면_주문을_결제_실패로_저장한다() {
        // arrange
        var orderAwaitingPaymentResult = createOrderWithFailedPgPayment();

        // act
        reconcilePayment.reconcile(orderAwaitingPaymentResult.userId(), orderAwaitingPaymentResult.orderId());

        // assert
        var orderAfterLookup = orders.order(orderAwaitingPaymentResult.orderId());
        assertThat(orderAfterLookup.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(orderAfterLookup.getPaymentResult()).isEqualTo(PaymentResult.FAILED);
        assertThat(orderAfterLookup.getPaidAmount()).isNull();
    }

    @Test
    void PG_접수_응답이_유실된_뒤_실패한_거래를_조회하면_결제에_실패_상태와_거래키를_저장한다() {
        // arrange
        PG.loseResponseAfterCreatingTransaction();
        var orderAwaitingPaymentResult = createOrderWithFailedPgPayment();
        String failedTransactionKey = PG.transactions().getFirst().transactionKey();

        // act
        reconcilePayment.reconcile(orderAwaitingPaymentResult.userId(), orderAwaitingPaymentResult.orderId());

        // assert
        var paymentAfterLookup = paymentFixture.findPaymentByOrderId(orderAwaitingPaymentResult.orderId());
        assertThat(paymentAfterLookup.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(paymentAfterLookup.transactionKey()).isEqualTo(failedTransactionKey);
    }

    @Test
    void PG에서_실패한_결제를_조회하면_확보했던_수량을_상품_재고에_돌려준다() {
        // arrange
        var orderAwaitingPaymentResult = createOrderWithFailedPgPayment();

        // act
        reconcilePayment.reconcile(orderAwaitingPaymentResult.userId(), orderAwaitingPaymentResult.orderId());

        // assert
        var productAfterReturn = products.product(orderAwaitingPaymentResult.productId());
        assertThat(productAfterReturn.getStock()).isEqualTo(orderAwaitingPaymentResult.stockBeforePayment());
    }

    @Test
    void PG에서_실패한_결제를_조회하면_주문의_재고_예약을_반환_완료로_저장한다() {
        // arrange
        var orderAwaitingPaymentResult = createOrderWithFailedPgPayment();

        // act
        reconcilePayment.reconcile(orderAwaitingPaymentResult.userId(), orderAwaitingPaymentResult.orderId());

        // assert
        var reservationAfterReturn = paymentFixture.findReservationByOrderAndProduct(
                orderAwaitingPaymentResult.orderId(), orderAwaitingPaymentResult.productId());
        assertThat(reservationAfterReturn.status()).isEqualTo(StockReservationStatus.RELEASED);
    }

    @Test
    void 같은_PG_결제_실패를_다시_조회해도_상품_재고를_두_번_반환하지_않는다() {
        // arrange
        var orderAwaitingPaymentResult = createOrderWithFailedPgPayment();
        reconcilePayment.reconcile(orderAwaitingPaymentResult.userId(), orderAwaitingPaymentResult.orderId());
        int stockAfterFirstReturn = products.product(orderAwaitingPaymentResult.productId()).getStock();

        // act
        reconcilePayment.reconcile(orderAwaitingPaymentResult.userId(), orderAwaitingPaymentResult.orderId());

        // assert
        assertThat(products.product(orderAwaitingPaymentResult.productId()).getStock()).isEqualTo(stockAfterFirstReturn);
    }

    @Test
    void 같은_PG_결제_실패를_두_요청이_동시에_조회해도_재고는_한_번만_반환한다() throws Exception {
        // arrange
        var orderAwaitingPaymentResult = createOrderWithFailedPgPayment();
        var startTogether = new CountDownLatch(1);

        try (var callers = Executors.newFixedThreadPool(2)) {
            // act
            var firstLookup = callers.submit(() -> {
                startTogether.await();
                return reconcilePayment.reconcile(orderAwaitingPaymentResult.userId(), orderAwaitingPaymentResult.orderId());
            });
            var secondLookup = callers.submit(() -> {
                startTogether.await();
                return reconcilePayment.reconcile(orderAwaitingPaymentResult.userId(), orderAwaitingPaymentResult.orderId());
            });
            startTogether.countDown();
            firstLookup.get(5, TimeUnit.SECONDS);
            secondLookup.get(5, TimeUnit.SECONDS);

            // assert
            assertThat(products.product(orderAwaitingPaymentResult.productId()).getStock())
                    .isEqualTo(orderAwaitingPaymentResult.stockBeforePayment());
        }
    }

    @Test
    void 결제_실패_결과를_DB에_저장하지_못하면_주문과_결제와_재고_예약은_처리_전_상태를_유지한다() {
        // arrange
        var orderAwaitingPaymentResult = createOrderWithFailedPgPayment();
        var expectedSaveFailure = failNextPaymentFailureSave();

        // act
        Throwable saveFailure = catchThrowable(() -> reconcilePayment.reconcile(
                orderAwaitingPaymentResult.userId(), orderAwaitingPaymentResult.orderId()));

        // assert
        assertThat(saveFailure).isSameAs(expectedSaveFailure);
        assertThat(orders.order(orderAwaitingPaymentResult.orderId()).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(paymentFixture.findPaymentByOrderId(orderAwaitingPaymentResult.orderId()).status())
                .isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentFixture.findReservationByOrderAndProduct(
                orderAwaitingPaymentResult.orderId(), orderAwaitingPaymentResult.productId()).status())
                .isEqualTo(StockReservationStatus.HELD);
        assertThat(products.product(orderAwaitingPaymentResult.productId()).getStock())
                .isEqualTo(orderAwaitingPaymentResult.stockBeforePayment() - orderAwaitingPaymentResult.orderQuantity());
    }

    @Test
    void 결제_실패_결과를_DB에_저장하지_못한_뒤_같은_PG_거래를_다시_조회하면_재고를_한_번_반환한다() {
        // arrange
        var orderAwaitingPaymentResult = createOrderWithFailedPgPayment();
        failNextPaymentFailureSave();
        assertThrows(DataAccessResourceFailureException.class, () -> reconcilePayment.reconcile(
                orderAwaitingPaymentResult.userId(), orderAwaitingPaymentResult.orderId()));

        // act
        reconcilePayment.reconcile(orderAwaitingPaymentResult.userId(), orderAwaitingPaymentResult.orderId());

        // assert
        assertThat(products.product(orderAwaitingPaymentResult.productId()).getStock())
                .isEqualTo(orderAwaitingPaymentResult.stockBeforePayment());
    }

    @Test
    void 재고_확보_후_삭제된_상품도_PG_결제_실패를_조회하면_삭제_상태를_유지하며_재고를_반환한다() {
        // arrange
        var orderAwaitingPaymentResult = createOrderWithFailedPgPayment();
        productRepository.delete(orderAwaitingPaymentResult.productId());

        // act
        reconcilePayment.reconcile(orderAwaitingPaymentResult.userId(), orderAwaitingPaymentResult.orderId());

        // assert
        var deletedProductAfterReturn = products.product(orderAwaitingPaymentResult.productId());
        assertThat(deletedProductAfterReturn.isDeleted()).isTrue();
        assertThat(deletedProductAfterReturn.getStock()).isEqualTo(orderAwaitingPaymentResult.stockBeforePayment());
    }

    @Test
    void 관리자가_가용_재고를_변경한_후_PG_결제_실패를_조회하면_변경된_재고에_확보_수량을_더한다() {
        // arrange
        var orderAwaitingPaymentResult = createOrderWithFailedPgPayment();
        int stockSetByAdmin = 10;
        productRepository.setStock(orderAwaitingPaymentResult.productId(), stockSetByAdmin);

        // act
        reconcilePayment.reconcile(orderAwaitingPaymentResult.userId(), orderAwaitingPaymentResult.orderId());

        // assert
        assertThat(products.product(orderAwaitingPaymentResult.productId()).getStock())
                .isEqualTo(stockSetByAdmin + orderAwaitingPaymentResult.orderQuantity());
    }

    @Test
    void PG에서_실패한_결제를_여러_번_조회해도_새_결제_생성_POST를_보내지_않는다() {
        // arrange
        var orderAwaitingPaymentResult = createOrderWithFailedPgPayment();

        // act
        reconcilePayment.reconcile(orderAwaitingPaymentResult.userId(), orderAwaitingPaymentResult.orderId());
        reconcilePayment.reconcile(orderAwaitingPaymentResult.userId(), orderAwaitingPaymentResult.orderId());

        // assert
        assertThat(PG.createRequestCount()).isEqualTo(1);
    }

    private DataAccessResourceFailureException failNextPaymentFailureSave() {
        var saveFailure = new DataAccessResourceFailureException("결제 실패 결과 저장 실패");
        doThrow(saveFailure).doCallRealMethod()
                .when(paymentStore).save(argThat(payment -> payment.status() == PaymentStatus.FAILED));
        return saveFailure;
    }

    private OrderPaymentFixture.PreparedOrder createOrderWithFailedPgPayment() {
        var orderBeforePayment = paymentFixture.createUnpaidOrder();
        var paymentRequest = paymentFixture.createPaymentRequestCommand();
        var acceptedPayment = requestPayment.request(
                orderBeforePayment.userId(), orderBeforePayment.orderId(), paymentRequest);
        PG.failPaymentForOrder(acceptedPayment.providerOrderId());
        return orderBeforePayment;
    }
}
