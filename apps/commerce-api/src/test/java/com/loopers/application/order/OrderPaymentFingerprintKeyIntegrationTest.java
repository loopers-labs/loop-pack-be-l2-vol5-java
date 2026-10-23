package com.loopers.application.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderException;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentFingerprintInput;
import com.loopers.domain.payment.PaymentFingerprintUnavailableException;
import com.loopers.domain.payment.PaymentRequestFingerprint;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.payment.PaymentRequestConflictException;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.payment.HttpTestPgClient;
import com.loopers.infrastructure.payment.PgHttpTimeouts;
import com.loopers.infrastructure.payment.VersionedPaymentRequestFingerprinter;
import com.loopers.infrastructure.payment.support.LocalTestPg;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Synthetic key changes with a retained committed test DB; not an actual deployment restart. */
@SpringBootTest
class OrderPaymentFingerprintKeyIntegrationTest {
    private static final String CARD = "0000-0000-0000-0000";
    @Autowired private OrderPaymentStockService stocks;
    @Autowired private OrderService orders;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @TestBean(methodName = "createFingerprinter") private PaymentRequestFingerprinter fingerprinter;

    private LocalTestPg pg;
    private HttpTestPgClient client;
    private OrderPaymentCoordinator coordinator;

    static PaymentRequestFingerprinter createFingerprinter() {
        return new SwitchableFingerprinter();
    }

    @BeforeEach
    void setUp() throws Exception {
        ((SwitchableFingerprinter) fingerprinter).delegate = keys(1, true);
        initializer.initialize();
        jdbc.update("UPDATE user SET point_balance=10000 WHERE id=1");
        pg = new LocalTestPg();
        client = new HttpTestPgClient(pg.baseUri(), pg.callbackUri(), new ObjectMapper(), PgHttpTimeouts.defaults());
        coordinator = new OrderPaymentCoordinator(stocks, client);
    }

    @AfterEach
    void tearDown() {
        if (pg != null) {
            pg.releaseResponses();
        }
        if (client != null) {
            client.close();
        }
        if (pg != null) {
            pg.close();
        }
        cleanup.truncateAllTables();
    }

    @Test
    void newKeyIsUsedOnlyForNewAttemptsWhileStoredVersionSurvivesCoordinatorRecreation() {
        Fixture first = draft();
        start(first, CARD);
        var original = attempts.findByOrderId(first.id()).orElseThrow().requireRequestFingerprint();
        assertThat(original.keyVersion()).isEqualTo(1);
        var before = snapshot();
        ((SwitchableFingerprinter) fingerprinter).delegate = keys(2, true);
        coordinator = new OrderPaymentCoordinator(stocks, client);

        assertThat(start(first, CARD).paymentStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThatThrownBy(() -> start(first, "1111-1111-1111-1111"))
            .isInstanceOf(PaymentRequestConflictException.class);
        assertThat(snapshot().equals(before)).as("retry preserves all stored facts including fingerprint").isTrue();
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.lookupCalls()).isZero();

        Fixture second = draft();
        start(second, CARD);
        assertThat(attempts.findByOrderId(second.id()).orElseThrow().requireRequestFingerprint().keyVersion()).isEqualTo(2);
        var retained = attempts.findByOrderId(first.id()).orElseThrow().requireRequestFingerprint();
        assertThat(Arrays.equals(retained.digest(), original.digest())).isTrue();
        assertThat(retained.keyVersion()).isEqualTo(1);
        assertThat(pg.createCalls()).isEqualTo(2);
        assertThat(pg.transactionCount()).isEqualTo(2);
    }

