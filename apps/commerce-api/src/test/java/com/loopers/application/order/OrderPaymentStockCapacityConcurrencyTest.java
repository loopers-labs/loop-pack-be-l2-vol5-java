package com.loopers.application.order;

import com.loopers.application.product.AdminProductInfo;
import com.loopers.application.product.AdminProductService;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentObservationResult;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.domain.product.ProductStockException;
import com.loopers.domain.user.UserRole;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 서비스 트랜잭션끼리 경합시켜 관리자 가용 재고 설정이 최신 HELD 합계를 사용하는지 검증한다.
 * 카드 요청 동일성이나 PG HTTP 발송을 검증하는 테스트는 아니다.
 */
@SpringBootTest
@Import(OrderPaymentStockConcurrencyTest.SqlCaptureConfiguration.class)
class OrderPaymentStockCapacityConcurrencyTest {

    private static final String ACCOUNT = "135135";

    @TestBean(methodName = "com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration#create")
    private PaymentRequestFingerprinter fingerprinter;

    @Autowired private OrderPaymentStockService payments;
    @Autowired private OrderService orders;
    @Autowired private AdminProductService products;
    @Autowired private BrandRepository brands;
    @Autowired private FixtureUserInitializer users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private OrderPaymentStockConcurrencyTest.SqlCapture capture;

    private long brandId;
    private boolean workersTerminated = true;

