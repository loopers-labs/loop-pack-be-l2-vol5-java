package com.loopers.interfaces.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.loopers.application.order.fixture.OrderFixture;
import com.loopers.application.payment.ReconcileOrderPaymentFacade;
import com.loopers.application.payment.RequestOrderPaymentFacade;
import com.loopers.application.payment.fixture.OrderPaymentFixture;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.StockReservationStatus;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.support.payment.LocalPgServer;
import com.loopers.utils.DatabaseCleanUp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@SpringBootTest
class PaymentCallbackIntegrationTest {
    private static final LocalPgServer PG = LocalPgServer.start();
    @Autowired private MockMvc mvc;
    @Autowired private RequestOrderPaymentFacade requestPayment;
    @Autowired private ReconcileOrderPaymentFacade reconcilePayment;
    @Autowired private OrderPaymentFixture paymentFixture;
    @Autowired private OrderFixture orders;
    @Autowired private ProductFixture products;
    @Autowired private DatabaseCleanUp cleanUp;

    @DynamicPropertySource
    static void configurePg(DynamicPropertyRegistry registry) {
        registry.add("payment-provider.base-url", PG::baseUrl);
    }

    @BeforeEach
    void resetPg() {
        PG.reset();
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }

    @AfterAll
    static void stopPg() {
        PG.close();
    }

    @Test
    void 성공_콜백을_받아도_PG_조회_결과가_처리중이면_주문을_확정하지_않는다() throws Exception {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var acceptedPayment = requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(),
                paymentFixture.createPaymentRequestCommand());

