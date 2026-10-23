package com.loopers.infrastructure.payment;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderPaymentInfo;
import com.loopers.application.order.OrderPaymentTransactions;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentEvent;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserService;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.support.payment.LookupSettingsFixture;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 같은 DB·PG를 유지하고 서비스·HTTP 클라이언트를 재생성한다. JVM 종료·재기동 자체를 검증하지는 않는다. */
@SpringBootTest
class PaymentRecoveryRestartTest {

    private static final FakePaymentServer PG = startPg();

    @DynamicPropertySource
    static void pgUrl(DynamicPropertyRegistry registry) {
        registry.add("commerce.payment.pg.base-url", PG::baseUrl);
    }

    @Autowired private AutowireCapableBeanFactory beans;
    @Autowired private UserRegistrationService registration;
    @Autowired private OrderFacade orderFacade;
    @Autowired private OrderPaymentTransactions paymentTransactions;
    @Autowired private PaymentProvider paymentProvider;
    @Autowired private BrandJpaRepository brands;
    @Autowired private ProductJpaRepository products;
    @Autowired private OrderJpaRepository orders;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private DatabaseCleanUp databaseCleanUp;

    private Long userId;
    private Long productId;
    private Long orderId;

    @BeforeEach
    void setUp() {
        PG.reset();
        userId = registration.register().getId();
        Long brandId = brands.save(new BrandModel("나이키", null)).getId();
        productId = products.save(new ProductModel(brandId, "양말", 1_000L, 5)).getId();
        orderId = orderFacade.create(userId, List.of(new OrderFacade.OrderLine(productId, 3))).id();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @AfterAll
    static void stopPg() throws InterruptedException {
        PG.close();
    }

    @ParameterizedTest
    @CsvSource({"SUCCESS, CONFIRMED, CONSUMED, 2", "FAILED, PAYMENT_FAILED, RELEASED, 5"})
    void interruptionAfterBRecoversFromStoredResultWithNewServicesAndClient(
        String result, OrderStatus orderStatus, ReservationStatus reservation, int stockAfter
    ) throws Exception {
        orderFacade.confirm(userId, orderId, command());
        PG.changeStatus("TEST-TX-1", result);
        paymentTransactions.applyLookup(orderId, paymentProvider.findByTransactionKey("TEST-TX-1"),
            PaymentEvent.Source.RECONCILE);
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);

        try (var adapter = newClient()) {
            OrderFacade restarted = newFacade(adapter);
            OrderPaymentInfo recovered = restarted.reconcile(userId, orderId);
            restarted.reconcile(userId, orderId);

            assertThat(recovered.isFinished()).isTrue();
            assertThat(recovered.order().status()).isEqualTo(orderStatus.name());
            assertThat(recovered.order().reservationStatus()).isEqualTo(reservation.name());
            assertThat(products.findById(productId).orElseThrow().getStockQuantity()).isEqualTo(stockAfter);
            assertThat(PG.lookupCalls()).isEqualTo(1);
            assertThat(PG.createCalls()).isEqualTo(1);
            assertThat(PG.transactionCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptionBeforeBUsesStoredProviderOrderIdWithoutResendingCreate(boolean sent) throws Exception {
        var start = paymentTransactions.start(userId, orderId, "attempt-restart", "TEST", "0000-0000-0000-0000");
        String providerOrderId = start.payment().providerOrderId();
        if (sent) {
            paymentProvider.create(new PaymentRequest(providerOrderId, "TEST", "0000-0000-0000-0000", 3_000));
            PG.changeStatus("TEST-TX-1", "SUCCESS");
        }
        assertThat(attempts.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);

        try (var adapter = newClient()) {
            OrderPaymentInfo recovered = newFacade(adapter).reconcile(userId, orderId);

            assertThat(recovered.paymentStatus()).isEqualTo(sent ? "SUCCESS" : "REQUESTED");
            assertThat(recovered.order().status()).isEqualTo(sent ? "CONFIRMED" : "PAYMENT_PENDING");
            assertThat(recovered.order().reservationStatus()).isEqualTo(sent ? "CONSUMED" : "RESERVED");
            assertThat(products.findById(productId).orElseThrow().getStockQuantity()).isEqualTo(2);
            assertThat(PG.createCalls()).isEqualTo(sent ? 1 : 0);
            assertThat(PG.transactionCount()).isEqualTo(sent ? 1 : 0);
            assertThat(PG.lookupCalls()).isEqualTo(1);
            assertThat(PG.requests().getLast().rawQuery()).isEqualTo("orderId=" + providerOrderId);
        }
    }

    private OrderFacade newFacade(TestPgPaymentAdapter adapter) {
        var provider = new ResilientPaymentProvider(adapter, LookupSettingsFixture.LEARNING, Clock.systemUTC());
        return new OrderFacade(beans.getBean(UserService.class), beans.getBean(ProductService.class),
            beans.getBean(OrderService.class), beans.createBean(OrderPaymentTransactions.class), provider, attempts);
    }

    private TestPgPaymentAdapter newClient() {
        return new PaymentProviderConfig().paymentProvider(PG.baseUrl(), "http://127.0.0.1:8080/api/v1/payments/callback",
            Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(500));
    }

    private OrderFacade.PaymentCommand command() {
        return new OrderFacade.PaymentCommand("attempt-restart", "TEST", "0000-0000-0000-0000");
    }

    private static FakePaymentServer startPg() {
        try {
            return new FakePaymentServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