    @BeforeEach
    void setUp() {
        users.initialize();
        brandId = brands.save(new Brand("예약 반환 여유 경합 브랜드")).getId();
        assertThat(AopUtils.isAopProxy(payments)).isTrue();
        assertThat(AopUtils.isAopProxy(products)).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @AfterEach
    void tearDown() {
        assertThat(workersTerminated).as("모든 worker 종료 후에만 DB를 정리한다").isTrue();
        cleanup.truncateAllTables();
        capture.clear();
    }

    @Test
    void preparationAndAdminSettingPreserveOnlyTheTwoSerialCapacityResults() throws Exception {
        Fixture fixture = draft();
        long beforeRevision = revision();
        var beforeUsers = userRows();
        var beforeItems = itemSnapshots();

        List<Outcome> outcomes = together(() -> prepare(fixture),
            () -> products.changeStock(UserRole.ADMIN, fixture.productId(), Integer.MAX_VALUE - 1));

        assertThat(outcomes.get(0).failure()).as("신규 확보는 두 직렬 순서 모두 성공한다").isNull();
        var prepared = (OrderPaymentStockService.PreparedPayment) outcomes.get(0).value();
        assertThat(prepared.orderId()).isEqualTo(fixture.orderId());
        assertThat(prepared.attemptKey()).isEqualTo(fixture.key());
        assertThat(prepared.amount()).isEqualTo(2000L);
        boolean adminSucceeded = assertAdminOutcome(outcomes.get(1), fixture.productId(), Integer.MAX_VALUE - 1);

        assertStock(fixture.productId(), adminSucceeded ? Integer.MAX_VALUE - 3 : 3);
        assertState(fixture, "PAYMENT_PENDING", "HELD", "NOT_OBSERVED");
        assertThat(heldQuantity(fixture.productId())).isEqualTo(2L);
        assertThat(revision()).isEqualTo(beforeRevision + (adminSucceeded ? 2 : 1));
        assertThat(userRows()).isEqualTo(beforeUsers);
        assertThat(itemSnapshots()).isEqualTo(beforeItems);
    }

    @Test
    void failureReturnAndAdminMaximumSettingUseTheCommittedReservationTotal() throws Exception {
        Fixture fixture = prepared();
        long beforeRevision = revision();
        var beforeUsers = userRows();
        var beforeItems = itemSnapshots();

        List<Outcome> outcomes = together(() -> apply(fixture, PaymentTransaction.Status.FAILED),
            () -> products.changeStock(UserRole.ADMIN, fixture.productId(), Integer.MAX_VALUE));

        assertApplied(outcomes.get(0), false);
        boolean adminSucceeded = assertAdminOutcome(outcomes.get(1), fixture.productId(), Integer.MAX_VALUE);

        assertStock(fixture.productId(), adminSucceeded ? Integer.MAX_VALUE : 5);
        assertState(fixture, "PAYMENT_FAILED", "RELEASED", "FAILED");
        assertThat(heldQuantity(fixture.productId())).isZero();
        assertThat(revision()).isEqualTo(beforeRevision + (adminSucceeded ? 2 : 1));
        assertThat(userRows()).isEqualTo(beforeUsers);
        assertThat(itemSnapshots()).isEqualTo(beforeItems);
    }

    @Test
    void successConsumptionAndAdminMaximumSettingUseTheCommittedReservationTotal() throws Exception {
        Fixture fixture = prepared();
        long beforeRevision = revision();
        var beforeUsers = userRows();
        var beforeItems = itemSnapshots();

        List<Outcome> outcomes = together(() -> apply(fixture, PaymentTransaction.Status.SUCCESS),
            () -> products.changeStock(UserRole.ADMIN, fixture.productId(), Integer.MAX_VALUE));

        assertApplied(outcomes.get(0), true);
        boolean adminSucceeded = assertAdminOutcome(outcomes.get(1), fixture.productId(), Integer.MAX_VALUE);

        assertStock(fixture.productId(), adminSucceeded ? Integer.MAX_VALUE : 3);
        assertState(fixture, "CONFIRMED", "CONSUMED", "SUCCESS");
        assertThat(heldQuantity(fixture.productId())).isZero();
        assertThat(revision()).isEqualTo(beforeRevision + (adminSucceeded ? 1 : 0));
        assertThat(userRows()).isEqualTo(beforeUsers);
        assertThat(itemSnapshots()).isEqualTo(beforeItems);
    }

    @Test
    void adminAggregatesHeldRowsAfterItsProductWriteLockWithoutLockingOrdersInReverse() {
        Fixture fixture = prepared();
        capture.clear();

        products.changeStock(UserRole.ADMIN, fixture.productId(), Integer.MAX_VALUE - 2);

        List<String> statements = capture.statements().stream()
            .map(sql -> sql.toLowerCase(Locale.ROOT).replace("`", "").replaceAll("\\s+", " "))
            .toList();
        int brandLock = indexOf(statements, sql -> sql.contains(" from brand ") && sql.contains("for update"));
        int productLock = indexOf(statements, sql -> sql.contains(" from product ") && sql.contains("for update"));
        int heldSum = indexOf(statements, sql -> sql.contains("sum(") && sql.contains(" from order_item "));
        int productUpdate = indexOf(statements, sql -> sql.startsWith("update product "));

        assertThat(brandLock).as("관리자 기존 브랜드 잠금").isGreaterThanOrEqualTo(0);
        assertThat(productLock).as("브랜드 다음 상품 쓰기 잠금").isGreaterThan(brandLock);
        assertThat(heldSum).as("상품 잠금을 얻은 뒤 HELD를 집계").isGreaterThan(productLock);
        assertThat(statements.get(heldSum)).contains("reservation_status").doesNotContain("for update", "for share");
        assertThat(productUpdate).as("집계·검증 뒤 상품 수량 변경").isGreaterThan(heldSum);
        assertThat(statements).noneMatch(sql -> sql.contains(" from order ") && sql.contains("for update"));
        assertThat(statements.get(statements.size() - 1)).as("업무 SQL 뒤 마지막에 revision 변경")
            .contains("insert into product_list_revision");
        assertStock(fixture.productId(), Integer.MAX_VALUE - 2);
        assertThat(heldQuantity(fixture.productId())).isEqualTo(2L);
    }

    private Fixture draft() {
        long productId = products.create(UserRole.ADMIN, brandId, "상품", 1000, 5).productId();
        long orderId = orders.create("alice", List.of(new OrderQuantities.Item(productId, 2))).orderId();
        return new Fixture(productId, orderId, UUID.randomUUID());
    }

    private Fixture prepared() {
        Fixture fixture = draft();
        prepare(fixture);
        return fixture;
    }

    private OrderPaymentStockService.PreparedPayment prepare(Fixture fixture) {
        return payments.prepareNew("alice", fixture.orderId(), fixture.key(), ACCOUNT, providerOrderId(fixture),
            "TEST", "0000-0000-0000-0000");
    }

    private OrderPaymentStockService.AppliedPayment apply(Fixture fixture, PaymentTransaction.Status status) {
        PaymentTransaction observed = new PaymentTransaction(ACCOUNT, providerOrderId(fixture),
            "capacity-tx-" + fixture.orderId(), 2000L, status);
        return payments.apply(fixture.orderId(), fixture.key(), observed);
    }

    private String providerOrderId(Fixture fixture) {
        return "LP-CAPACITY-" + fixture.orderId();
    }

    private boolean assertAdminOutcome(Outcome outcome, long productId, int proposed) {
        if (outcome.failure() == null) {
            AdminProductInfo info = (AdminProductInfo) outcome.value();
            assertThat(info.productId()).isEqualTo(productId);
            assertThat(info.stockQuantity()).isEqualTo(proposed);
            return true;
        }
        assertThat(outcome.failure()).as("기술 오류·락 시간 초과는 허용 결과가 아니다")
            .isExactlyInstanceOf(ProductStockException.class)
            .satisfies(error -> assertThat(((ProductStockException) error).getReason())
                .isEqualTo(ProductStockException.Reason.STOCK_CAPACITY_EXCEEDED));
        return false;
    }

    private void assertApplied(Outcome outcome, boolean success) {
        assertThat(outcome.failure()).as("예약 종결은 두 직렬 순서 모두 성공해야 한다").isNull();
        var result = (OrderPaymentStockService.AppliedPayment) outcome.value();
        assertThat(result.observationResult()).isEqualTo(PaymentObservationResult.APPLIED);
        assertThat(result.orderStatus()).isEqualTo(success ? OrderStatus.CONFIRMED : OrderStatus.PAYMENT_FAILED);
        assertThat(result.paymentStatus()).isEqualTo(success ? PaymentAttemptStatus.SUCCESS : PaymentAttemptStatus.FAILED);
    }

    private void assertState(Fixture fixture, String orderStatus, String reservation, String paymentStatus) {
        var order = jdbc.queryForMap("SELECT * FROM `order` WHERE id=?", fixture.orderId());
        assertThat(order.get("status")).isEqualTo(orderStatus);
        assertThat(order.get("payment_method")).isEqualTo("PG");
        assertThat(order.get("total_amount")).isEqualTo(2000L);
        assertThat(order.get("paid_amount")).isEqualTo(orderStatus.equals("CONFIRMED") ? 2000L : null);
        if (orderStatus.equals("CONFIRMED")) {
            assertThat(order.get("confirmed_at")).isNotNull();
        } else {
            assertThat(order.get("confirmed_at")).isNull();
        }
        assertThat(jdbc.queryForObject("SELECT reservation_status FROM order_item WHERE order_id=?",
            String.class, fixture.orderId())).isEqualTo(reservation);
        assertThat(jdbc.queryForObject("SELECT quantity FROM order_item WHERE order_id=?",
            Integer.class, fixture.orderId())).isEqualTo(2);
        var attempt = jdbc.queryForMap("SELECT * FROM payment_attempt WHERE order_id=?", fixture.orderId());
        assertThat(attempt.get("status")).isEqualTo(paymentStatus);
        assertThat(attempt.get("amount")).isEqualTo(2000L);
        assertThat(attempt.get("provider_account")).isEqualTo(ACCOUNT);
        assertThat(attempt.get("provider_order_id")).isEqualTo(providerOrderId(fixture));
        assertThat(attempt.get("send_started_at")).isNotNull();
        assertThat(attempt.get("transaction_key"))
            .isEqualTo(paymentStatus.equals("NOT_OBSERVED") ? null : "capacity-tx-" + fixture.orderId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE order_id=?",
            Long.class, fixture.orderId())).isEqualTo(1L);
    }

