package com.loopers.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loopers.application.payment.fixture.OrderPaymentFixture;
import com.loopers.application.order.fixture.OrderFixture;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.order.StockReservationStatus;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.support.payment.LocalPgServer;
import com.loopers.utils.DatabaseCleanUp;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {
        "payment-provider.connect-timeout=500ms",
        "payment-provider.read-timeout=2s"
})
class OrderPaymentCircuitIntegrationTest {
    private static final LocalPgServer PG = LocalPgServer.start();

    @Autowired private RequestOrderPaymentFacade requestPayment;
    @Autowired private ReconcileOrderPaymentFacade reconcilePayment;
    @Autowired
    @Qualifier("circuitBreakingPaymentGateway")
    private PaymentGateway singleAttemptGateway;
    @Autowired private CircuitBreaker lookupCircuit;
    @Autowired private OrderPaymentFixture paymentFixture;
    @Autowired private OrderFixture orders;
    @Autowired private ProductFixture products;
    @Autowired private DatabaseCleanUp cleanUp;

    @DynamicPropertySource
    static void configurePgAddress(DynamicPropertyRegistry registry) {
        registry.add("payment-provider.base-url", PG::baseUrl);
    }

    @BeforeEach
    void setUp() {
        PG.reset();
        lookupCircuit.reset();
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
    void PG_조회가_9회_연속_실패해도_최소_표본_10회가_모이기_전에는_차단하지_않는다() {
        // arrange
        PG.failPaymentLookups();

        // act
        for (int i = 0; i < 9; i++) {
            assertThrows(PaymentGateway.UnconfirmedException.class,
                    () -> singleAttemptGateway.findByOrderId("LP-ORD-OTHER"));
        }

        // assert
        assertThat(lookupCircuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(PG.lookupRequestCount()).isEqualTo(9);
    }

    @Test
    void PG_조회가_차단되어도_저장된_결제_대기_상태와_거래키를_유지한다() {
        // arrange
        var orderAwaitingPayment = createOrderAwaitingPayment();
        var paymentBeforeLookup = paymentFixture.findPaymentByOrderId(orderAwaitingPayment.orderId());
        openCircuitWithFailedLookups();

        // act
        reconcilePayment.reconcile(orderAwaitingPayment.userId(), orderAwaitingPayment.orderId());

        // assert
        var paymentAfterBlockedLookup = paymentFixture.findPaymentByOrderId(orderAwaitingPayment.orderId());
        assertThat(paymentAfterBlockedLookup.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentAfterBlockedLookup.transactionKey()).isEqualTo(paymentBeforeLookup.transactionKey());
    }

    @Test
    void PG_조회가_차단되면_저장된_결제와_함께_조회_불가를_반환한다() {
        // arrange
        var orderAwaitingPayment = createOrderAwaitingPayment();
        openCircuitWithFailedLookups();

        // act
        var lookupResult = reconcilePayment.reconcile(orderAwaitingPayment.userId(), orderAwaitingPayment.orderId());

        // assert
        assertThat(lookupResult.lookupAvailable()).isFalse();
        assertThat(lookupResult.payment().status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void PG_조회가_차단되어도_주문을_결제_대기로_유지한다() {
        // arrange
        var orderAwaitingPayment = createOrderAwaitingPayment();
        openCircuitWithFailedLookups();

        // act
        reconcilePayment.reconcile(orderAwaitingPayment.userId(), orderAwaitingPayment.orderId());

        // assert
        assertThat(orders.order(orderAwaitingPayment.orderId()).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @Test
    void PG_조회가_차단되어도_주문한_상품의_재고_확보를_유지한다() {
        // arrange
        var orderAwaitingPayment = createOrderAwaitingPayment();
        openCircuitWithFailedLookups();

        // act
        reconcilePayment.reconcile(orderAwaitingPayment.userId(), orderAwaitingPayment.orderId());

        // assert
        var stockReservation = paymentFixture.findReservationByOrderAndProduct(
                orderAwaitingPayment.orderId(), orderAwaitingPayment.productId());
        assertThat(stockReservation.status()).isEqualTo(StockReservationStatus.HELD);
        assertThat(products.product(orderAwaitingPayment.productId()).getStock())
                .isEqualTo(orderAwaitingPayment.stockBeforePayment() - orderAwaitingPayment.orderQuantity());
    }

    @Test
    void PG_조회가_차단되어도_결제_생성_POST를_추가로_보내지_않는다() {
        // arrange
        var orderAwaitingPayment = createOrderAwaitingPayment();
        openCircuitWithFailedLookups();

        // act
        reconcilePayment.reconcile(orderAwaitingPayment.userId(), orderAwaitingPayment.orderId());

        // assert
        assertThat(PG.createRequestCount()).isEqualTo(1);
    }

    @Test
    void PG_조회가_차단되어도_이미_저장된_결제_성공을_유지한다() {
        // arrange
        var orderAwaitingPayment = createOrderAwaitingPayment();
        String providerOrderId = paymentFixture.findPaymentByOrderId(orderAwaitingPayment.orderId()).providerOrderId();
        PG.succeedPaymentForOrder(providerOrderId);
        reconcilePayment.reconcile(orderAwaitingPayment.userId(), orderAwaitingPayment.orderId());
        lookupCircuit.reset();
        openCircuitWithFailedLookups();

        // act
        var lookupResult = reconcilePayment.reconcile(orderAwaitingPayment.userId(), orderAwaitingPayment.orderId());

        // assert
        assertThat(lookupResult.payment().status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(paymentFixture.findPaymentByOrderId(orderAwaitingPayment.orderId()).status())
                .isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void 첫_PG_조회가_503으로_실패해도_재조회가_성공하면_조회_가능으로_반환한다() {
        // arrange
        var orderAwaitingPayment = createOrderAwaitingPayment();
        PG.failNextPaymentLookup();

        // act
        var lookupResult = reconcilePayment.reconcile(orderAwaitingPayment.userId(), orderAwaitingPayment.orderId());

        // assert
        assertThat(lookupResult.lookupAvailable()).isTrue();
    }

    @Test
    void PG_조회_재시도를_모두_실패해도_상품의_재고_예약을_유지한다() {
        // arrange
        var orderAwaitingPayment = createOrderAwaitingPayment();
        PG.failPaymentLookups();

        // act
        reconcilePayment.reconcile(orderAwaitingPayment.userId(), orderAwaitingPayment.orderId());

        // assert
        assertThat(PG.lookupRequestCount()).isEqualTo(2);
        var reservationAfterFailedLookups = paymentFixture.findReservationByOrderAndProduct(
                orderAwaitingPayment.orderId(), orderAwaitingPayment.productId());
        assertThat(reservationAfterFailedLookups.status()).isEqualTo(StockReservationStatus.HELD);
        assertThat(products.product(orderAwaitingPayment.productId()).getStock())
                .isEqualTo(orderAwaitingPayment.stockBeforePayment() - orderAwaitingPayment.orderQuantity());
    }

    @Test
    void PG가_결제_조회에_503을_응답하면_저장된_결제와_조회_불가를_반환한다() {
        // arrange
        var orderAwaitingPayment = createOrderAwaitingPayment();
        PG.failPaymentLookups();

        // act
        var lookupResult = reconcilePayment.reconcile(orderAwaitingPayment.userId(), orderAwaitingPayment.orderId());

        // assert
        assertThat(lookupResult.lookupAvailable()).isFalse();
        assertThat(lookupResult.payment().status()).isEqualTo(PaymentStatus.PENDING);
    }

    private OrderPaymentFixture.PreparedOrder createOrderAwaitingPayment() {
        var orderBeforePayment = paymentFixture.createUnpaidOrder();
        var paymentRequest = paymentFixture.createPaymentRequestCommand();
        requestPayment.request(orderBeforePayment.userId(), orderBeforePayment.orderId(), paymentRequest);
        return orderBeforePayment;
    }

    private void openCircuitWithFailedLookups() {
        PG.failPaymentLookups();
        for (int i = 0; i < 10; i++) {
            assertThrows(PaymentGateway.UnconfirmedException.class,
                    () -> singleAttemptGateway.findByOrderId("LP-ORD-OTHER"));
        }
    }
}
