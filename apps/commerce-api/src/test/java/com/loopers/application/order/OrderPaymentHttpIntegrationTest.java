package com.loopers.application.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.product.AdminProductService;
import com.loopers.application.product.ProductQueryException;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.order.OrderException;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentCreateRequest;
import com.loopers.domain.payment.PaymentObservationResult;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductListRevisionRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductStockException;
import com.loopers.domain.user.UserRole;
import com.loopers.infrastructure.payment.HttpTestPgClient;
import com.loopers.infrastructure.payment.PgHttpTimeouts;
import com.loopers.infrastructure.payment.support.LocalTestPg;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real HTTP + committed MySQL, without changing the existing customer confirm API. */
@SpringBootTest
class OrderPaymentHttpIntegrationTest {
    @TestBean(methodName = "com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration#create")
    private PaymentRequestFingerprinter fingerprinter;

    @Autowired private OrderPaymentStockService stocks;
    @Autowired private OrderService orders;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private ProductListRevisionRepository revisions;
    @Autowired private AdminProductService adminProducts;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;

    private LocalTestPg pg;
    private HttpTestPgClient client;
    private OrderPaymentCoordinator coordinator;

    @BeforeEach
    void setUp() throws Exception {
        initializer.initialize();
        jdbc.update("UPDATE user SET point_balance=10000 WHERE id=1");
        pg = new LocalTestPg();
        recreateClient(PgHttpTimeouts.defaults());
        assertThat(AopUtils.isAopProxy(stocks)).isTrue();
    }

    @AfterEach
    void tearDown() {
        try {
            if (pg != null) {
                pg.releaseResponses();
            }
            if (client != null) {
                client.close();
            }
            if (pg != null) {
                pg.close();
            }
        } finally {
            cleanup.truncateAllTables();
        }
    }

    @Test
    void pendingAcceptanceHasOneExternalTransactionAndOneHeldReservation() {
        Fixture fixture = draft();
        long revision = revisions.current();

        var result = start(fixture);

        assertThat(result.orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(result.paymentStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertState(fixture, "PAYMENT_PENDING", "PENDING", "HELD", 3);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.transactionCount()).isEqualTo(1);
        assertThat(pg.lookupCalls()).isZero();
        assertThat(pg.requests().getFirst().amount()).isEqualTo(200L);
        assertThat(pg.requests().getFirst().account()).isEqualTo("135135");
        assertThat(revisions.current()).isEqualTo(revision + 1);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentTransaction.Status.class, names = {"SUCCESS", "FAILED"})
    void authoritativeLookupAppliesTheSameTransactionOnce(PaymentTransaction.Status status) {
        Fixture fixture = draft();
        start(fixture);
        String transactionKey = attempts.findByOrderId(fixture.orderId()).orElseThrow().getTransactionKey();
        pg.setStatus(transactionKey, status.name());
        long revision = revisions.current();

        var result = refresh(fixture);

        assertThat(result.observationResult()).isEqualTo(PaymentObservationResult.APPLIED);
        boolean success = status == PaymentTransaction.Status.SUCCESS;
        assertState(fixture, success ? "CONFIRMED" : "PAYMENT_FAILED", status.name(),
            success ? "CONSUMED" : "RELEASED", success ? 3 : 5);
        assertThat(jdbc.queryForObject("SELECT paid_amount FROM `order` WHERE id=?", Long.class, fixture.orderId()))
            .isEqualTo(success ? 200L : null);
        assertThat(revisions.current()).isEqualTo(revision + (success ? 0 : 1));
        assertThat(pg.requests().getLast().rawPath()).isEqualTo("/api/v1/payments/" + transactionKey);
        var terminal = storedState();

        assertThat(refresh(fixture).observationResult()).isEqualTo(PaymentObservationResult.UNCHANGED);
        assertThat(storedState()).isEqualTo(terminal);
        assertThat(pg.lookupCalls()).isEqualTo(1);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.transactionCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentTransaction.Status.class, names = {"SUCCESS", "FAILED"})
    void terminalCreateResponseAppliesTheActuallyCreatedTransaction(PaymentTransaction.Status status) {
        Fixture fixture = draft();
        pg.nextCreateStatus(status.name());

        var result = start(fixture);

        boolean success = status == PaymentTransaction.Status.SUCCESS;
        assertThat(result.paymentStatus()).isEqualTo(PaymentAttemptStatus.valueOf(status.name()));
        assertState(fixture, success ? "CONFIRMED" : "PAYMENT_FAILED", status.name(),
            success ? "CONSUMED" : "RELEASED", success ? 3 : 5);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.transactionCount()).isEqualTo(1);
        assertThat(pg.lookupCalls()).isZero();
        String key = attempts.findByOrderId(fixture.orderId()).orElseThrow().getTransactionKey();
        assertThat(client.findByTransactionKey(key).transactions()).singleElement()
            .satisfies(transaction -> assertThat(transaction.status()).isEqualTo(status));
    }