    private void assertStock(long productId, int expected) {
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, productId))
            .isEqualTo(expected);
    }

    private long heldQuantity(long productId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM order_item"
            + " WHERE product_id=? AND reservation_status='HELD'", Long.class, productId);
    }

    private long revision() {
        return jdbc.queryForObject("SELECT revision FROM product_list_revision WHERE id=1", Long.class);
    }

    private List<Map<String, Object>> userRows() {
        return jdbc.queryForList("SELECT * FROM user ORDER BY id");
    }

    private List<Map<String, Object>> itemSnapshots() {
        return jdbc.queryForList("SELECT id,order_id,product_id,product_name_snapshot,unit_price_snapshot,quantity"
            + " FROM order_item ORDER BY id");
    }

    private int indexOf(List<String> statements, Predicate<String> matches) {
        for (int index = 0; index < statements.size(); index++) {
            if (matches.test(statements.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private List<Outcome> together(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        List<Future<Outcome>> futures = new ArrayList<>();
        workersTerminated = false;
        try {
            for (Callable<?> action : List.of(first, second)) {
                futures.add(executor.submit(() -> invoke(action, ready, start)));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Outcome> results = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            workersTerminated = executor.awaitTermination(10, TimeUnit.SECONDS);
            assertThat(workersTerminated).as("DB 재조회 전에 worker를 모두 종료한다").isTrue();
        }
    }

    private Outcome invoke(Callable<?> action, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Capacity concurrency start timed out");
            }
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new Outcome(action.call(), null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Outcome(null, exception);
        } catch (Exception exception) {
            return new Outcome(null, exception);
        } finally {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        }
    }

    private record Fixture(long productId, long orderId, UUID key) {
    }

    private record Outcome(Object value, Exception failure) {
    }
}