        // act
        var callbackResponse = mvc.perform(post("/api/v1/payments/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(callbackBody(acceptedPayment, PaymentStatus.SUCCESS)));

        // assert
        callbackResponse.andExpect(status().isAccepted());
        assertThat(PG.lookupRequestCount()).isEqualTo(1);
        assertThat(orders.order(unpaidOrder.orderId()).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(paymentFixture.findPaymentByOrderId(unpaidOrder.orderId()).status()).isEqualTo(PaymentStatus.PENDING);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 콜백과_주문번호_조회_중_어느_쪽이_먼저_결제_성공을_확인해도_주문을_한_번만_확정한다(
            boolean callbackFirst) throws Exception {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var acceptedPayment = requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(),
                paymentFixture.createPaymentRequestCommand());
        PG.succeedPaymentForOrder(acceptedPayment.providerOrderId());

        // act
        if (callbackFirst) {
            deliverCallback(acceptedPayment, PaymentStatus.SUCCESS);
            reconcilePayment.reconcile(unpaidOrder.userId(), unpaidOrder.orderId());
        } else {
            reconcilePayment.reconcile(unpaidOrder.userId(), unpaidOrder.orderId());
            deliverCallback(acceptedPayment, PaymentStatus.SUCCESS);
        }

        // assert
        assertThat(orders.order(unpaidOrder.orderId()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(products.product(unpaidOrder.productId()).getStock())
                .isEqualTo(unpaidOrder.stockBeforePayment() - unpaidOrder.orderQuantity());
        assertThat(paymentFixture.findPaymentByOrderId(unpaidOrder.orderId()).transactionKey())
                .isEqualTo(acceptedPayment.transactionKey());
    }

    @Test
    void PG에서_실패한_결제의_콜백을_여러_번_받아도_확보했던_재고를_한_번만_돌려준다() throws Exception {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var acceptedPayment = requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(),
                paymentFixture.createPaymentRequestCommand());
        PG.failPaymentForOrder(acceptedPayment.providerOrderId());

        // act
        deliverCallback(acceptedPayment, PaymentStatus.FAILED);
        deliverCallback(acceptedPayment, PaymentStatus.FAILED);

        // assert
        assertThat(products.product(unpaidOrder.productId()).getStock()).isEqualTo(unpaidOrder.stockBeforePayment());
        assertThat(paymentFixture.findReservationByOrderAndProduct(unpaidOrder.orderId(), unpaidOrder.productId())
                .status()).isEqualTo(StockReservationStatus.RELEASED);
    }

    @Test
    void 콜백과_주문번호_조회가_동시에_결제_성공을_확인해도_재고를_추가로_차감하지_않는다() throws Exception {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var acceptedPayment = requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(),
                paymentFixture.createPaymentRequestCommand());
        PG.succeedPaymentForOrder(acceptedPayment.providerOrderId());
        var startTogether = new CountDownLatch(1);

        try (var callers = Executors.newFixedThreadPool(2)) {
            // act
            var callback = callers.submit(() -> {
                startTogether.await();
                deliverCallback(acceptedPayment, PaymentStatus.SUCCESS);
                return null;
            });
            var lookup = callers.submit(() -> {
                startTogether.await();
                return reconcilePayment.reconcile(unpaidOrder.userId(), unpaidOrder.orderId());
            });
            startTogether.countDown();
            callback.get(5, TimeUnit.SECONDS);
            lookup.get(5, TimeUnit.SECONDS);

            // assert
            assertThat(orders.order(unpaidOrder.orderId()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(products.product(unpaidOrder.productId()).getStock())
                    .isEqualTo(unpaidOrder.stockBeforePayment() - unpaidOrder.orderQuantity());
            assertThat(PG.createRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void 콜백의_거래키가_저장된_결제와_다르면_충돌로_거절하고_주문을_대기로_유지한다() throws Exception {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var acceptedPayment = requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(),
                paymentFixture.createPaymentRequestCommand());
        String otherTransactionNotification = callbackBody(acceptedPayment, PaymentStatus.SUCCESS)
                .replace(acceptedPayment.transactionKey(), "TEST-TX-OTHER");

        // act
        var callbackResponse = mvc.perform(post("/api/v1/payments/callback")
                .contentType(MediaType.APPLICATION_JSON).content(otherTransactionNotification));

        // assert
        callbackResponse.andExpect(status().isConflict());
        assertThat(orders.order(unpaidOrder.orderId()).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(PG.lookupRequestCount()).isZero();
    }

    @Test
    void 콜백을_받아도_PG를_조회할_수_없으면_주문과_확보한_재고를_유지한다() throws Exception {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var acceptedPayment = requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(),
                paymentFixture.createPaymentRequestCommand());
        PG.failPaymentLookups();

        // act
        var callbackResponse = mvc.perform(post("/api/v1/payments/callback")
                .contentType(MediaType.APPLICATION_JSON).content(callbackBody(acceptedPayment, PaymentStatus.SUCCESS)));

        // assert
        callbackResponse.andExpect(status().isAccepted());
        assertThat(orders.order(unpaidOrder.orderId()).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(paymentFixture.findReservationByOrderAndProduct(unpaidOrder.orderId(), unpaidOrder.productId())
                .status()).isEqualTo(StockReservationStatus.HELD);
    }

    @Test
    void 콜백이_먼저_결제_성공을_저장하면_늦게_도착한_PG_접수_응답이_처리중으로_덮어쓰지_않는다() throws Exception {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var paymentCommand = paymentFixture.createPaymentRequestCommand();
        PG.withholdCreateResponse();
        try (var callers = Executors.newSingleThreadExecutor()) {
            var waitingForAcceptance = callers.submit(() ->
                    requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(), paymentCommand));
            try {
                assertThat(PG.awaitCreateRequest()).isTrue();
                var pgTransaction = PG.transactions().getFirst();
                PG.succeedPaymentForOrder(pgTransaction.orderId());

                // act
                mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                                .content(callbackBody(pgTransaction.orderId(), pgTransaction.transactionKey(), PaymentStatus.SUCCESS)))
                        .andExpect(status().isOk());
                PG.releaseResponses();
                var responseAfterCallback = waitingForAcceptance.get(5, TimeUnit.SECONDS);

                // assert
                assertThat(responseAfterCallback.paymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
                assertThat(paymentFixture.findPaymentByOrderId(unpaidOrder.orderId()).status())
                        .isEqualTo(PaymentStatus.SUCCESS);
            } finally {
                PG.releaseResponses();
            }
        }
    }

    @Test
    void 주문이_결제_성공으로_확정된_뒤_상충하는_PG_실패를_알리는_콜백은_거절한다() throws Exception {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var acceptedPayment = requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(),
                paymentFixture.createPaymentRequestCommand());
        PG.succeedPaymentForOrder(acceptedPayment.providerOrderId());
        deliverCallback(acceptedPayment, PaymentStatus.SUCCESS);
        PG.failPaymentForOrder(acceptedPayment.providerOrderId());

        // act
        var conflictingCallback = mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                .content(callbackBody(acceptedPayment, PaymentStatus.FAILED)));

        // assert
        conflictingCallback.andExpect(status().isConflict());
        assertThat(paymentFixture.findPaymentByOrderId(unpaidOrder.orderId()).status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(orders.order(unpaidOrder.orderId()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    private void deliverCallback(RequestOrderPaymentFacade.Result payment, PaymentStatus notificationStatus) throws Exception {
        mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                        .content(callbackBody(payment, notificationStatus)))
                .andExpect(status().isOk());
    }

    private String callbackBody(RequestOrderPaymentFacade.Result payment, PaymentStatus notificationStatus) {
        return callbackBody(payment.providerOrderId(), payment.transactionKey(), notificationStatus);
    }

    private String callbackBody(String providerOrderId, String transactionKey, PaymentStatus notificationStatus) {
        return """
                {"providerOrderId":"%s","transactionKey":"%s","status":"%s"}
                """.formatted(providerOrderId, transactionKey, notificationStatus);
    }
}
