package com.loopers.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.loopers.application.payment.fixture.OrderPaymentFixture;
import com.loopers.application.order.fixture.OrderFixture;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.StockReservationStatus;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.payment.LocalPgServer;
import com.loopers.utils.DatabaseCleanUp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@SpringBootTest
class OrderPaymentDispatchIntegrationTest {
    private static final LocalPgServer PG = LocalPgServer.start();

    @Autowired private RequestOrderPaymentFacade requestPayment;
    @Autowired private OrderPaymentTransactions paymentTransactions;
    @Autowired private OrderPaymentFixture paymentFixture;
    @Autowired private ProductFixture products;
    @Autowired private OrderFixture orders;
    @Autowired private ReconcileOrderPaymentFacade reconcilePayment;
    @Autowired private DatabaseCleanUp cleanUp;

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
    void 같은_주문의_같은_결제를_다시_요청하면_추가_POST_없이_기존_결제를_반환한다() {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var paymentCommand = paymentFixture.createPaymentRequestCommand();
        var firstResult = requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(), paymentCommand);

        // act
        var repeatedResult = requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(), paymentCommand);

        // assert
        assertThat(repeatedResult).isEqualTo(firstResult);
        assertThat(PG.createRequestCount()).isEqualTo(1);
    }

    @Test
    void 같은_주문의_같은_결제를_동시에_요청해도_PG에_POST를_한_번만_보낸다() throws Exception {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var paymentCommand = paymentFixture.createPaymentRequestCommand();
        var startTogether = new CountDownLatch(1);

        try (var callers = Executors.newFixedThreadPool(2)) {
            // act
            var firstRequest = callers.submit(() -> {
                startTogether.await();
                return requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(), paymentCommand);
            });
            var secondRequest = callers.submit(() -> {
                startTogether.await();
                return requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(), paymentCommand);
            });
            startTogether.countDown();
            firstRequest.get(5, TimeUnit.SECONDS);
            secondRequest.get(5, TimeUnit.SECONDS);

            // assert
            assertThat(PG.createRequestCount()).isEqualTo(1);
            assertThat(PG.transactions()).hasSize(1);
        }
    }

    @Test
    void 같은_결제를_다시_요청해도_주문한_상품의_재고를_추가로_확보하지_않는다() {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var paymentCommand = paymentFixture.createPaymentRequestCommand();
        requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(), paymentCommand);

        // act
        requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(), paymentCommand);

        // assert
        assertThat(products.product(unpaidOrder.productId()).getStock())
                .isEqualTo(unpaidOrder.stockBeforePayment() - unpaidOrder.orderQuantity());
    }

    @Test
    void PG_응답이_유실된_결제를_다시_요청해도_PG에_추가_POST를_보내지_않는다() {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var paymentCommand = paymentFixture.createPaymentRequestCommand();
        PG.loseResponseAfterCreatingTransaction();
        requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(), paymentCommand);

        // act
        var repeatedResult = requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(), paymentCommand);

        // assert
        assertThat(repeatedResult.paymentStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(PG.createRequestCount()).isEqualTo(1);
    }

    @Test
    void 결제가_성공한_뒤_같은_결제를_다시_요청하면_PG에_추가_POST_없이_성공_결과를_반환한다() {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var paymentCommand = paymentFixture.createPaymentRequestCommand();
        var acceptedPayment = requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(), paymentCommand);
        PG.succeedPaymentForOrder(acceptedPayment.providerOrderId());
        reconcilePayment.reconcile(unpaidOrder.userId(), unpaidOrder.orderId());

        // act
        var repeatedResult = requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(), paymentCommand);

        // assert
        assertThat(repeatedResult.orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(repeatedResult.paymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(PG.createRequestCount()).isEqualTo(1);
    }

    @Test
    void 이미_결제를_시작한_주문에_다른_시도키로_요청하면_충돌로_거절한다() {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var originalCommand = paymentFixture.createPaymentRequestCommand();
        requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(), originalCommand);
        var differentAttemptCommand = new RequestOrderPaymentFacade.Command(
                UUID.fromString("017f22e2-79b0-7cc3-98c4-dc0c0c073990"),
                originalCommand.cardType(), originalCommand.cardNo());

        // act
        Throwable conflictingRequest = catchThrowable(() -> requestPayment.request(
                unpaidOrder.userId(), unpaidOrder.orderId(), differentAttemptCommand));

        // assert
        assertConflict(conflictingRequest);
        assertThat(PG.createRequestCount()).isEqualTo(1);
    }

    @Test
    void 다른_주문에서_사용한_결제_시도키로_요청하면_충돌로_거절하고_재고를_확보하지_않는다() {
        // arrange
        var firstOrder = paymentFixture.createUnpaidOrder();
        var secondOrder = paymentFixture.createUnpaidOrder();
        var sameAttemptCommand = paymentFixture.createPaymentRequestCommand();
        requestPayment.request(firstOrder.userId(), firstOrder.orderId(), sameAttemptCommand);

        // act
        Throwable conflictingRequest = catchThrowable(() -> requestPayment.request(
                secondOrder.userId(), secondOrder.orderId(), sameAttemptCommand));

        // assert
        assertConflict(conflictingRequest);
        assertThat(products.product(secondOrder.productId()).getStock()).isEqualTo(secondOrder.stockBeforePayment());
    }

    @Test
    void 서로_다른_주문이_같은_결제_시도키를_동시에_사용하면_한_주문만_PG에_보낸다() throws Exception {
        // arrange
        var firstOrder = paymentFixture.createUnpaidOrder();
        var secondOrder = paymentFixture.createUnpaidOrder();
        var sameAttemptCommand = paymentFixture.createPaymentRequestCommand();
        var startTogether = new CountDownLatch(1);

        try (var callers = Executors.newFixedThreadPool(2)) {
            // act
            var firstRequest = callers.submit(() -> {
                startTogether.await();
                return catchThrowable(() -> requestPayment.request(
                        firstOrder.userId(), firstOrder.orderId(), sameAttemptCommand));
            });
            var secondRequest = callers.submit(() -> {
                startTogether.await();
                return catchThrowable(() -> requestPayment.request(
                        secondOrder.userId(), secondOrder.orderId(), sameAttemptCommand));
            });
            startTogether.countDown();
            Throwable firstFailure = firstRequest.get(5, TimeUnit.SECONDS);
            Throwable secondFailure = secondRequest.get(5, TimeUnit.SECONDS);

            // assert
            var rejectedRequests = Stream.of(firstFailure, secondFailure).filter(Objects::nonNull).toList();
            assertThat(rejectedRequests).hasSize(1);
            assertConflict(rejectedRequests.getFirst());
            assertThat(PG.createRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void 과거_포인트로_확정된_주문에_PG_결제를_요청하면_기존_규약대로_잘못된_요청으로_거절한다() {
        // arrange
        long buyerId = paymentFixture.createBuyer();
        var product = paymentFixture.createProductWithStock(3_500, 5);
        var confirmedOrder = orders.createConfirmedOrder(buyerId,
                List.of(new Order.RequestedItem(product.getId(), 2, 3_500)));
        var paymentCommand = paymentFixture.createPaymentRequestCommand();

        // act
        Throwable invalidRequest = catchThrowable(() -> requestPayment.request(
                buyerId, confirmedOrder.getId(), paymentCommand));

        // assert
        assertThat(invalidRequest).isInstanceOfSatisfying(CoreException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST));
        assertThat(PG.createRequestCount()).isZero();
    }

    @Test
    void 첫_PG_POST의_응답을_기다리는_동안_같은_결제를_다시_요청하면_추가_POST_없이_대기_결과를_반환한다() throws Exception {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var paymentCommand = paymentFixture.createPaymentRequestCommand();
        PG.withholdCreateResponse();

        try (var callers = Executors.newSingleThreadExecutor()) {
            var waitingRequest = callers.submit(() -> requestPayment.request(
                    unpaidOrder.userId(), unpaidOrder.orderId(), paymentCommand));
            try {
                assertThat(PG.awaitCreateRequest()).isTrue();

                // act
                var duplicateResult = requestPayment.request(
                        unpaidOrder.userId(), unpaidOrder.orderId(), paymentCommand);

                // assert
                assertThat(waitingRequest.isDone()).isFalse();
                assertThat(duplicateResult.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
                assertThat(PG.createRequestCount()).isEqualTo(1);
            } finally {
                PG.releaseResponses();
            }
            waitingRequest.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void PG가_거래_생성_전에_거절하면_거래키_없이_주문과_결제를_실패로_저장한다() {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var paymentCommand = paymentFixture.createPaymentRequestCommand();
        PG.rejectBeforeCreatingTransaction();

        // act
        var rejectedResult = requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(), paymentCommand);

        // assert
        assertThat(orders.order(unpaidOrder.orderId()).getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        var rejectedPayment = paymentFixture.findPaymentByOrderId(unpaidOrder.orderId());
        assertThat(rejectedPayment.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(rejectedPayment.transactionKey()).isNull();
        assertThat(rejectedResult.paymentStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(PG.transactions()).isEmpty();
    }

    @Test
    void PG가_거래_생성_전에_거절하면_확보했던_수량을_상품_재고에_반환한다() {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var paymentCommand = paymentFixture.createPaymentRequestCommand();
        PG.rejectBeforeCreatingTransaction();

        // act
        requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(), paymentCommand);

        // assert
        assertThat(products.product(unpaidOrder.productId()).getStock()).isEqualTo(unpaidOrder.stockBeforePayment());
        assertThat(paymentFixture.findReservationByOrderAndProduct(
                unpaidOrder.orderId(), unpaidOrder.productId()).status()).isEqualTo(StockReservationStatus.RELEASED);
    }

    @Test
    void 거래_생성_전_거절을_중복_반영해도_상품_재고를_추가로_반환하지_않는다() {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var paymentCommand = paymentFixture.createPaymentRequestCommand();
        PG.rejectBeforeCreatingTransaction();
        requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(), paymentCommand);
        var rejectedPayment = paymentFixture.findPaymentByOrderId(unpaidOrder.orderId());
        var sameRejection = new PaymentGateway.RejectedBeforeCreateException(
                rejectedPayment.providerOrderId(), rejectedPayment.amount());

        // act
        paymentTransactions.recordRejection(unpaidOrder.orderId(), sameRejection);

        // assert
        assertThat(products.product(unpaidOrder.productId()).getStock()).isEqualTo(unpaidOrder.stockBeforePayment());
    }

    @Test
    void 효과_0건을_보장하지_않는_422를_받으면_결제를_미확인으로_남기고_재고_확보를_유지한다() {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var paymentCommand = paymentFixture.createPaymentRequestCommand();
        PG.respondBeforeCreateWith(422, "{}");

        // act
        var unknownResult = requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(), paymentCommand);

        // assert
        assertThat(unknownResult.paymentStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(paymentFixture.findReservationByOrderAndProduct(
                unpaidOrder.orderId(), unpaidOrder.productId()).status()).isEqualTo(StockReservationStatus.HELD);
    }

    private static void assertConflict(Throwable failure) {
        assertThat(failure).isInstanceOfSatisfying(CoreException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT));
    }

}
