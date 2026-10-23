package com.loopers.application.order;

import com.loopers.application.brand.AdminBrandService;
import com.loopers.application.product.AdminProductService;
import com.loopers.application.product.ProductQueryException;
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
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP confirm이 아닌 내부 신규 준비·결과 적용의 독립 DB 트랜잭션 경합을 검증한다.
 * 카드 동일성·실제 PG 발송 1회까지 검증하는 테스트는 아니다.
 */
@SpringBootTest
@Import(OrderPaymentStockConcurrencyTest.SqlCaptureConfiguration.class)
class OrderPaymentStockConcurrencyTest {
    private static final String ACCOUNT = "135135";

    @TestBean(methodName = "com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration#create")
    private PaymentRequestFingerprinter fingerprinter;

    @Autowired private OrderPaymentStockService payments;
    @Autowired private OrderService orders;
    @Autowired private AdminProductService products;
    @Autowired private AdminBrandService adminBrands;
    @Autowired private BrandRepository brands;
    @Autowired private FixtureUserInitializer users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private SqlCapture capture;

    private long brandId;
    private boolean workersTerminated = true;

    @BeforeEach
    void setUp() {
        users.initialize();
        brandId = brands.save(new Brand("PG 재고 경합 브랜드")).getId();
        assertThat(AopUtils.isAopProxy(payments)).as("실제 Spring TX 프록시 사용").isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @AfterEach
    void tearDown() {
        assertThat(workersTerminated).as("worker 종료 전 DB를 정리하지 않는다").isTrue();
        cleanup.truncateAllTables();
        capture.clear();
    }

    @Test
    @DisplayName("동일 주문 신규 준비 경합: 한 실행만 준비하고 다른 실행은 추가 확보 없이 거절한다")
    void newOnlyPreparationOfSameOrderReservesOnce() throws Exception {
        long productId = product(5);
        long orderId = draft("alice", productId, 2);
        UUID key = UUID.randomUUID();
        long revision = revision();
        var buyers = userRows();
        var items = itemSnapshots();

        var results = together(() -> prepare("alice", orderId, key), () -> prepare("alice", orderId, key));

        assertThat(results.stream().filter(value -> value.failure() == null).toList()).hasSize(1);
        assertThat(results.stream().filter(value -> value.failure() != null).toList()).singleElement()
            .satisfies(value -> assertThat(value.failure()).isExactlyInstanceOf(IllegalStateException.class));
        var prepared = (OrderPaymentStockService.PreparedPayment) results.stream()
            .filter(value -> value.failure() == null).findFirst().orElseThrow().value();
        assertThat(prepared.orderId()).isEqualTo(orderId);
        assertThat(prepared.attemptKey()).isEqualTo(key);
        assertThat(prepared.amount()).isEqualTo(2000);
        assertStock(productId, 3);
        assertPrepared(orderId, 2);
        assertThat(revision()).isEqualTo(revision + 1);
        assertThat(userRows()).isEqualTo(buyers);
        assertThat(itemSnapshots()).isEqualTo(items);
    }

    @Test
    @DisplayName("서로 다른 주문의 재고 5·수량4 경합: 한 주문만 확보하고 패자는 DRAFT 전체를 유지한다")
    void differentOrdersDoNotReserveBeyondAvailableStock() throws Exception {
        long productId = product(5);
        long firstId = draft("alice", productId, 4);
        long secondId = draft("bob", productId, 4);
        var firstBefore = orderRow(firstId);
        var secondBefore = orderRow(secondId);
        var buyers = userRows();
        var items = itemSnapshots();
        long revision = revision();

        var results = together(() -> prepare("alice", firstId, UUID.randomUUID()),
            () -> prepare("bob", secondId, UUID.randomUUID()));

        assertThat(results.stream().filter(value -> value.failure() == null).toList()).hasSize(1);
        assertThat(results.stream().filter(value -> value.failure() != null).toList()).singleElement()
            .satisfies(value -> assertThat(value.failure()).isExactlyInstanceOf(ProductStockException.class)
                .satisfies(error -> assertThat(((ProductStockException) error).getReason())
                    .isEqualTo(ProductStockException.Reason.INSUFFICIENT_STOCK)));
        int winner = results.get(0).failure() == null ? 0 : 1;
        long winnerId = winner == 0 ? firstId : secondId;
        long loserId = winner == 0 ? secondId : firstId;
        assertPrepared(winnerId, 4);
        assertThat(orderRow(loserId)).isEqualTo(winner == 0 ? secondBefore : firstBefore);
        assertThat(reservation(loserId)).isEqualTo("NOT_RESERVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Long.class)).isEqualTo(1);
        assertStock(productId, 1);
        assertThat(revision()).isEqualTo(revision + 1);
        assertThat(userRows()).isEqualTo(buyers);
        assertThat(itemSnapshots()).isEqualTo(items);
    }

    @Test
    @DisplayName("같은 FAILED의 동시 적용: 가용 재고는 5로 한 번만 반환되고 7이 되지 않는다")
    void concurrentFailureReturnsHeldStockOnce() throws Exception {
        long productId = product(5);
        long orderId = draft("alice", productId, 2);
        UUID key = UUID.randomUUID();
        prepare("alice", orderId, key);
        long revision = revision();
        var buyers = userRows();
        PaymentTransaction failed = transaction(orderId, PaymentTransaction.Status.FAILED);

        var results = together(() -> payments.apply(orderId, key, failed),
            () -> payments.apply(orderId, key, failed));

        assertNoFailures(results);
        assertThat(results.stream().map(value -> applied(value).observationResult()).toList())
            .containsExactlyInAnyOrder(PaymentObservationResult.APPLIED, PaymentObservationResult.UNCHANGED);
        assertThat(results).allSatisfy(value -> {
            assertThat(applied(value).orderStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(applied(value).paymentStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        });
        assertTerminal(orderId, false);
        assertStock(productId, 5);
        assertThat(revision()).isEqualTo(revision + 1);
        assertThat(userRows()).isEqualTo(buyers);
    }

    @Test
    @DisplayName("SUCCESS와 FAILED 경합: 먼저 반영한 종결만 보존하고 반대 결과는 충돌이다")
    void conflictingTerminalResultsPreserveFirstCommittedEffect() throws Exception {
        long productId = product(5);
        long orderId = draft("alice", productId, 2);
        UUID key = UUID.randomUUID();
        prepare("alice", orderId, key);
        long revision = revision();
        var buyers = userRows();
        PaymentTransaction success = transaction(orderId, PaymentTransaction.Status.SUCCESS);
        PaymentTransaction failure = transaction(orderId, PaymentTransaction.Status.FAILED);

        var results = together(() -> payments.apply(orderId, key, success),
            () -> payments.apply(orderId, key, failure));

        assertNoFailures(results);
        assertThat(results.stream().map(value -> applied(value).observationResult()).toList())
            .containsExactlyInAnyOrder(PaymentObservationResult.APPLIED, PaymentObservationResult.CONFLICT);
        boolean successWon = applied(results.get(0)).observationResult() == PaymentObservationResult.APPLIED;
        assertTerminal(orderId, successWon);
        assertStock(productId, successWon ? 3 : 5);
        assertThat(revision()).isEqualTo(revision + (successWon ? 0 : 1));
        var terminalState = storedState();

        var repeated = payments.apply(orderId, key, successWon ? failure : success);

        assertThat(repeated.observationResult()).isEqualTo(PaymentObservationResult.CONFLICT);
        assertThat(storedState()).isEqualTo(terminalState);
        assertThat(userRows()).isEqualTo(buyers);
    }

    @Test
    @DisplayName("관리자 가용10 설정과 실패 반환 경합: 직렬 결과10 또는12이고 예약은 한 번 반환된다")
    void stockReplacementAndFailurePreserveOneOfTheirSerialResults() throws Exception {
        long productId = product(5);
        long orderId = draft("alice", productId, 2);
        UUID key = UUID.randomUUID();
        prepare("alice", orderId, key);
        long revision = revision();
        var buyers = userRows();

        var results = together(() -> products.changeStock(UserRole.ADMIN, productId, 10),
            () -> payments.apply(orderId, key, transaction(orderId, PaymentTransaction.Status.FAILED)));

        assertNoFailures(results);
        assertThat(applied(results.get(1)).observationResult()).isEqualTo(PaymentObservationResult.APPLIED);
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, productId))
            .isIn(10, 12);
        assertTerminal(orderId, false);
        assertThat(revision()).isEqualTo(revision + 2);
        assertThat(userRows()).isEqualTo(buyers);
    }

    @Test
    @DisplayName("브랜드 삭제와 신규 확보 경합: 선행 확보만 HELD로 남고 삭제 선행은 부분 기록 없이 거절한다")
    void brandDeletionAndPreparationDoNotLeavePartialReservations() throws Exception {
        long productId = product(5);
        long orderId = draft("alice", productId, 2);
        UUID key = UUID.randomUUID();
        var draft = orderRow(orderId);
        var buyers = userRows();
        var items = itemSnapshots();
        long revision = revision();

        var results = together(() -> prepare("alice", orderId, key), () -> {
            adminBrands.delete(UserRole.ADMIN, brandId);
            return null;
        });

        assertThat(results.get(1).failure()).as("브랜드 삭제는 성공해야 한다").isNull();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM brand WHERE id=?", Boolean.class, brandId))
            .isTrue();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM product WHERE id=?", Boolean.class, productId))
            .isTrue();
        if (results.get(0).failure() == null) {
            assertPrepared(orderId, 2);
            assertStock(productId, 3);
            assertThat(revision()).isEqualTo(revision + 2);
        } else {
            assertThat(results.get(0).failure()).isExactlyInstanceOf(ProductQueryException.class)
                .satisfies(error -> assertThat(((ProductQueryException) error).getReason())
                    .isEqualTo(ProductQueryException.Reason.PRODUCT_NOT_FOUND));
            assertThat(orderRow(orderId)).isEqualTo(draft);
            assertThat(reservation(orderId)).isEqualTo("NOT_RESERVED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Long.class)).isZero();
            assertStock(productId, 5);
            assertThat(revision()).isEqualTo(revision + 1);
        }
        assertThat(itemSnapshots()).isEqualTo(items);
        assertThat(userRows()).isEqualTo(buyers);
    }

    @Test
    @DisplayName("실제 SQL은 확보·반환 모두 주문→브랜드→상품 FOR UPDATE와 마지막 revision 갱신을 유지한다")
    void preparationAndReturnUseActualSqlLocksInExistingRelativeOrder() {
        long productId = product(5);
        long orderId = draft("alice", productId, 2);
        UUID key = UUID.randomUUID();
        capture.clear();

        prepare("alice", orderId, key);

        assertLockOrderAndLastRevision(capture.statements());
        assertStock(productId, 3);
        capture.clear();

        payments.apply(orderId, key, transaction(orderId, PaymentTransaction.Status.FAILED));

        assertLockOrderAndLastRevision(capture.statements());
        assertStock(productId, 5);
        assertTerminal(orderId, false);
    }

    private void assertLockOrderAndLastRevision(List<String> statements) {
        List<String> sql = statements.stream().map(value -> value.toLowerCase(Locale.ROOT)
            .replace("`", "").replaceAll("\\s+", " ")).toList();
        int orderLock = lockIndex(sql, "order");
        int brandLock = lockIndex(sql, "brand");
        int productLock = lockIndex(sql, "product");
        assertThat(orderLock).as("주문 FOR UPDATE SQL").isGreaterThanOrEqualTo(0);
        assertThat(brandLock).as("브랜드는 주문 뒤 잠근다").isGreaterThan(orderLock);
        assertThat(productLock).as("상품은 브랜드 뒤 잠근다").isGreaterThan(brandLock);
        assertThat(sql).anyMatch(value -> value.contains("insert into product_list_revision"));
        assertThat(sql.get(sql.size() - 1)).as("업무 flush 뒤 목록 버전이 마지막 SQL")
            .contains("insert into product_list_revision");
        assertThat(sql).noneMatch(value -> value.startsWith("update user "));
    }

    private int lockIndex(List<String> statements, String table) {
        for (int index = 0; index < statements.size(); index++) {
            String statement = statements.get(index);
            if (statement.contains(" from " + table + " ") && statement.contains("for update")) {
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
            assertThat(ready.await(5, TimeUnit.SECONDS)).as("두 worker 시작 대기").isTrue();
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
            assertThat(workersTerminated).as("DB 재조회 전에 모든 worker 종료").isTrue();
        }
    }

    private Outcome invoke(Callable<?> action, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Payment concurrency start timed out");
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

    private void assertNoFailures(List<Outcome> outcomes) {
        assertThat(outcomes).allSatisfy(value -> assertThat(value.failure())
            .as("기술 오류·시간 초과를 정상 경합 결과로 세지 않는다").isNull());
    }

    private OrderPaymentStockService.AppliedPayment applied(Outcome outcome) {
        return (OrderPaymentStockService.AppliedPayment) outcome.value();
    }

    private long product(int stock) {
        return products.create(UserRole.ADMIN, brandId, "PG 재고 경합 상품", 1000, stock).productId();
    }

    private long draft(String buyer, long productId, int quantity) {
        return orders.create(buyer, List.of(new OrderQuantities.Item(productId, quantity))).orderId();
    }

    private OrderPaymentStockService.PreparedPayment prepare(String buyer, long orderId, UUID key) {
        return payments.prepareNew(buyer, orderId, key, ACCOUNT, providerOrderId(orderId), "TEST", "0000-0000-0000-0000");
    }

    private PaymentTransaction transaction(long orderId, PaymentTransaction.Status status) {
        return new PaymentTransaction(ACCOUNT, providerOrderId(orderId), "pg-tx-" + orderId, 2000, status);
    }

    private String providerOrderId(long orderId) {
        return "LP-ORD-" + orderId;
    }

    private void assertPrepared(long orderId, int quantity) {
        Map<String, Object> order = orderRow(orderId);
        assertThat(order.get("status")).isEqualTo("PAYMENT_PENDING");
        assertThat(order.get("payment_method")).isEqualTo("PG");
        assertThat(order.get("paid_amount")).isNull();
        assertThat(order.get("confirmed_at")).isNull();
        assertThat(reservation(orderId)).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT quantity FROM order_item WHERE order_id=?", Integer.class, orderId))
            .isEqualTo(quantity);
        Map<String, Object> attempt = jdbc.queryForMap("SELECT * FROM payment_attempt WHERE order_id=?", orderId);
        assertThat(attempt.get("status")).isEqualTo("NOT_OBSERVED");
        assertThat(attempt.get("amount")).isEqualTo(quantity * 1000L);
        assertThat(attempt.get("provider_account")).isEqualTo(ACCOUNT);
        assertThat(attempt.get("provider_order_id")).isEqualTo(providerOrderId(orderId));
        assertThat(attempt.get("send_started_at")).isNotNull();
        assertThat(attempt.get("transaction_key")).isNull();
    }

    private void assertTerminal(long orderId, boolean success) {
        Map<String, Object> order = orderRow(orderId);
        assertThat(order.get("status")).isEqualTo(success ? "CONFIRMED" : "PAYMENT_FAILED");
        assertThat(order.get("payment_method")).isEqualTo("PG");
        assertThat(order.get("total_amount")).isEqualTo(2000L);
        assertThat(order.get("paid_amount")).isEqualTo(success ? 2000L : null);
        if (success) {
            assertThat(order.get("confirmed_at")).isNotNull();
        } else {
            assertThat(order.get("confirmed_at")).isNull();
        }
        assertThat(reservation(orderId)).isEqualTo(success ? "CONSUMED" : "RELEASED");
        Map<String, Object> attempt = jdbc.queryForMap("SELECT * FROM payment_attempt WHERE order_id=?", orderId);
        assertThat(attempt.get("status")).isEqualTo(success ? "SUCCESS" : "FAILED");
        assertThat(attempt.get("transaction_key")).isEqualTo("pg-tx-" + orderId);
        assertThat(attempt.get("amount")).isEqualTo(2000L);
        assertThat(attempt.get("send_started_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE order_id=?", Long.class, orderId))
            .isEqualTo(1);
    }

    private void assertStock(long productId, int expected) {
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, productId))
            .isEqualTo(expected);
    }

    private long revision() {
        return jdbc.queryForObject("SELECT revision FROM product_list_revision WHERE id=1", Long.class);
    }

    private String reservation(long orderId) {
        return jdbc.queryForObject("SELECT reservation_status FROM order_item WHERE order_id=?", String.class, orderId);
    }

    private Map<String, Object> orderRow(long orderId) {
        return jdbc.queryForMap("SELECT * FROM `order` WHERE id=?", orderId);
    }

    private List<Map<String, Object>> userRows() {
        return jdbc.queryForList("SELECT * FROM user ORDER BY id");
    }

    private List<Map<String, Object>> itemSnapshots() {
        return jdbc.queryForList("SELECT id,order_id,product_id,product_name_snapshot,unit_price_snapshot,quantity"
            + " FROM order_item ORDER BY id");
    }

    private Map<String, List<Map<String, Object>>> storedState() {
        return Map.of(
            "orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "attempts", jdbc.queryForList("SELECT id,order_id,HEX(attempt_key) AS attempt_key,provider_account,"
                + "provider_order_id,amount,status,send_started_at,transaction_key FROM payment_attempt ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "revision", jdbc.queryForList("SELECT * FROM product_list_revision ORDER BY id"));
    }

    private record Outcome(Object value, Exception failure) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SqlCaptureConfiguration {
        @Bean
        SqlCapture stockSqlCapture() {
            return new SqlCapture();
        }

        @Bean
        HibernatePropertiesCustomizer stockSqlInspector(SqlCapture capture) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", capture);
        }
    }

    static class SqlCapture implements StatementInspector {
        private final List<String> statements = new CopyOnWriteArrayList<>();

        @Override
        public String inspect(String sql) {
            statements.add(sql);
            return sql;
        }

        void clear() {
            statements.clear();
        }

        List<String> statements() {
            return List.copyOf(statements);
        }
    }
}