    @Test
    void strictRejectionHasNoExternalTransactionAndReturnsStockOnlyOnce() {
        Fixture fixture = draft();
        long revision = revisions.current();
        pg.nextCreateMode(LocalTestPg.CreateMode.REJECT_BEFORE_CREATE);

        start(fixture);

        assertState(fixture, "PAYMENT_FAILED", "FAILED", "RELEASED", 5);
        assertThat(attempts.findByOrderId(fixture.orderId()).orElseThrow().getTransactionKey()).isNull();
        assertThat(pg.transactionCount()).isZero();
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(revisions.current()).isEqualTo(revision + 2);
        var terminal = storedState();
        assertThat(stocks.rejectBeforeCreate(fixture.orderId(), fixture.key(), "135135", fixture.providerId(), 200)
            .observationResult()).isEqualTo(PaymentObservationResult.UNCHANGED);
        refresh(fixture);
        assertThat(storedState()).isEqualTo(terminal);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.lookupCalls()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {422, 400, 500})
    void unprovenErrorsKeepReservationAndDoNotBecomeFailedPayment(int status) {
        Fixture fixture = draft();
        pg.replyNext(status, "{}");

        start(fixture);

        assertState(fixture, "PAYMENT_PENDING", "UNKNOWN", "HELD", 3);
        assertThat(pg.transactionCount()).isZero();
        refresh(fixture);
        assertState(fixture, "PAYMENT_PENDING", "UNKNOWN", "HELD", 3);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.lookupCalls()).isEqualTo(1);
    }

    @Test
    void lostResponseRecoversTheStoredOrderWithANewClientWithoutCreatingAgain() throws Exception {
        Fixture fixture = draft();
        pg.nextCreateMode(LocalTestPg.CreateMode.LOSE_RESPONSE_AFTER_CREATE);
        start(fixture);
        assertState(fixture, "PAYMENT_PENDING", "UNKNOWN", "HELD", 3);
        assertThat(pg.transactionCount()).isEqualTo(1);
        assertThat(attempts.findByOrderId(fixture.orderId()).orElseThrow().getTransactionKey()).isNull();

        recreateClient(PgHttpTimeouts.defaults());
        refresh(fixture);
        assertThat(pg.requests().getLast().rawQuery()).isEqualTo("orderId=" + fixture.providerId());
        String transactionKey = attempts.findByOrderId(fixture.orderId()).orElseThrow().getTransactionKey();
        pg.setStatus(transactionKey, "SUCCESS");
        refresh(fixture);

        assertState(fixture, "CONFIRMED", "SUCCESS", "CONSUMED", 3);
        assertThat(attempts.findByOrderId(fixture.orderId()).orElseThrow().getTransactionKey()).isEqualTo(transactionKey);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.transactionCount()).isEqualTo(1);
        assertThat(pg.lookupCalls()).isEqualTo(2);
    }

    @Test
    void actualResponseTimeoutKeepsTheExternalTransactionAndHeldStock() throws Exception {
        Fixture fixture = draft();
        recreateClient(new PgHttpTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(1),
            Duration.ofMillis(150), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(2)));
        pg.nextCreateMode(LocalTestPg.CreateMode.HOLD_RESPONSE_AFTER_CREATE);
        try {
            start(fixture);
            assertState(fixture, "PAYMENT_PENDING", "UNKNOWN", "HELD", 3);
            assertThat(pg.transactionCount()).isEqualTo(1);
            assertThat(pg.createCalls()).isEqualTo(1);
        } finally {
            pg.releaseResponses();
        }
        refresh(fixture);
        assertState(fixture, "PAYMENT_PENDING", "PENDING", "HELD", 3);
        assertThat(pg.createCalls()).isEqualTo(1);
    }

    @Test
    void dispatchRecordWithoutAnExternalTransactionDoesNotAuthorizeResendingAfterRestart() throws Exception {
        Fixture fixture = draft();
        stocks.prepareNew("alice", fixture.orderId(), fixture.key(), "135135", fixture.providerId(),
            "TEST", "0000-0000-0000-0000");
        recreateClient(PgHttpTimeouts.defaults());

        refresh(fixture);

        assertState(fixture, "PAYMENT_PENDING", "UNKNOWN", "HELD", 3);
        assertThatThrownBy(() -> start(fixture)).isInstanceOf(RuntimeException.class);
        assertThat(pg.createCalls()).isZero();
        assertThat(pg.transactionCount()).isZero();
        assertThat(pg.lookupCalls()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"order", "amount", "key", "multiple"})
    void conflictingLookupPreservesStoredFactsWithoutChoosingTheFirstTransaction(String mismatch) {
        Fixture fixture = draft();
        if (mismatch.equals("multiple")) {
            pg.nextCreateMode(LocalTestPg.CreateMode.LOSE_RESPONSE_AFTER_CREATE);
        }
        start(fixture);
        String key = attempts.findByOrderId(fixture.orderId()).orElseThrow().getTransactionKey();
        var before = storedState();
        if (mismatch.equals("multiple")) {
            // The provider does not deduplicate POST. Create a second external transaction without changing our DB.
            client.create(new PaymentCreateRequest(fixture.providerId(), 200, "TEST", "0000-0000-0000-0000"));
        } else {
            pg.replyNext(200, mapper.createObjectNode()
                .put("transactionKey", mismatch.equals("key") ? "OTHER-KEY" : key)
                .put("orderId", mismatch.equals("order") ? "OTHER-ORDER" : fixture.providerId())
                .put("amount", mismatch.equals("amount") ? 201 : 200).put("status", "SUCCESS").toString());
        }

        assertThat(refresh(fixture).observationResult()).isEqualTo(PaymentObservationResult.CONFLICT);

        assertThat(storedState()).isEqualTo(before);
        assertThat(pg.createCalls()).isEqualTo(mismatch.equals("multiple") ? 2 : 1);
        assertThat(pg.transactionCount()).isEqualTo(mismatch.equals("multiple") ? 2 : 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 422})
    void lookupFailureIsNeverTreatedAsPreCreateRejection(int status) {
        Fixture fixture = draft();
        start(fixture);
        pg.replyNext(status, mapper.createObjectNode().put("code", "TEST_PG_REJECTED_BEFORE_CREATE")
            .put("orderId", fixture.providerId()).put("amount", 200).put("transactionCreated", false).toString());

        refresh(fixture);

        assertState(fixture, "PAYMENT_PENDING", "UNKNOWN", "HELD", 3);
        assertThat(pg.transactionCount()).isEqualTo(1);
        assertThat(pg.createCalls()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cardType", "cardNo", "providerId"})
    void invalidTransientInputIsRejectedBeforeReservation(String field) {
        Fixture fixture = draft();
        var before = storedState();

        assertThatThrownBy(() -> coordinator.startNew("alice", fixture.orderId(), fixture.key(),
            field.equals("providerId") ? "short" : fixture.providerId(),
            field.equals("cardType") ? " " : "TEST", field.equals("cardNo") ? null : "0000-0000-0000-0000"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(storedState()).isEqualTo(before);
        assertThat(pg.createCalls()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "stock", "deleted", "point"})
    void existingPreconditionsRejectBeforeAnyExternalEffect(String scenario) {
        Fixture fixture = draft();
        if (scenario.equals("stock")) {
            adminProducts.changeStock(UserRole.ADMIN, fixture.productId(), 1);
        } else if (scenario.equals("deleted")) {
            adminProducts.delete(UserRole.ADMIN, fixture.productId());
        } else if (scenario.equals("point")) {
            orders.confirm("alice", fixture.orderId());
        }
        var before = storedState();

        var failure = assertThatThrownBy(() -> coordinator.startNew(scenario.equals("owner") ? "bob" : "alice",
            fixture.orderId(), fixture.key(), fixture.providerId(), "TEST", "0000-0000-0000-0000"))
            .isInstanceOf(RuntimeException.class);
        switch (scenario) {
            case "owner" -> failure.isInstanceOf(OrderException.class).hasMessage("ORDER_NOT_FOUND");
            case "stock" -> failure.isInstanceOf(ProductStockException.class).hasMessage("INSUFFICIENT_STOCK");
            case "deleted" -> failure.isInstanceOf(ProductQueryException.class).hasMessage("PRODUCT_NOT_FOUND");
            case "point" -> failure.isInstanceOf(OrderException.class).hasMessage("ORDER_ALREADY_CONFIRMED");
            default -> throw new IllegalArgumentException("Unknown test scenario");
        }

        assertThat(storedState()).isEqualTo(before);
        assertThat(pg.createCalls()).isZero();
        assertThat(pg.transactionCount()).isZero();
    }

    @Test
    void anotherOwnerOrAttemptCannotTriggerRecoveryHttp() {
        Fixture fixture = draft();
        start(fixture);
        var before = storedState();

        assertThatThrownBy(() -> coordinator.refreshOnce("bob", fixture.orderId(), fixture.key()))
            .isInstanceOf(OrderException.class).hasMessage("ORDER_NOT_FOUND");
        assertThatThrownBy(() -> coordinator.refreshOnce("alice", fixture.orderId(), UUID.randomUUID()))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(storedState()).isEqualTo(before);
        assertThat(pg.lookupCalls()).isZero();
        assertThat(pg.createCalls()).isEqualTo(1);
    }

    private void recreateClient(PgHttpTimeouts timeouts) throws Exception {
        if (client != null) {
            client.close();
        }
        client = new HttpTestPgClient(pg.baseUri(), pg.callbackUri(), mapper, timeouts);
        coordinator = new OrderPaymentCoordinator(stocks, client);
    }

    private Fixture draft() {
        Product product = products.save(new Product(brands.save(new Brand("HTTP 브랜드")), "상품", 100, 5));
        long orderId = orders.create("alice", List.of(new OrderQuantities.Item(product.getId(), 2))).orderId();
        return new Fixture(product.getId(), orderId, UUID.randomUUID(), "HTTP-ORDER-" + orderId);
    }

    private OrderPaymentStockService.AppliedPayment start(Fixture fixture) {
        return coordinator.startNew("alice", fixture.orderId(), fixture.key(), fixture.providerId(), "TEST", "0000-0000-0000-0000");
    }

    private OrderPaymentStockService.AppliedPayment refresh(Fixture fixture) {
        return coordinator.refreshOnce("alice", fixture.orderId(), fixture.key());
    }

    private void assertState(Fixture fixture, String order, String payment, String reservation, int stock) {
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=?", String.class, fixture.orderId())).isEqualTo(order);
        assertThat(jdbc.queryForObject("SELECT payment_method FROM `order` WHERE id=?", String.class, fixture.orderId())).isEqualTo("PG");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_attempt WHERE order_id=?", String.class, fixture.orderId())).isEqualTo(payment);
        assertThat(jdbc.queryForObject("SELECT reservation_status FROM order_item WHERE order_id=?", String.class, fixture.orderId())).isEqualTo(reservation);
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, fixture.productId())).isEqualTo(stock);
        assertThat(jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class)).isEqualTo(10000L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Long.class)).isEqualTo(1L);
    }

    private Map<String, List<Map<String, Object>>> storedState() {
        return Map.of("orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "attempts", jdbc.queryForList("SELECT order_id,status,transaction_key,send_started_at,amount FROM payment_attempt ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id"),
            "revision", jdbc.queryForList("SELECT * FROM product_list_revision ORDER BY id"));
    }

    private record Fixture(long productId, long orderId, UUID key, String providerId) {
    }
}
