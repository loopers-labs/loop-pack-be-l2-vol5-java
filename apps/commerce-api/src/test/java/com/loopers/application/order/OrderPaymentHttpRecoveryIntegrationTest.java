package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.StockReservation;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.payment.TestPaymentPgServer;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.product.StockReservationJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.interfaces.api.payment.PaymentV1Controller;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "payment.pg.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OrderPaymentHttpRecoveryIntegrationTest {

    private static final String TRANSACTION_KEY = "TEST-TX-1";
    private static TestPaymentPgServer server;

    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private PaymentV1Controller paymentController;
    @Autowired
    private UserJpaRepository users;
    @Autowired
    private ProductJpaRepository products;
    @Autowired
    private OrderJpaRepository orders;
    @Autowired
    private PaymentAttemptJpaRepository attempts;
    @Autowired
    private StockReservationJpaRepository reservations;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @BeforeAll
    static void startServer() throws IOException {
        server = new TestPaymentPgServer();
    }

    @DynamicPropertySource
    static void pgUrl(DynamicPropertyRegistry registry) {
        registry.add("payment.pg.base-url", server::baseUrl);
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @AfterEach
    void cleanUp() {
        databaseCleanUp.truncateAllTables();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED"})
    @DisplayName("불완전한 422, 조회 실패, 범위 밖 금액 뒤에도 예약을 유지하고 정상 조회로 복구한다.")
    void recoversExistingTransactionAfterIncompleteRejection(String malformedStatus) {
        server.reset();
        Long userId = users.save(new User()).getId();
        Product product = new Product(1L, "상품", new Price(1000L));
        product.changeStock(5);
        Long productId = products.save(product).getId();
        Long orderId = orderFacade.createOrder(userId, List.of(new OrderCommand.Item(productId, 2))).orderId();
        String providerOrderId = PaymentAttempt.providerOrderIdOf(orderId);
        OrderCommand.Confirm confirm = new OrderCommand.Confirm("k1", "TEST", "0000-0000-0000-0000");

        server.respondToCreate(422, """
            {"code":"TEST_PG_REJECTED_BEFORE_CREATE","transactionCreated":false,"orderId":"%s"}
            """.formatted(providerOrderId));

        assertThat(orderFacade.confirmOrder(userId, orderId, confirm).attemptStatus()).isEqualTo("UNKNOWN");
        assertThat(orderFacade.confirmOrder(userId, orderId, confirm).attemptStatus()).isEqualTo("UNKNOWN");
        assertThat(assertReserved(orderId, productId).isConflicted()).isFalse();
        assertThat(server.createCount()).isEqualTo(1);

        server.respondToList(providerOrderId, 503, null);
        var unavailable = paymentController.reconcile(userId, orderId);

        assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(unavailable.getBody().data().lookupUnavailable()).isTrue();
        assertThat(assertReserved(orderId, productId).isConflicted()).isFalse();

        server.respondToList(providerOrderId, 200, """
            [{"transactionKey":"%s","orderId":"%s","amount":18446744073709553616,"status":"%s"}]
            """.formatted(TRANSACTION_KEY, providerOrderId, malformedStatus));
        var malformed = paymentController.reconcile(userId, orderId);

        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(malformed.getBody().data().lookupUnavailable()).isTrue();
        assertThat(assertReserved(orderId, productId).isConflicted()).isFalse();
        assertThat(server.createCount()).isEqualTo(1);

        server.respondToList(providerOrderId, 200, """
            [{"transactionKey":"%s","orderId":"%s","amount":2001,"status":"SUCCESS"}]
            """.formatted(TRANSACTION_KEY, providerOrderId));
        var conflicting = paymentController.reconcile(userId, orderId);

        assertThat(conflicting.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(conflicting.getBody().data().lookupUnavailable()).isTrue();
        PaymentAttempt conflict = assertReserved(orderId, productId);
        assertThat(conflict.isConflicted()).isTrue();
        assertThat(conflict.getConflictReason()).isEqualTo(PaymentAttempt.ConflictReason.AMOUNT_MISMATCH);

        server.respondToList(providerOrderId, 200, """
            [{"transactionKey":"%s","orderId":"%s","amount":2000,"status":"SUCCESS"}]
            """.formatted(TRANSACTION_KEY, providerOrderId));
        var recovered = paymentController.reconcile(userId, orderId);

        assertThat(recovered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recovered.getBody().data().attemptStatus()).isEqualTo("SUCCESS");
        PaymentAttempt attempt = attempts.findByOrderId(orderId).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttempt.Status.SUCCESS);
        assertThat(attempt.getTransactionKey()).isEqualTo(TRANSACTION_KEY);
        Order order = orders.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
        assertThat(order.getPaidAmount()).isEqualTo(2000L);
        assertThat(reservations.findAllByOrderId(orderId)).singleElement()
            .extracting(StockReservation::getStatus).isEqualTo(StockReservation.Status.CONSUMED);
        assertThat(products.findById(productId).orElseThrow().getStock().getQuantity()).isEqualTo(3);
        assertThat(server.createCount()).isEqualTo(1);
        assertThat(server.receivedRequests()).filteredOn(request -> "GET".equals(request.method()))
            .extracting(request -> request.query().get("orderId"))
            .containsExactly(providerOrderId, providerOrderId, providerOrderId, providerOrderId);
    }

    private PaymentAttempt assertReserved(Long orderId, Long productId) {
        PaymentAttempt attempt = attempts.findByOrderId(orderId).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttempt.Status.UNKNOWN);
        assertThat(attempt.getTransactionKey()).isNull();
        assertThat(attempt.getFailureReason()).isNull();
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
        assertThat(reservations.findAllByOrderId(orderId)).singleElement()
            .extracting(StockReservation::getStatus).isEqualTo(StockReservation.Status.RESERVED);
        assertThat(products.findById(productId).orElseThrow().getStock().getQuantity()).isEqualTo(3);
        return attempt;
    }
}
