package com.loopers.application.order;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductListRevisionRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest
class OrderPaymentStockRollbackTest {
    @TestBean(methodName = "com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration#create")
    private PaymentRequestFingerprinter fingerprinter;

    @Autowired private OrderService orders;
    @Autowired private OrderPaymentStockService payments;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    @MockitoSpyBean private ProductListRevisionRepository revisions;

    @BeforeEach
    void initialize() {
        initializer.initialize();
    }

    @AfterEach
    void clean() {
        ProductListRevisionRepository target = AopTestUtils.getUltimateTargetObject(revisions);
        reset(target);
        cleanup.truncateAllTables();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serviceRollsBackRealBusinessAndRevisionSqlThenAllowsRetry(boolean returning) {
        Product product = products.save(new Product(brands.save(new Brand("브랜드")), "상품", 100, 5));
        long orderId = orders.create("alice", List.of(new OrderQuantities.Item(product.getId(), 2))).orderId();
        UUID key = UUID.randomUUID();
        if (returning) {
            payments.prepareNew("alice", orderId, key, "135135", "order-" + orderId, "TEST", "0000-0000-0000-0000");
        }
        Map<String, List<Map<String, Object>>> before = storedState();
        long revision = revisions.current();
        AtomicBoolean observedWrite = new AtomicBoolean();
        ProductListRevisionRepository target = AopTestUtils.getUltimateTargetObject(revisions);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(revisions.current()).isEqualTo(revision + 1);
            assertThat(storedState()).isNotEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class,
                product.getId())).isEqualTo(returning ? 5 : 3);
            assertThat(jdbc.queryForObject("SELECT reservation_status FROM order_item WHERE order_id=?", String.class,
                orderId)).isEqualTo(returning ? "RELEASED" : "HELD");
            observedWrite.set(true);
            throw new IllegalStateException("test-only failure after real business and revision SQL");
        }).when(target).advance();

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThatThrownBy(() -> mutate(returning, orderId, key)).isInstanceOf(RuntimeException.class);

        assertThat(observedWrite).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(storedState()).isEqualTo(before);
        assertThat(revisions.current()).isEqualTo(revision);
        reset(target);
        mutate(returning, orderId, key);
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class,
            product.getId())).isEqualTo(returning ? 5 : 3);
        assertThat(revisions.current()).isEqualTo(revision + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class)).isZero();
    }

    private void mutate(boolean returning, long orderId, UUID key) {
        if (returning) {
            payments.apply(orderId, key, new PaymentTransaction("135135", "order-" + orderId,
                "transaction-" + orderId, 200, PaymentTransaction.Status.FAILED));
        } else {
            payments.prepareNew("alice", orderId, key, "135135", "order-" + orderId, "TEST", "0000-0000-0000-0000");
        }
    }

    private Map<String, List<Map<String, Object>>> storedState() {
        return Map.of("orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "attempts", jdbc.queryForList("SELECT id,order_id,HEX(attempt_key),provider_account,provider_order_id,"
                + "amount,status,send_started_at,transaction_key FROM payment_attempt ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id"));
    }
}
