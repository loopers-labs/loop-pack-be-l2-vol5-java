package com.loopers.application.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.product.AdminProductService;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderException;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentFingerprintInput;
import com.loopers.domain.payment.PaymentFingerprintUnavailableException;
import com.loopers.domain.payment.PaymentObservationResult;
import com.loopers.domain.payment.PaymentRequestConflictException;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.UserRole;
import com.loopers.infrastructure.payment.HttpTestPgClient;
import com.loopers.infrastructure.payment.PgHttpTimeouts;
import com.loopers.infrastructure.payment.support.LocalTestPg;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Synthetic HMAC keys, real loopback HTTP, and committed isolated test DB state. No customer API claim. */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
@Timeout(20)
class OrderPaymentFingerprintIntegrationTest {
    private static final String CARD = "0000-0000-0000-0000";
    private static final String OTHER_CARD = "1111-1111-1111-1111";

    @Autowired private OrderPaymentStockService stocks;
    @Autowired private OrderService orders;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private AdminProductService adminProducts;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private JdbcTemplate jdbc;

    @TestBean(methodName = "com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration#create")
    private PaymentRequestFingerprinter fingerprinter;

    private LocalTestPg pg;
    private HttpTestPgClient client;
    private OrderPaymentCoordinator coordinator;

    @BeforeEach
    void setUp() throws Exception {
        initializer.initialize();
        jdbc.update("UPDATE user SET point_balance=10000 WHERE id=1");
        assertThat(AopUtils.isAopProxy(stocks)).as("TX A/B Spring proxy").isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        pg = new LocalTestPg();
        recreateClient();
    }

    @AfterEach
    void tearDown(CapturedOutput output) {
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
        // Boolean assertions prevent a failure from copying sensitive captured output into a report.
        assertThat(output.getAll().contains(CARD)).as("synthetic card is absent from logs").isFalse();
        assertThat(output.getAll().contains(OTHER_CARD)).as("changed synthetic card is absent from logs").isFalse();
    }

