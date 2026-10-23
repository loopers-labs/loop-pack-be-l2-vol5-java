package com.loopers.application.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.order.fixture.OrderFixture;
import com.loopers.application.payment.fixture.OrderPaymentFixture;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.StockReservationStatus;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.infrastructure.payment.CircuitBreakingPaymentGateway;
import com.loopers.infrastructure.payment.HttpPaymentGateway;
import com.loopers.infrastructure.payment.RetryingPaymentGateway;
import com.loopers.support.payment.LocalPgServer;
import com.loopers.utils.DatabaseCleanUp;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

@SpringBootTest
class OrderPaymentRestartIntegrationTest {
    private static final LocalPgServer PG = LocalPgServer.start();
    @Autowired private ApplicationContext application;
    @Autowired private OrderPaymentTransactions paymentTransactions;
    @Autowired private RequestOrderPaymentFacade requestPayment;
    @Autowired private OrderPaymentFixture paymentFixture;
    @Autowired private OrderFixture orders;
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
    void PG_발송_기록_저장_직후_중단됐다면_서비스_재생성_후_같은_요청에도_POST를_보내지_않는다() {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var paymentCommand = paymentFixture.createPaymentRequestCommand();
        paymentTransactions.prepare(unpaidOrder.userId(), unpaidOrder.orderId(), paymentCommand.paymentAttemptKey());

        // act
        try (var restarted = recreatePaymentServices()) {
            restarted.getBean(RequestOrderPaymentFacade.class)
                    .request(unpaidOrder.userId(), unpaidOrder.orderId(), paymentCommand);
        }

        // assert
        assertThat(PG.createRequestCount()).isZero();
        assertThat(paymentFixture.findPaymentByOrderId(unpaidOrder.orderId()).status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void 서비스_재생성_후_PG_거래가_아직_조회되지_않아도_확보한_재고를_반환하지_않는다() {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var paymentCommand = paymentFixture.createPaymentRequestCommand();
        paymentTransactions.prepare(unpaidOrder.userId(), unpaidOrder.orderId(), paymentCommand.paymentAttemptKey());

        // act
        try (var restarted = recreatePaymentServices()) {
            restarted.getBean(ReconcileOrderPaymentFacade.class).reconcile(unpaidOrder.userId(), unpaidOrder.orderId());
        }

        // assert
        assertThat(PG.lookupRequestCount()).isEqualTo(1);
        assertThat(PG.createRequestCount()).isZero();
        assertThat(paymentFixture.findReservationByOrderAndProduct(unpaidOrder.orderId(), unpaidOrder.productId())
                .status()).isEqualTo(StockReservationStatus.HELD);
    }

    @Test
    void 콜백을_받지_못하고_서비스를_재생성해도_기존_PG_거래의_성공을_조회하면_주문을_확정한다() {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        PG.loseResponseAfterCreatingTransaction();
        var unknownPayment = requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(),
                paymentFixture.createPaymentRequestCommand());
        PG.succeedPaymentForOrder(unknownPayment.providerOrderId());

        // act
        try (var restarted = recreatePaymentServices()) {
            restarted.getBean(ReconcileOrderPaymentFacade.class).reconcile(unpaidOrder.userId(), unpaidOrder.orderId());
        }

        // assert
        assertThat(orders.order(unpaidOrder.orderId()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(PG.createRequestCount()).isEqualTo(1);
        assertThat(paymentFixture.findPaymentByOrderId(unpaidOrder.orderId()).transactionKey())
                .isEqualTo(PG.transactions().getFirst().transactionKey());
    }

    private AnnotationConfigApplicationContext recreatePaymentServices() {
        // DB·PG는 유지하고 결제 서비스, TX 프록시, HTTP 클라이언트와 회복 도구를 새로 만든다.
        var restarted = new AnnotationConfigApplicationContext();
        restarted.setParent(application);
        restarted.register(RestartTransactions.class);
        restarted.registerBean(OrderPaymentTransactions.class);
        restarted.registerBean(HttpPaymentGateway.class, () -> new HttpPaymentGateway(RestClient.builder(),
                URI.create(PG.baseUrl()), URI.create("http://127.0.0.1:8080/api/v1/payments/callback"),
                Duration.ofMillis(500), Duration.ofSeconds(2)));
        restarted.registerBean(CircuitBreaker.class, () -> CircuitBreaker.of("restarted-pg-lookup",
                CircuitBreakerConfig.custom().slidingWindowSize(10).minimumNumberOfCalls(10)
                        .failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(10))
                        .permittedNumberOfCallsInHalfOpenState(1).build()));
        restarted.registerBean(CircuitBreakingPaymentGateway.class, () -> new CircuitBreakingPaymentGateway(
                restarted.getBean(HttpPaymentGateway.class), restarted.getBean(CircuitBreaker.class)));
        restarted.registerBean(RetryingPaymentGateway.class, () -> new RetryingPaymentGateway(
                restarted.getBean(CircuitBreakingPaymentGateway.class), 2, Duration.ofMillis(200), Duration.ofSeconds(5)));
        restarted.registerBean(RequestOrderPaymentFacade.class, () -> new RequestOrderPaymentFacade(
                restarted.getBean(OrderPaymentTransactions.class), restarted.getBean(RetryingPaymentGateway.class)));
        restarted.registerBean(ReconcileOrderPaymentFacade.class, () -> new ReconcileOrderPaymentFacade(
                restarted.getBean(OrderPaymentTransactions.class), restarted.getBean(RetryingPaymentGateway.class)));
        restarted.refresh();
        return restarted;
    }

    @EnableTransactionManagement
    static class RestartTransactions {}
}
