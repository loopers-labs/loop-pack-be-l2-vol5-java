package com.loopers.application.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.order.fixture.OrderFixture;
import com.loopers.application.payment.fixture.OrderPaymentFixture;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.order.StockReservationStatus;
import com.loopers.infrastructure.product.fixture.ProductFixture;
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

import java.time.Duration;

@SpringBootTest(properties = {
        "payment-provider.connect-timeout=500ms",
        "payment-provider.read-timeout=500ms"
})
class OrderPaymentTimeoutIntegrationTest {
    private static final LocalPgServer PG = LocalPgServer.start();

    @Autowired private RequestOrderPaymentFacade requestPayment;
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
        PG.withholdCreateResponse();
    }

    @AfterEach
    void tearDown() {
        PG.releaseResponses();
        cleanUp.deleteAllEntities();
    }

    @AfterAll
    static void stopPg() {
        PG.close();
    }

    @Test
    void PG가_결제_생성_응답을_보내지_않아도_대기시간을_제한해_요청을_끝낸다() {
        // arrange
        var orderBeforePayment = paymentFixture.createUnpaidOrder();
        var paymentRequest = paymentFixture.createPaymentRequestCommand();

        // act
        long startedAt = System.nanoTime();
        requestPayment.request(orderBeforePayment.userId(), orderBeforePayment.orderId(), paymentRequest);
        var requestDuration = Duration.ofNanos(System.nanoTime() - startedAt);

        // assert: HTTP 대기는 500ms이며, DB 처리·실행 환경의 여유를 포함해 2초 안에 끝나야 한다.
        assertThat(requestDuration).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void PG_결제_생성_응답이_시간을_초과하면_결제를_결과_미확인으로_저장한다() {
        // arrange
        var orderBeforePayment = paymentFixture.createUnpaidOrder();
        var paymentRequest = paymentFixture.createPaymentRequestCommand();

        // act
        requestPayment.request(orderBeforePayment.userId(), orderBeforePayment.orderId(), paymentRequest);

        // assert
        var paymentAfterTimeout = paymentFixture.findPaymentByOrderId(orderBeforePayment.orderId());
        assertThat(paymentAfterTimeout.status()).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void PG_결제_생성_응답이_시간을_초과해도_주문을_결제_대기_상태로_유지한다() {
        // arrange
        var orderBeforePayment = paymentFixture.createUnpaidOrder();
        var paymentRequest = paymentFixture.createPaymentRequestCommand();

        // act
        requestPayment.request(orderBeforePayment.userId(), orderBeforePayment.orderId(), paymentRequest);

        // assert
        assertThat(orders.order(orderBeforePayment.orderId()).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @Test
    void PG_결제_생성_응답이_시간을_초과해도_주문한_상품의_재고_확보를_유지한다() {
        // arrange
        var orderBeforePayment = paymentFixture.createUnpaidOrder();
        var paymentRequest = paymentFixture.createPaymentRequestCommand();

        // act
        requestPayment.request(orderBeforePayment.userId(), orderBeforePayment.orderId(), paymentRequest);

        // assert
        var stockReservation = paymentFixture.findReservationByOrderAndProduct(
                orderBeforePayment.orderId(), orderBeforePayment.productId());
        assertThat(stockReservation.status()).isEqualTo(StockReservationStatus.HELD);
        assertThat(products.product(orderBeforePayment.productId()).getStock())
                .isEqualTo(orderBeforePayment.stockBeforePayment() - orderBeforePayment.orderQuantity());
    }

    @Test
    void PG_결제_생성_응답이_시간을_초과해도_결제_생성_POST를_추가로_보내지_않는다() {
        // arrange
        var orderBeforePayment = paymentFixture.createUnpaidOrder();
        var paymentRequest = paymentFixture.createPaymentRequestCommand();

        // act
        requestPayment.request(orderBeforePayment.userId(), orderBeforePayment.orderId(), paymentRequest);

        // assert
        assertThat(PG.createRequestCount()).isEqualTo(1);
    }
}