    @Test
    void newPaymentCommitsFingerprintVersionsAndReservationBeforeReturning() {
        Fixture fixture = draft();

        var result = start(fixture);

        var attempt = attempts.findByOrderId(fixture.orderId()).orElseThrow();
        var stored = attempt.requireRequestFingerprint();
        var expected = fingerprinter.calculate(new PaymentFingerprintInput(1, fixture.orderId(), 200, "TEST", CARD));
        assertThat(MessageDigest.isEqual(stored.digest(), expected.digest())).as("persisted HMAC matches resolved owner and stored amount").isTrue();
        assertThat(stored.fingerprintVersion()).isEqualTo(expected.fingerprintVersion());
        assertThat(stored.keyVersion()).isEqualTo(expected.keyVersion());
        assertThat(jdbc.queryForObject("SELECT OCTET_LENGTH(request_fingerprint) FROM payment_attempt WHERE order_id=?",
            Integer.class, fixture.orderId())).isEqualTo(32);
        assertThat(attempt.getSendStartedAt()).isNotNull();
        assertThat(result.orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(result.paymentStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertState(fixture, "PAYMENT_PENDING", "PENDING", "HELD", 3);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.lookupCalls()).isZero();
        assertThat(pg.transactionCount()).isEqualTo(1);
        assertThat(pg.requests().getFirst().cardMatchesExpected()).isTrue();
        assertThat(pg.requests().getFirst().amount()).isEqualTo(200);
        assertThat(storedState().toString().contains(CARD)).as("no card value persisted in the inspected business rows").isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "PENDING,PAYMENT_PENDING,PENDING,HELD,3,1",
        "UNKNOWN,PAYMENT_PENDING,UNKNOWN,HELD,3,1",
        "SUCCESS,CONFIRMED,SUCCESS,CONSUMED,3,1",
        "FAILED,PAYMENT_FAILED,FAILED,RELEASED,5,1",
        "REJECTED,PAYMENT_FAILED,FAILED,RELEASED,5,0"
    })
    void matchingRetryReturnsStoredStatusWithoutHttpOrEffects(String external, String order, String payment,
                                                              String reservation, int stock, int transactions) throws Exception {
        Fixture fixture = draft();
        arrangeResponse(external);
        start(fixture);
        var before = storedState();
        recreateClient();

        var result = start(fixture);

        assertThat(result.orderStatus()).isEqualTo(OrderStatus.valueOf(order));
        assertThat(result.paymentStatus()).isEqualTo(PaymentAttemptStatus.valueOf(payment));
        assertThat(result.observationResult()).isEqualTo(PaymentObservationResult.UNCHANGED);
        assertUnchanged(before);
        assertState(fixture, order, payment, reservation, stock);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.lookupCalls()).isZero();
        assertThat(pg.transactionCount()).isEqualTo(transactions);
    }

    @ParameterizedTest
    @CsvSource({"PENDING,card", "PENDING,type", "SUCCESS,card", "SUCCESS,type", "FAILED,card", "FAILED,type"})
    void changedContentConflictsEvenAfterTerminalResult(String external, String changed) {
        Fixture fixture = draft();
        arrangeResponse(external);
        start(fixture);
        var before = storedState();

        assertThatThrownBy(() -> coordinator.startOrReuse("alice", fixture.orderId(), fixture.key(), fixture.providerId(),
            changed.equals("type") ? "OTHER_TEST" : "TEST", changed.equals("card") ? OTHER_CARD : CARD))
            .isInstanceOf(PaymentRequestConflictException.class);

        assertUnchanged(before);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.lookupCalls()).isZero();
        assertThat(pg.transactionCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"same-order-new-key", "other-order-same-key", "different-provider-order"})
    void conflictingIdentitiesCannotStartAnotherPayment(String scenario) {
        Fixture fixture = draft();
        start(fixture);
        Fixture target = scenario.equals("other-order-same-key") ? draft() : fixture;
        UUID key = scenario.equals("same-order-new-key") ? UUID.randomUUID() : fixture.key();
        String provider = scenario.equals("different-provider-order") ? "OTHER-PROVIDER-ORDER" : target.providerId();
        var before = storedState();

        assertThatThrownBy(() -> coordinator.startOrReuse("alice", target.orderId(), key, provider, "TEST", CARD))
            .isInstanceOf(PaymentRequestConflictException.class);

        assertUnchanged(before);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.lookupCalls()).isZero();
        assertThat(pg.transactionCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Long.class)).isEqualTo(1L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ownershipIsCheckedBeforeFingerprintConflictOrUnavailable(boolean legacy) {
        Fixture fixture = draft();
        start(fixture);
        if (legacy) {
            eraseFingerprintForLegacyFixture(fixture);
        }
        var before = storedState();

        assertThatThrownBy(() -> coordinator.startOrReuse("bob", fixture.orderId(), fixture.key(),
            fixture.providerId(), "TEST", OTHER_CARD))
            .isInstanceOf(OrderException.class).hasMessage("ORDER_NOT_FOUND");

        assertUnchanged(before);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.lookupCalls()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"price", "deleted"})
    void retryUsesStoredOrderFactsInsteadOfCurrentProductState(String change) {
        Fixture fixture = draft();
        start(fixture);
        if (change.equals("price")) {
            adminProducts.update(UserRole.ADMIN, fixture.productId(), "changed product", 999);
        } else {
            adminProducts.delete(UserRole.ADMIN, fixture.productId());
        }
        var before = storedState();

        var result = start(fixture);

        assertThat(result.paymentStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertUnchanged(before);
        assertThat(attempts.findByOrderId(fixture.orderId()).orElseThrow().getAmount()).isEqualTo(200);
        assertThat(fingerprinter.matches(new PaymentFingerprintInput(1, fixture.orderId(), 200, "TEST", CARD),
            attempts.findByOrderId(fixture.orderId()).orElseThrow().requireRequestFingerprint())).isTrue();
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.lookupCalls()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy", "key-version", "format-version"})
    void missingComparisonMaterialPreservesStateAndCardFreeRecoveryStillWorks(String missing) {
        Fixture fixture = draft();
        start(fixture);
        String transactionKey = attempts.findByOrderId(fixture.orderId()).orElseThrow().getTransactionKey();
        if (missing.equals("legacy")) {
            eraseFingerprintForLegacyFixture(fixture);
        } else if (missing.equals("key-version")) {
            jdbc.update("UPDATE payment_attempt SET key_version=? WHERE order_id=?", Integer.MAX_VALUE, fixture.orderId());
        } else {
            jdbc.update("UPDATE payment_attempt SET fingerprint_version=? WHERE order_id=?", Integer.MAX_VALUE, fixture.orderId());
        }
        var before = storedState();

        assertThatThrownBy(() -> start(fixture)).isInstanceOf(PaymentFingerprintUnavailableException.class);

        assertUnchanged(before);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.lookupCalls()).isZero();
        pg.setStatus(transactionKey, "SUCCESS");
        var result = coordinator.refreshOnce("alice", fixture.orderId(), fixture.key());
        assertThat(result.paymentStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
        assertState(fixture, "CONFIRMED", "SUCCESS", "CONSUMED", 3);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.lookupCalls()).isEqualTo(1);
        if (missing.equals("legacy")) {
            assertThat(attempts.findByOrderId(fixture.orderId()).orElseThrow().getRequestFingerprint()).isEmpty();
        } else {
            String column = missing.equals("key-version") ? "key_version" : "fingerprint_version";
            assertThat(jdbc.queryForObject("SELECT " + column + " FROM payment_attempt WHERE order_id=?",
                Integer.class, fixture.orderId())).isEqualTo(Integer.MAX_VALUE);
        }
        var terminal = storedState();
        assertThatThrownBy(() -> start(fixture)).isInstanceOf(PaymentFingerprintUnavailableException.class);
        assertUnchanged(terminal);
    }

    @Test
    void preparedButNotSentRetryCannotInventPermissionToCreate() {
        Fixture fixture = draft();
        stocks.prepareNew("alice", fixture.orderId(), fixture.key(), "135135", fixture.providerId(), "TEST", CARD);
        var before = storedState();

        var result = start(fixture);

        assertThat(result.orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(result.paymentStatus()).isEqualTo(PaymentAttemptStatus.NOT_OBSERVED);
        assertUnchanged(before);
        assertState(fixture, "PAYMENT_PENDING", "NOT_OBSERVED", "HELD", 3);
        assertThat(pg.createCalls()).isZero();
        assertThat(pg.lookupCalls()).isZero();
        assertThat(pg.transactionCount()).isZero();
    }

    @Test
    void existingPointPaymentKeepsItsOldReconfirmationErrorWithoutPgEffect() {
        Fixture fixture = draft();
        orders.confirm("alice", fixture.orderId());
        var before = storedState();

        assertThatThrownBy(() -> start(fixture)).isInstanceOf(OrderException.class).hasMessage("ORDER_ALREADY_CONFIRMED");

        assertUnchanged(before);
        assertThat(jdbc.queryForObject("SELECT payment_method FROM `order` WHERE id=?", String.class, fixture.orderId())).isEqualTo("POINT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Long.class)).isZero();
        assertThat(pg.createCalls()).isZero();
        assertThat(pg.lookupCalls()).isZero();
    }

    private void arrangeResponse(String external) {
        switch (external) {
            case "UNKNOWN" -> pg.nextCreateMode(LocalTestPg.CreateMode.LOSE_RESPONSE_AFTER_CREATE);
            case "REJECTED" -> pg.nextCreateMode(LocalTestPg.CreateMode.REJECT_BEFORE_CREATE);
            default -> pg.nextCreateStatus(external);
        }
    }

    private void recreateClient() throws Exception {
        if (client != null) {
            client.close();
        }
        client = new HttpTestPgClient(pg.baseUri(), pg.callbackUri(), new ObjectMapper(), PgHttpTimeouts.defaults());
        coordinator = new OrderPaymentCoordinator(stocks, client);
    }

    private Fixture draft() {
        Product product = products.save(new Product(brands.save(new Brand("fingerprint brand")), "product", 100, 5));
        long orderId = orders.create("alice", List.of(new OrderQuantities.Item(product.getId(), 2))).orderId();
        return new Fixture(product.getId(), orderId, UUID.randomUUID(), "FINGERPRINT-ORDER-" + orderId);
    }

    private OrderPaymentStockService.AppliedPayment start(Fixture fixture) {
        return coordinator.startOrReuse("alice", fixture.orderId(), fixture.key(), fixture.providerId(), "TEST", CARD);
    }

    private void eraseFingerprintForLegacyFixture(Fixture fixture) {
        jdbc.update("UPDATE payment_attempt SET request_fingerprint=NULL, fingerprint_version=NULL, key_version=NULL WHERE order_id=?",
            fixture.orderId());
    }

    private void assertState(Fixture fixture, String order, String payment, String reservation, int stock) {
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=?", String.class, fixture.orderId())).isEqualTo(order);
        assertThat(jdbc.queryForObject("SELECT payment_method FROM `order` WHERE id=?", String.class, fixture.orderId())).isEqualTo("PG");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_attempt WHERE order_id=?", String.class, fixture.orderId())).isEqualTo(payment);
        assertThat(jdbc.queryForObject("SELECT reservation_status FROM order_item WHERE order_id=?", String.class, fixture.orderId())).isEqualTo(reservation);
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, fixture.productId())).isEqualTo(stock);
        assertThat(jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class)).isEqualTo(10000L);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private void assertUnchanged(Map<String, List<Map<String, Object>>> before) {
        assertThat(storedState().equals(before)).as("committed DB snapshot, including fingerprint, remains unchanged").isTrue();
    }

    private Map<String, List<Map<String, Object>>> storedState() {
        return Map.of("orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "attempts", jdbc.queryForList("SELECT order_id,status,transaction_key,send_started_at,amount,"
                + "HEX(request_fingerprint) AS fingerprint,fingerprint_version,key_version FROM payment_attempt ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id"),
            "revision", jdbc.queryForList("SELECT * FROM product_list_revision ORDER BY id"));
    }

    private record Fixture(long productId, long orderId, UUID key, String providerId) { }
}