    @Test
    void missingOldKeyBlocksComparisonButNotCardlessAuthoritativeRecovery() {
        Fixture fixture = draft();
        start(fixture, CARD);
        ((SwitchableFingerprinter) fingerprinter).delegate = keys(2, false);
        var before = snapshot();

        assertThatThrownBy(() -> start(fixture, CARD)).isInstanceOf(PaymentFingerprintUnavailableException.class);
        assertThat(snapshot().equals(before)).isTrue();
        String transaction = attempts.findByOrderId(fixture.id()).orElseThrow().getTransactionKey();
        pg.setStatus(transaction, "SUCCESS");
        assertThat(coordinator.refreshOnce("alice", fixture.id(), fixture.key()).paymentStatus())
            .isEqualTo(PaymentAttemptStatus.SUCCESS);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.lookupCalls()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, fixture.productId()))
            .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class)).isEqualTo(10000L);
    }

    @Test
    void unavailableCalculatorLeavesNewDraftCompletelyUnchangedAndNeverCallsPg() {
        Fixture fixture = draft();
        ((SwitchableFingerprinter) fingerprinter).delegate = null;
        var before = snapshot();

        assertThatThrownBy(() -> start(fixture, CARD)).isInstanceOf(PaymentFingerprintUnavailableException.class);
        assertThat(snapshot().equals(before)).isTrue();
        assertThat(attempts.findByOrderId(fixture.id())).isEmpty();
        assertThat(pg.createCalls()).isZero();
        assertThat(pg.lookupCalls()).isZero();
    }

    @Test
    void pointHistoryAndOwnershipTakePrecedenceOverPgCardOrKeyRequirements() {
        Fixture fixture = draft();
        orders.confirm("alice", fixture.id());
        ((SwitchableFingerprinter) fingerprinter).delegate = null;
        var before = snapshot();

        assertThatThrownBy(() -> coordinator.startOrReuse("alice", fixture.id(), null, null, null, null))
            .isInstanceOf(OrderException.class).hasMessage("ORDER_ALREADY_CONFIRMED");
        assertThatThrownBy(() -> coordinator.startOrReuse("bob", fixture.id(), null, null, null, null))
            .isInstanceOf(OrderException.class).hasMessage("ORDER_NOT_FOUND");
        assertThat(snapshot().equals(before)).isTrue();
        assertThat(pg.createCalls()).isZero();
        assertThat(pg.lookupCalls()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void startOrReuseRejectsAnOuterTransactionBeforeAnyEffect(boolean existingAttempt) {
        Fixture fixture = draft();
        if (existingAttempt) {
            start(fixture, CARD);
        }
        var before = snapshot();
        var tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> start(fixture, CARD)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Payment HTTP orchestration must start outside a database transaction");
        assertThat(snapshot().equals(before)).isTrue();
        assertThat(pg.createCalls()).isEqualTo(existingAttempt ? 1 : 0);
        assertThat(pg.lookupCalls()).isZero();
    }

    private Fixture draft() {
        var brand = brands.save(new Brand("키 교체 브랜드"));
        var product = products.save(new Product(brand, "상품", 100, 5));
        long id = orders.create("alice", List.of(new OrderQuantities.Item(product.getId(), 2))).orderId();
        return new Fixture(id, product.getId(), UUID.randomUUID());
    }

    private OrderPaymentStockService.AppliedPayment start(Fixture fixture, String card) {
        return coordinator.startOrReuse("alice", fixture.id(), fixture.key(), "KEY-ORDER-" + fixture.id(), "TEST", card);
    }

    private List<List<Map<String, Object>>> snapshot() {
        return List.of(jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            jdbc.queryForList("SELECT order_id, status, HEX(request_fingerprint) AS fingerprint, fingerprint_version,"
                + "key_version, transaction_key, send_started_at FROM payment_attempt ORDER BY id"),
            jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            jdbc.queryForList("SELECT * FROM user ORDER BY id"),
            jdbc.queryForList("SELECT * FROM product_list_revision ORDER BY id"));
    }

    private static PaymentRequestFingerprinter keys(int current, boolean includeOld) {
        byte[] old = new byte[32];
        byte[] fresh = new byte[32];
        Arrays.fill(old, (byte) 31);
        Arrays.fill(fresh, (byte) 67);
        return new VersionedPaymentRequestFingerprinter(current, includeOld ? Map.of(1, old, 2, fresh) : Map.of(2, fresh));
    }

    private record Fixture(long id, long productId, UUID key) {
    }

    private static final class SwitchableFingerprinter implements PaymentRequestFingerprinter {
        private PaymentRequestFingerprinter delegate;

        @Override
        public PaymentRequestFingerprint calculate(PaymentFingerprintInput input) {
            return required().calculate(input);
        }

        @Override
        public boolean matches(PaymentFingerprintInput input, PaymentRequestFingerprint stored) {
            return required().matches(input, stored);
        }

        private PaymentRequestFingerprinter required() {
            if (delegate == null) {
                throw new PaymentFingerprintUnavailableException("Synthetic calculator unavailable");
            }
            return delegate;
        }
    }
}
