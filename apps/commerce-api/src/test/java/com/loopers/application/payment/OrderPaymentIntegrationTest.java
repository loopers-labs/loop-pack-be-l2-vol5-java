package com.loopers.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.loopers.application.order.fixture.OrderFixture;
import com.loopers.application.payment.fixture.OrderPaymentFixture;
import com.loopers.application.point.fixture.PointFixture;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.order.StockReservationStatus;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.support.payment.LocalPgServer;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
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
class OrderPaymentIntegrationTest {
    private static final LocalPgServer PG = LocalPgServer.start();

    @Autowired private RequestOrderPaymentFacade requestPayment;
    @Autowired private ReconcileOrderPaymentFacade reconcilePayment;
    @Autowired private PaymentGateway paymentGateway;
    @Autowired private OrderPaymentFixture paymentFixture;
    @Autowired private ProductFixture products;
    @Autowired private OrderFixture orders;
    @Autowired private PointFixture points;
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
    void PG가_결제를_처리중이라고_응답하면_주문을_결제_대기로_저장한다() {
        // arrange
        var orderBeforePayment = paymentFixture.createUnpaidOrder();
        var paymentRequest = paymentFixture.createPaymentRequestCommand();

        // act
        requestPayment.request(orderBeforePayment.userId(), orderBeforePayment.orderId(), paymentRequest);

        // assert
        var orderAfterRequest = orders.order(orderBeforePayment.orderId());
        assertThat(orderAfterRequest.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(orderAfterRequest.getPaidAmount()).isNull();
    }

    @Test
    void PG가_결제를_처리중이라고_응답하면_주문_수량만큼_상품_재고를_확보해_둔다() {
        // arrange
        var orderBeforePayment = paymentFixture.createUnpaidOrder();
        var paymentRequest = paymentFixture.createPaymentRequestCommand();

        // act
        requestPayment.request(orderBeforePayment.userId(), orderBeforePayment.orderId(), paymentRequest);

        // assert
        var productAfterRequest = products.product(orderBeforePayment.productId());
        assertThat(productAfterRequest.getStock())
                .isEqualTo(orderBeforePayment.stockBeforePayment() - orderBeforePayment.orderQuantity());
        var stockReservation = paymentFixture.findReservationByOrderAndProduct(
                orderBeforePayment.orderId(), orderBeforePayment.productId());
        assertThat(stockReservation.quantity()).isEqualTo(orderBeforePayment.orderQuantity());
        assertThat(stockReservation.status()).isEqualTo(StockReservationStatus.HELD);
    }

    @Test
    void PG가_결제를_처리중이라고_응답하면_주문의_결제_식별자와_금액을_저장한다() {
        // arrange
        long unitPrice = 3_500;
        int initialStock = 5;
        int orderQuantity = 2;

        long buyerId = paymentFixture.createBuyer();
        var productToOrder = paymentFixture.createProductWithStock(unitPrice, initialStock);
        var orderBeforePayment = orders.createOrder(buyerId, productToOrder, orderQuantity);
        var paymentRequest = paymentFixture.createPaymentRequestCommand();

        // act
        var paymentResponse = requestPayment.request(buyerId, orderBeforePayment.getId(), paymentRequest);

        // assert
        assertThat(PG.transactions()).hasSize(1);

        var transactionCreatedByPg = PG.transactions().getFirst();
        var paymentAfterRequest = paymentFixture.findPaymentByOrderId(orderBeforePayment.getId());

        assertThat(paymentAfterRequest.paymentAttemptKey()).isEqualTo(paymentRequest.paymentAttemptKey());
        assertThat(paymentAfterRequest.providerOrderId()).isEqualTo(transactionCreatedByPg.orderId());
        assertThat(paymentAfterRequest.transactionKey()).isEqualTo(transactionCreatedByPg.transactionKey());
        assertThat(paymentAfterRequest.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentAfterRequest.amount()).isEqualTo(orderBeforePayment.getTotalAmount());
        assertThat(paymentResponse.providerOrderId()).isEqualTo(paymentAfterRequest.providerOrderId());
        assertThat(paymentResponse.orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(paymentResponse.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void 포인트를_보유한_구매자가_PG_결제를_요청해도_포인트_잔액은_줄어들지_않는다() {
        // arrange
        long pointBalanceBeforePayment = 10_000;
        long buyerId = paymentFixture.createBuyerWithPoints(pointBalanceBeforePayment);
        var productToOrder = paymentFixture.createProductWithStock(3_500, 5);
        var orderBeforePayment = orders.createOrder(buyerId, productToOrder, 2);
        var paymentRequest = paymentFixture.createPaymentRequestCommand();

        // act
        requestPayment.request(buyerId, orderBeforePayment.getId(), paymentRequest);

        // assert
        long pointBalanceAfterRequest = points.balance(buyerId);
        assertThat(pointBalanceAfterRequest).isEqualTo(pointBalanceBeforePayment);
    }

    @Test
    void PG가_거래를_생성했지만_응답이_유실되면_결제를_미확인으로_저장하고_새_결제를_요청하지_않는다() {
        // arrange
        var orderBeforePayment = paymentFixture.createUnpaidOrder();
        var paymentRequest = paymentFixture.createPaymentRequestCommand();
        PG.loseResponseAfterCreatingTransaction();

        // act
        var paymentResponse = requestPayment.request(
                orderBeforePayment.userId(), orderBeforePayment.orderId(), paymentRequest);

        // assert
        assertThat(PG.transactions()).hasSize(1);
        assertThat(PG.createRequestCount()).isEqualTo(1);
        var paymentAfterRequest = paymentFixture.findPaymentByOrderId(orderBeforePayment.orderId());
        assertThat(paymentAfterRequest.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(paymentAfterRequest.transactionKey()).isNull();
        assertThat(paymentResponse.paymentStatus()).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void PG_거래_생성_응답이_유실되면_주문은_결제_대기_상태로_유지된다() {
        // arrange
        var orderBeforePayment = paymentFixture.createUnpaidOrder();
        var paymentRequest = paymentFixture.createPaymentRequestCommand();
        PG.loseResponseAfterCreatingTransaction();

        // act
        requestPayment.request(orderBeforePayment.userId(), orderBeforePayment.orderId(), paymentRequest);

        // assert
        var orderAfterRequest = orders.order(orderBeforePayment.orderId());
        assertThat(orderAfterRequest.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(orderAfterRequest.getPaidAmount()).isNull();
    }

    @Test
    void PG_거래_생성_응답이_유실되어도_주문한_상품의_재고는_확보한_상태로_유지된다() {
        // arrange
        var orderBeforePayment = paymentFixture.createUnpaidOrder();
        var paymentRequest = paymentFixture.createPaymentRequestCommand();
        PG.loseResponseAfterCreatingTransaction();

        // act
        requestPayment.request(orderBeforePayment.userId(), orderBeforePayment.orderId(), paymentRequest);

        // assert
        var productAfterRequest = products.product(orderBeforePayment.productId());
        assertThat(productAfterRequest.getStock())
                .isEqualTo(orderBeforePayment.stockBeforePayment() - orderBeforePayment.orderQuantity());
        var stockReservation = paymentFixture.findReservationByOrderAndProduct(
                orderBeforePayment.orderId(), orderBeforePayment.productId());
        assertThat(stockReservation.quantity()).isEqualTo(orderBeforePayment.orderQuantity());
        assertThat(stockReservation.status()).isEqualTo(StockReservationStatus.HELD);
    }

    @Test
    void 응답_유실_후_PG의_기존_결제를_조회해_결제_성공을_확인하면_주문을_확정한다() {
        // arrange
        var orderBeforePayment = paymentFixture.createUnpaidOrder();
        var paymentRequest = paymentFixture.createPaymentRequestCommand();
        PG.loseResponseAfterCreatingTransaction();
        var responseAfterLoss = requestPayment.request(
                orderBeforePayment.userId(), orderBeforePayment.orderId(), paymentRequest);
        PG.succeedPaymentForOrder(responseAfterLoss.providerOrderId());

        // act
        reconcilePayment.reconcile(
                orderBeforePayment.userId(), orderBeforePayment.orderId());

        // assert
        assertThat(PG.lookupRequestCount()).isEqualTo(1);
        assertThat(PG.lastLookupOrderId()).isEqualTo(responseAfterLoss.providerOrderId());
        var orderAfterRecovery = orders.order(orderBeforePayment.orderId());
        assertThat(orderAfterRecovery.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(orderAfterRecovery.getPaidAmount()).isEqualTo(orderAfterRecovery.getTotalAmount());
    }

    @Test
    void 응답_유실_후_PG의_기존_결제를_조회해_결제_성공을_확인하면_내부_결제에_성공_상태와_거래키를_저장한다() {
        // arrange
        var orderBeforePayment = paymentFixture.createUnpaidOrder();
        var paymentRequest = paymentFixture.createPaymentRequestCommand();
        PG.loseResponseAfterCreatingTransaction();
        var responseAfterLoss = requestPayment.request(
                orderBeforePayment.userId(), orderBeforePayment.orderId(), paymentRequest);
        PG.succeedPaymentForOrder(responseAfterLoss.providerOrderId());

        // act
        reconcilePayment.reconcile(orderBeforePayment.userId(), orderBeforePayment.orderId());

        // assert
        var paymentAfterRecovery = paymentFixture.findPaymentByOrderId(orderBeforePayment.orderId());
        assertThat(paymentAfterRecovery.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(paymentAfterRecovery.transactionKey()).isEqualTo(PG.transactions().getFirst().transactionKey());
    }

    @Test
    void PG에서_성공한_결제를_여러_번_조회해도_재고를_추가로_차감하지_않는다() {
        // arrange
        var orderBeforePayment = paymentFixture.createUnpaidOrder();
        var paymentRequest = paymentFixture.createPaymentRequestCommand();
        PG.loseResponseAfterCreatingTransaction();
        var responseAfterLoss = requestPayment.request(
                orderBeforePayment.userId(), orderBeforePayment.orderId(), paymentRequest);
        PG.succeedPaymentForOrder(responseAfterLoss.providerOrderId());

        // act
        var firstLookupResult = reconcilePayment.reconcile(orderBeforePayment.userId(), orderBeforePayment.orderId());
        var repeatedLookupResult = reconcilePayment.reconcile(orderBeforePayment.userId(), orderBeforePayment.orderId());

        // assert
        assertThat(PG.createRequestCount()).isEqualTo(1);
        assertThat(firstLookupResult.payment().status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(repeatedLookupResult).isEqualTo(firstLookupResult);

        var stockReservation = paymentFixture.findReservationByOrderAndProduct(
                orderBeforePayment.orderId(), orderBeforePayment.productId());
        assertThat(stockReservation.status()).isEqualTo(StockReservationStatus.CONSUMED);
        assertThat(stockReservation.quantity()).isEqualTo(orderBeforePayment.orderQuantity());

        var productAfterRecovery = products.product(orderBeforePayment.productId());
        assertThat(productAfterRecovery.getStock())
                .isEqualTo(orderBeforePayment.stockBeforePayment() - orderBeforePayment.orderQuantity());
    }

    @Test
    void 같은_PG_결제의_성공_결과를_동시에_조회해도_주문과_재고에_한_번만_반영한다() throws Exception {
        // arrange
        var orderBeforePayment = paymentFixture.createUnpaidOrder();
        var paymentRequest = paymentFixture.createPaymentRequestCommand();
        PG.loseResponseAfterCreatingTransaction();
        var responseAfterLoss = requestPayment.request(
                orderBeforePayment.userId(), orderBeforePayment.orderId(), paymentRequest);
        PG.succeedPaymentForOrder(responseAfterLoss.providerOrderId());
        var startTogether = new CountDownLatch(1);

        try (var callers = Executors.newFixedThreadPool(2)) {
            // act
            var firstRequest = callers.submit(() -> {
                startTogether.await();
                return reconcilePayment.reconcile(orderBeforePayment.userId(), orderBeforePayment.orderId());
            });
            var secondRequest = callers.submit(() -> {
                startTogether.await();
                return reconcilePayment.reconcile(orderBeforePayment.userId(), orderBeforePayment.orderId());
            });
            startTogether.countDown();
            var firstLookupResult = firstRequest.get(5, TimeUnit.SECONDS);
            var secondLookupResult = secondRequest.get(5, TimeUnit.SECONDS);

            // assert
            assertThat(firstLookupResult.payment().status()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(secondLookupResult).isEqualTo(firstLookupResult);
            assertThat(orders.order(orderBeforePayment.orderId()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);

            var stockReservation = paymentFixture.findReservationByOrderAndProduct(
                    orderBeforePayment.orderId(), orderBeforePayment.productId());
            assertThat(stockReservation.status()).isEqualTo(StockReservationStatus.CONSUMED);
            assertThat(products.product(orderBeforePayment.productId()).getStock())
                    .isEqualTo(orderBeforePayment.stockBeforePayment() - orderBeforePayment.orderQuantity());
            assertThat(PG.createRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void PG_결제_성공_결과를_DB에_저장하지_못하면_주문과_결제와_재고_예약은_처리_전_상태를_유지한다() {
        // arrange
        var orderAwaitingPaymentResult = createOrderWithSuccessfulPgPaymentAndLostResponse();
        var expectedSaveFailure = failNextPaymentSuccessSave();

        // act
        Throwable saveFailure = catchThrowable(() -> reconcilePayment.reconcile(
                orderAwaitingPaymentResult.userId(), orderAwaitingPaymentResult.orderId()));

        // assert
        assertThat(saveFailure).isSameAs(expectedSaveFailure);
        var orderAfterFailedSave = orders.order(orderAwaitingPaymentResult.orderId());
        assertThat(orderAfterFailedSave.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(orderAfterFailedSave.getPaidAmount()).isNull();
        var paymentAfterFailedSave = paymentFixture.findPaymentByOrderId(orderAwaitingPaymentResult.orderId());
        assertThat(paymentAfterFailedSave.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(paymentAfterFailedSave.transactionKey()).isNull();
        var stockReservationAfterFailedSave = paymentFixture.findReservationByOrderAndProduct(
                orderAwaitingPaymentResult.orderId(), orderAwaitingPaymentResult.productId());
        assertThat(stockReservationAfterFailedSave.status()).isEqualTo(StockReservationStatus.HELD);
    }

    @Test
    void 결제_성공_결과를_DB에_저장하지_못한_뒤_같은_PG_거래를_다시_조회하면_새_결제_없이_주문을_확정한다() {
        // arrange
        var orderAwaitingPaymentResult = createOrderWithSuccessfulPgPaymentAndLostResponse();
        String existingPgTransactionKey = PG.transactions().getFirst().transactionKey();
        failNextPaymentSuccessSave();
        assertThrows(DataAccessResourceFailureException.class, () -> reconcilePayment.reconcile(
                orderAwaitingPaymentResult.userId(), orderAwaitingPaymentResult.orderId()));

        // act
        reconcilePayment.reconcile(orderAwaitingPaymentResult.userId(), orderAwaitingPaymentResult.orderId());

        // assert
        assertThat(orders.order(orderAwaitingPaymentResult.orderId()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        var paymentAfterRetry = paymentFixture.findPaymentByOrderId(orderAwaitingPaymentResult.orderId());
        assertThat(paymentAfterRetry.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(paymentAfterRetry.transactionKey()).isEqualTo(existingPgTransactionKey);
        assertThat(PG.lookupRequestCount()).isEqualTo(2);
        assertThat(PG.createRequestCount()).isEqualTo(1);
    }

    @Test
    void 응답_유실_후_기존_PG_결제를_조회해_아직_처리중이면_주문을_결제_대기로_두고_상품_재고_확보를_유지한다() {
        // arrange
        var orderBeforePayment = paymentFixture.createUnpaidOrder();
        var paymentRequest = paymentFixture.createPaymentRequestCommand();
        PG.loseResponseAfterCreatingTransaction();
        requestPayment.request(orderBeforePayment.userId(), orderBeforePayment.orderId(), paymentRequest);

        // act
        var lookupResult = reconcilePayment.reconcile(
                orderBeforePayment.userId(), orderBeforePayment.orderId());

        // assert
        assertThat(lookupResult.payment().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(lookupResult.payment().transactionKey()).isEqualTo(PG.transactions().getFirst().transactionKey());
        var orderAfterLookup = orders.order(orderBeforePayment.orderId());
        assertThat(orderAfterLookup.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(orderAfterLookup.getPaidAmount()).isNull();
        var stockReservation = paymentFixture.findReservationByOrderAndProduct(
                orderBeforePayment.orderId(), orderBeforePayment.productId());
        assertThat(stockReservation.status()).isEqualTo(StockReservationStatus.HELD);
    }

    @Test
    void 응답_유실_후_PG_결제_조회가_실패하면_결제_미확인과_상품_재고_확보를_유지하고_새_결제를_요청하지_않는다() {
        // arrange
        var orderBeforePayment = paymentFixture.createUnpaidOrder();
        var paymentRequest = paymentFixture.createPaymentRequestCommand();
        PG.loseResponseAfterCreatingTransaction();
        requestPayment.request(orderBeforePayment.userId(), orderBeforePayment.orderId(), paymentRequest);
        PG.failPaymentLookups();

        // act
        var lookupResult = reconcilePayment.reconcile(
                orderBeforePayment.userId(), orderBeforePayment.orderId());

        // assert
        assertThat(lookupResult.payment().status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(paymentFixture.findPaymentByOrderId(orderBeforePayment.orderId()).status())
                .isEqualTo(PaymentStatus.UNKNOWN);
        var stockReservation = paymentFixture.findReservationByOrderAndProduct(
                orderBeforePayment.orderId(), orderBeforePayment.productId());
        assertThat(stockReservation.status()).isEqualTo(StockReservationStatus.HELD);
        assertThat(PG.lookupRequestCount()).isEqualTo(2);
        assertThat(PG.createRequestCount()).isEqualTo(1);
    }

    @Test
    void 다른_구매자가_주문의_결제_결과를_조회하면_주문_없음_오류로_거절하고_PG를_조회하지_않는다() {
        // arrange
        var orderBeforePayment = paymentFixture.createUnpaidOrder();
        var paymentRequest = paymentFixture.createPaymentRequestCommand();
        PG.loseResponseAfterCreatingTransaction();
        requestPayment.request(orderBeforePayment.userId(), orderBeforePayment.orderId(), paymentRequest);
        long anotherBuyerId = paymentFixture.createBuyer();

        // act
        CoreException recoveryFailure = assertThrows(CoreException.class, () ->
                reconcilePayment.reconcile(anotherBuyerId, orderBeforePayment.orderId()));

        // assert
        assertThat(recoveryFailure.getErrorType()).isEqualTo(ErrorType.ORDER_NOT_FOUND);
        assertThat(PG.lookupRequestCount()).isZero();
        assertThat(paymentFixture.findPaymentByOrderId(orderBeforePayment.orderId()).status())
                .isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void PG에_같은_주문의_거래가_여러_개면_하나를_골라_확정하지_않고_재고_확보를_유지한다() {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var paymentCommand = paymentFixture.createPaymentRequestCommand();
        var acceptedPayment = requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(), paymentCommand);
        var storedOrder = orders.order(unpaidOrder.orderId());
        paymentGateway.create(acceptedPayment.providerOrderId(), storedOrder.getTotalAmount(),
                paymentCommand.cardType(), paymentCommand.cardNo());

        // act
        var lookupResult = reconcilePayment.reconcile(unpaidOrder.userId(), unpaidOrder.orderId());

        // assert
        assertThat(PG.transactions()).hasSize(2);
        assertThat(lookupResult.payment().status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(paymentFixture.findReservationByOrderAndProduct(unpaidOrder.orderId(), unpaidOrder.productId())
                .status()).isEqualTo(StockReservationStatus.HELD);
        assertThat(PG.createRequestCount()).isEqualTo(2);
    }

    @Test
    void PG_조회_금액이_저장한_결제_금액과_다르면_주문을_확정하지_않고_충돌로_거절한다() {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var acceptedPayment = requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(),
                paymentFixture.createPaymentRequestCommand());
        var storedOrder = orders.order(unpaidOrder.orderId());
        PG.respondWithLookupBody("""
                [{"transactionKey":"%s","orderId":"%s","amount":%d,"status":"SUCCESS"}]
                """.formatted(acceptedPayment.transactionKey(), acceptedPayment.providerOrderId(), storedOrder.getTotalAmount() + 1));

        // act
        var conflictingLookup = assertThrows(CoreException.class, () ->
                reconcilePayment.reconcile(unpaidOrder.userId(), unpaidOrder.orderId()));

        // assert
        assertThat(conflictingLookup.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        assertThat(orders.order(unpaidOrder.orderId()).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(paymentFixture.findReservationByOrderAndProduct(unpaidOrder.orderId(), unpaidOrder.productId())
                .status()).isEqualTo(StockReservationStatus.HELD);
    }

    private OrderPaymentFixture.PreparedOrder createOrderWithSuccessfulPgPaymentAndLostResponse() {
        var orderBeforePayment = paymentFixture.createUnpaidOrder();
        var paymentRequest = paymentFixture.createPaymentRequestCommand();
        PG.loseResponseAfterCreatingTransaction();
        var responseAfterLoss = requestPayment.request(
                orderBeforePayment.userId(), orderBeforePayment.orderId(), paymentRequest);
        PG.succeedPaymentForOrder(responseAfterLoss.providerOrderId());
        return orderBeforePayment;
    }

    private DataAccessResourceFailureException failNextPaymentSuccessSave() {
        var saveFailure = new DataAccessResourceFailureException("결제 성공 저장 실패");
        doThrow(saveFailure).doCallRealMethod()
                .when(paymentStore).save(argThat(payment -> payment.status() == PaymentStatus.SUCCESS));
        return saveFailure;
    }
}
