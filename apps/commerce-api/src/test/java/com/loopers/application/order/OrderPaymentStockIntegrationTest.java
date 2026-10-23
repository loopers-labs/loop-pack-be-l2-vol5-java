package com.loopers.application.order;

import com.loopers.application.brand.AdminBrandService;
import com.loopers.application.product.AdminProductService;
import com.loopers.application.product.ProductQueryException;
import com.loopers.application.product.ProductQueryService;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderException;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentObservationResult;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductListRevisionRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductStockException;
import com.loopers.domain.user.UserRole;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 MySQL의 TX A/B 재고 효과를 검증한다. HTTP PG 생성·callback·카드 요청 동일성은 이 증분의 범위가 아니다.
 */
@SpringBootTest
class OrderPaymentStockIntegrationTest {
    private static final String ACCOUNT = "135135";

    @TestBean(methodName = "com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration#create")
    private PaymentRequestFingerprinter fingerprinter;

    @Autowired private OrderPaymentStockService payments;
    @Autowired private OrderService orders;
    @Autowired private AdminProductService adminProducts;
    @Autowired private AdminBrandService adminBrands;
    @Autowired private ProductQueryService productQueries;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private ProductListRevisionRepository revisions;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        initializer.initialize();
    }

    @AfterEach
    void tearDown() {
        cleanUp.truncateAllTables();
    }

    @Test
    void preparesStockReservationAttemptDispatchAndRevisionTogetherWithoutPointPayment() {
        Fixture fixture = draft();
        long beforeRevision = revisions.current();

        var prepared = prepare(fixture);

        assertThat(prepared.orderId()).isEqualTo(fixture.orderId());
        assertThat(prepared.attemptKey()).isEqualTo(fixture.attemptKey());
        assertThat(prepared.providerAccount()).isEqualTo(ACCOUNT);
        assertThat(prepared.providerOrderId()).isEqualTo(fixture.providerOrderId());
        assertThat(prepared.amount()).isEqualTo(200L);
        assertState(fixture, "PAYMENT_PENDING", "HELD", "NOT_OBSERVED", 3);
        var attempt = attempts.findByOrderId(fixture.orderId()).orElseThrow();
        assertThat(attempt.getSendStartedAt()).isNotNull();
        assertThat(attempt.getTransactionKey()).isNull();
        assertThat(attempt.getAttemptKey()).isEqualTo(fixture.attemptKey());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Long.class)).isEqualTo(1L);
        assertThat(revisions.current()).isEqualTo(beforeRevision + 1);
        assertThat(balance()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentTransaction.Status.class, names = {"SUCCESS", "FAILED"})
    void appliesATerminalResultOnceAndPreservesItAgainstDuplicatePendingAndUnknown(PaymentTransaction.Status status) {
        Fixture fixture = prepared();
        long beforeRevision = revisions.current();

        var result = apply(fixture, status);

        assertThat(result.observationResult()).isEqualTo(PaymentObservationResult.APPLIED);
        assertTerminalState(fixture, status);
        assertThat(result.orderStatus()).isEqualTo(success(status) ? OrderStatus.CONFIRMED : OrderStatus.PAYMENT_FAILED);
        assertThat(result.paymentStatus()).isEqualTo(success(status) ? PaymentAttemptStatus.SUCCESS : PaymentAttemptStatus.FAILED);
        assertThat(revisions.current()).isEqualTo(beforeRevision + (success(status) ? 0 : 1));
        var terminal = storedState();

        assertThat(apply(fixture, status).observationResult()).isEqualTo(PaymentObservationResult.UNCHANGED);
        assertThat(apply(fixture, PaymentTransaction.Status.PENDING).observationResult())
            .isEqualTo(PaymentObservationResult.UNCHANGED);
        assertThat(payments.markUnknown(fixture.orderId(), fixture.attemptKey()).observationResult())
            .isEqualTo(PaymentObservationResult.UNCHANGED);
        assertThat(storedState()).isEqualTo(terminal);
        assertThat(balance()).isZero();
    }

    @Test
    void unknownAndPendingKeepHeldStockAndCanRecoverToTheSameTransaction() {
        Fixture fixture = prepared();
        long beforeRevision = revisions.current();

        assertThat(payments.markUnknown(fixture.orderId(), fixture.attemptKey()).observationResult())
            .isEqualTo(PaymentObservationResult.APPLIED);
        assertState(fixture, "PAYMENT_PENDING", "HELD", "UNKNOWN", 3);
        assertThat(attempts.findByOrderId(fixture.orderId()).orElseThrow().getTransactionKey()).isNull();
        assertThat(apply(fixture, PaymentTransaction.Status.PENDING).observationResult())
            .isEqualTo(PaymentObservationResult.APPLIED);
        assertState(fixture, "PAYMENT_PENDING", "HELD", "PENDING", 3);
        payments.markUnknown(fixture.orderId(), fixture.attemptKey());
        assertState(fixture, "PAYMENT_PENDING", "HELD", "UNKNOWN", 3);
        assertThat(attempts.findByOrderId(fixture.orderId()).orElseThrow().getTransactionKey()).isEqualTo("TX-1");
        var unknown = storedState();
        assertThat(payments.markUnknown(fixture.orderId(), fixture.attemptKey()).observationResult())
            .isEqualTo(PaymentObservationResult.UNCHANGED);
        assertThat(storedState()).isEqualTo(unknown);
        assertThat(revisions.current()).isEqualTo(beforeRevision);

        apply(fixture, PaymentTransaction.Status.SUCCESS);
        assertTerminalState(fixture, PaymentTransaction.Status.SUCCESS);
        assertThat(revisions.current()).isEqualTo(beforeRevision);
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "order", "amount", "transaction"})
    void conflictingObservationDoesNotChangeAnyStoredFact(String mismatch) {
        Fixture fixture = prepared();
        apply(fixture, PaymentTransaction.Status.PENDING);
        var before = storedState();
        PaymentTransaction observation = new PaymentTransaction(
            mismatch.equals("account") ? "other-account" : ACCOUNT,
            mismatch.equals("order") ? "OTHER-ORDER" : fixture.providerOrderId(),
            mismatch.equals("transaction") ? "TX-OTHER" : "TX-1",
            mismatch.equals("amount") ? 201L : 200L, PaymentTransaction.Status.SUCCESS);

        var result = payments.apply(fixture.orderId(), fixture.attemptKey(), observation);

        assertThat(result.observationResult()).isEqualTo(PaymentObservationResult.CONFLICT);
        assertThat(result.orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(result.paymentStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(storedState()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"apply", "unknown", "reject"})
    void anotherAttemptKeyCannotChangeAnExistingReservation(String operation) {
        Fixture fixture = prepared();
        var before = storedState();
        UUID wrongKey = UUID.randomUUID();

        OrderPaymentStockService.AppliedPayment result = switch (operation) {
            case "apply" -> payments.apply(fixture.orderId(), wrongKey, transaction(fixture, PaymentTransaction.Status.SUCCESS));
            case "unknown" -> payments.markUnknown(fixture.orderId(), wrongKey);
            default -> payments.rejectBeforeCreate(fixture.orderId(), wrongKey, ACCOUNT, fixture.providerOrderId(), 200L);
        };

        assertThat(result.observationResult()).isEqualTo(PaymentObservationResult.CONFLICT);
        assertThat(storedState()).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentTransaction.Status.class, names = {"SUCCESS", "FAILED"})
    void oppositeTerminalObservationCannotOverwriteTheFirstResult(PaymentTransaction.Status first) {
        Fixture fixture = prepared();
        apply(fixture, first);
        var before = storedState();

        var result = apply(fixture, success(first) ? PaymentTransaction.Status.FAILED : PaymentTransaction.Status.SUCCESS);

        assertThat(result.observationResult()).isEqualTo(PaymentObservationResult.CONFLICT);
        assertThat(storedState()).isEqualTo(before);
    }

    @Test
    void confirmedNoEffectRejectionReturnsOnceWithoutInventingATransactionKey() {
        Fixture fixture = prepared();
        long beforeRevision = revisions.current();

        var result = reject(fixture);

        assertThat(result.observationResult()).isEqualTo(PaymentObservationResult.APPLIED);
        assertState(fixture, "PAYMENT_FAILED", "RELEASED", "FAILED", 5);
        assertThat(attempts.findByOrderId(fixture.orderId()).orElseThrow().getTransactionKey()).isNull();
        assertThat(revisions.current()).isEqualTo(beforeRevision + 1);
        var terminal = storedState();
        assertThat(reject(fixture).observationResult()).isEqualTo(PaymentObservationResult.UNCHANGED);
        assertThat(payments.markUnknown(fixture.orderId(), fixture.attemptKey()).observationResult())
            .isEqualTo(PaymentObservationResult.UNCHANGED);
        assertThat(storedState()).isEqualTo(terminal);
    }

    @Test
    void noEffectRejectionConflictsWithAnAlreadyObservedExternalTransaction() {
        Fixture fixture = prepared();
        apply(fixture, PaymentTransaction.Status.PENDING);
        var before = storedState();

        assertThat(reject(fixture).observationResult()).isEqualTo(PaymentObservationResult.CONFLICT);

        assertThat(storedState()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "order", "amount"})
    void mismatchedNoEffectRejectionDoesNotReturnStock(String mismatch) {
        Fixture fixture = prepared();
        var before = storedState();

        var result = payments.rejectBeforeCreate(fixture.orderId(), fixture.attemptKey(),
            mismatch.equals("account") ? "other-account" : ACCOUNT,
            mismatch.equals("order") ? "OTHER-ORDER" : fixture.providerOrderId(),
            mismatch.equals("amount") ? 201L : 200L);

        assertThat(result.observationResult()).isEqualTo(PaymentObservationResult.CONFLICT);
        assertThat(storedState()).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentTransaction.Status.class, names = {"SUCCESS", "FAILED"})
    void administratorSetsAvailableStockWithoutErasingHeldReservation(PaymentTransaction.Status result) {
        Fixture fixture = prepared();
        adminProducts.changeStock(UserRole.ADMIN, fixture.productId(), 10);
        assertState(fixture, "PAYMENT_PENDING", "HELD", "NOT_OBSERVED", 10);

        apply(fixture, result);

        assertThat(stock(fixture.productId())).isEqualTo(success(result) ? 10 : 12);
        assertThat(reservations(fixture.orderId())).containsExactly(success(result) ? "CONSUMED" : "RELEASED");
        assertThat(balance()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"product", "brand"})
    void deletionBeforeReservationRejectsWithoutAnyPreparation(String target) {
        Fixture fixture = draft();
        delete(fixture, target);
        var before = storedState();

        assertThatThrownBy(() -> prepare(fixture))
            .isInstanceOfSatisfying(ProductQueryException.class,
                exception -> assertThat(exception.getReason()).isEqualTo(ProductQueryException.Reason.PRODUCT_NOT_FOUND));

        assertThat(storedState()).isEqualTo(before);
        assertThat(attempts.findByOrderId(fixture.orderId())).isEmpty();
        assertThat(stock(fixture.productId())).isEqualTo(5);
    }

    @ParameterizedTest
    @CsvSource({"product,SUCCESS", "product,FAILED", "brand,SUCCESS", "brand,FAILED"})
    void reservationCanFinishAfterDeletionWithoutRestoringVisibility(String target, PaymentTransaction.Status result) {
        Fixture fixture = prepared();
        delete(fixture, target);
        Object productDeletedAt = jdbc.queryForObject("SELECT deleted_at FROM product WHERE id=?", Object.class, fixture.productId());
        List<Map<String, Object>> brandBefore = jdbc.queryForList("SELECT * FROM brand WHERE id=?", fixture.brandId());

        apply(fixture, result);

        assertTerminalState(fixture, result);
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM product WHERE id=?", Object.class, fixture.productId()))
            .isEqualTo(productDeletedAt);
        assertThat(jdbc.queryForList("SELECT * FROM brand WHERE id=?", fixture.brandId())).isEqualTo(brandBefore);
        assertThatThrownBy(() -> productQueries.getDetail(fixture.productId())).isInstanceOf(ProductQueryException.class);
        assertThat(orders.getDetail("alice", fixture.orderId()).items())
            .containsExactly(new OrderInfo.ItemInfo(fixture.productId(), "상품", 100L, 2, 200L));
    }

    @Test
    void insufficientSecondItemLeavesAllProductsOrderAndAttemptUnchanged() {
        Product first = product("충분한 상품", 5);
        Product second = product("부족한 상품", 1);
        long orderId = orders.create("alice", List.of(new OrderQuantities.Item(first.getId(), 2),
            new OrderQuantities.Item(second.getId(), 2))).orderId();
        var before = storedState();

        assertThatThrownBy(() -> payments.prepareNew("alice", orderId, UUID.randomUUID(), ACCOUNT, "LP-TWO-ITEMS",
            "TEST", "0000-0000-0000-0000"))
            .isInstanceOfSatisfying(ProductStockException.class,
                exception -> assertThat(exception.getReason()).isEqualTo(ProductStockException.Reason.INSUFFICIENT_STOCK));

        assertThat(storedState()).isEqualTo(before);
        assertThat(stock(first.getId())).isEqualTo(5);
        assertThat(stock(second.getId())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"total", "negativeTotal", "quantity"})
    void invalidStoredDraftAmountOrQuantityCannotReserveStock(String corrupted) {
        Fixture fixture = draft();
        if (corrupted.equals("quantity")) {
            jdbc.update("UPDATE order_item SET quantity=0 WHERE order_id=?", fixture.orderId());
        } else {
            jdbc.update("UPDATE `order` SET total_amount=? WHERE id=?", corrupted.equals("negativeTotal") ? -1L : 201L,
                fixture.orderId());
        }
        var before = storedState();

        assertThatThrownBy(() -> prepare(fixture)).isInstanceOf(IllegalStateException.class);

        assertThat(storedState()).isEqualTo(before);
        assertThat(stock(fixture.productId())).isEqualTo(5);
        assertThat(attempts.findByOrderId(fixture.orderId())).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"pending,total", "unknown,total", "pending,quantity", "unknown,quantity",
        "pending,reservation", "unknown,reservation", "pending,method", "unknown,method"})
    void nonterminalObservationCannotHideAnInconsistentStoredOrder(String operation, String corrupted) {
        Fixture fixture = prepared();
        switch (corrupted) {
            case "total" -> jdbc.update("UPDATE `order` SET total_amount=201 WHERE id=?", fixture.orderId());
            case "quantity" -> jdbc.update("UPDATE order_item SET quantity=0 WHERE order_id=?", fixture.orderId());
            case "reservation" -> jdbc.update("UPDATE order_item SET reservation_status='CONSUMED' WHERE order_id=?",
                fixture.orderId());
            default -> jdbc.update("UPDATE `order` SET payment_method='POINT' WHERE id=?", fixture.orderId());
        }
        var before = storedState();

        assertThatThrownBy(() -> {
            if (operation.equals("pending")) {
                apply(fixture, PaymentTransaction.Status.PENDING);
            } else {
                payments.markUnknown(fixture.orderId(), fixture.attemptKey());
            }
        }).isInstanceOf(IllegalStateException.class);

        assertThat(storedState()).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"pending,SUCCESS", "unknown,FAILED"})
    void nonterminalObservationCannotAcceptATerminalAttemptWithAnUnfinishedOrder(String operation, String paymentStatus) {
        Fixture fixture = prepared();
        jdbc.update("UPDATE payment_attempt SET status=?, transaction_key='TX-1' WHERE order_id=?", paymentStatus, fixture.orderId());
        var before = storedState();

        assertThatThrownBy(() -> {
            if (operation.equals("pending")) {
                apply(fixture, PaymentTransaction.Status.PENDING);
            } else {
                payments.markUnknown(fixture.orderId(), fixture.attemptKey());
            }
        }).isInstanceOf(IllegalStateException.class);

        assertThat(storedState()).isEqualTo(before);
    }

    @Test
    void anotherOwnerCannotPrepareTheOrder() {
        Fixture fixture = draft();
        var before = storedState();

        assertThatThrownBy(() -> payments.prepareNew("bob", fixture.orderId(), fixture.attemptKey(), ACCOUNT,
            fixture.providerOrderId(), "TEST", "0000-0000-0000-0000"))
            .isInstanceOfSatisfying(OrderException.class,
                exception -> assertThat(exception.getReason()).isEqualTo(OrderException.Reason.ORDER_NOT_FOUND));

        assertThat(storedState()).isEqualTo(before);
    }

    @Test
    void historicalPointPaymentCannotBeConvertedIntoANewPgAttempt() {
        Fixture fixture = draft();
        jdbc.update("UPDATE user SET point_balance=1000 WHERE id=1");
        OrderInfo historical = orders.confirm("alice", fixture.orderId());
        var before = storedState();

        assertThatThrownBy(() -> prepare(fixture)).isInstanceOfSatisfying(OrderException.class,
            exception -> assertThat(exception.getReason()).isEqualTo(OrderException.Reason.ORDER_ALREADY_CONFIRMED));

        assertThat(storedState()).isEqualTo(before);
        OrderInfo loaded = orders.getDetail("alice", fixture.orderId());
        assertThat(loaded.orderId()).isEqualTo(historical.orderId());
        assertThat(loaded.status()).isEqualTo(historical.status());
        assertThat(loaded.items()).isEqualTo(historical.items());
        assertThat(loaded.totalAmount()).isEqualTo(historical.totalAmount());
        assertThat(loaded.paidAmount()).isEqualTo(historical.paidAmount());
        assertThat(loaded.confirmedAt().toInstant()).isEqualTo(historical.confirmedAt().toInstant());
        assertThat(jdbc.queryForObject("SELECT payment_method FROM `order` WHERE id=?", String.class, fixture.orderId()))
            .isEqualTo("POINT");
        assertThat(attempts.findByOrderId(fixture.orderId())).isEmpty();
        assertThat(reservations(fixture.orderId())).containsExactly("NOT_RESERVED");
    }

    @Test
    void newPreparationOnlyBoundaryRejectsAnAlreadyPreparedOrderWithoutAnotherReservation() {
        Fixture fixture = prepared();
        var before = storedState();

        assertThatThrownBy(() -> prepare(fixture)).isInstanceOf(IllegalStateException.class);

        assertThat(storedState()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Long.class)).isEqualTo(1L);
    }

    @Test
    void preparationParticipatesInTheCallerTransactionAndRollsBackActualSqlBeforeANewTry() {
        Fixture fixture = draft();
        var before = storedState();
        long beforeRevision = revisions.current();
        IllegalStateException injected = new IllegalStateException("after TX A flush");

        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            prepare(fixture);
            entityManager.flush();
            assertState(fixture, "PAYMENT_PENDING", "HELD", "NOT_OBSERVED", 3);
            assertThat(jdbc.queryForObject("SELECT COUNT(send_started_at) FROM payment_attempt", Long.class)).isEqualTo(1L);
            assertThat(revisions.current()).isEqualTo(beforeRevision + 1);
            throw injected;
        })).isSameAs(injected);

        assertThat(storedState()).isEqualTo(before);
        assertThat(attempts.findByOrderId(fixture.orderId())).isEmpty();
        prepare(fixture);
        assertState(fixture, "PAYMENT_PENDING", "HELD", "NOT_OBSERVED", 3);
        assertThat(revisions.current()).isEqualTo(beforeRevision + 1);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentTransaction.Status.class, names = {"SUCCESS", "FAILED"})
    void terminalApplicationRollsBackRealSqlAndTheSameObservationCanBeAppliedAgain(PaymentTransaction.Status result) {
        Fixture fixture = prepared();
        apply(fixture, PaymentTransaction.Status.PENDING);
        var before = storedState();
        long beforeRevision = revisions.current();
        IllegalStateException injected = new IllegalStateException("after TX B flush");

        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            apply(fixture, result);
            entityManager.flush();
            assertTerminalState(fixture, result);
            assertThat(revisions.current()).isEqualTo(beforeRevision + (success(result) ? 0 : 1));
            throw injected;
        })).isSameAs(injected);

        assertThat(storedState()).isEqualTo(before);
        apply(fixture, result);
        assertTerminalState(fixture, result);
        assertThat(revisions.current()).isEqualTo(beforeRevision + (success(result) ? 0 : 1));
        var terminal = storedState();
        apply(fixture, result);
        assertThat(storedState()).isEqualTo(terminal);
    }

    @Test
    void noEffectRejectionRollbackKeepsReservationUntilTheSameTrustedEvidenceIsAppliedAgain() {
        Fixture fixture = prepared();
        var before = storedState();
        IllegalStateException injected = new IllegalStateException("after no-effect rejection flush");

        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            reject(fixture);
            entityManager.flush();
            assertState(fixture, "PAYMENT_FAILED", "RELEASED", "FAILED", 5);
            throw injected;
        })).isSameAs(injected);

        assertThat(storedState()).isEqualTo(before);
        // 동일한 엄격 거절 증거를 다시 전달하는 시험이다. 조회 0건으로 이 결과를 재구성하지 않는다.
        reject(fixture);
        assertState(fixture, "PAYMENT_FAILED", "RELEASED", "FAILED", 5);
        assertThat(attempts.findByOrderId(fixture.orderId()).orElseThrow().getTransactionKey()).isNull();
    }

    private Fixture draft() {
        Brand brand = brands.save(new Brand("브랜드"));
        Product product = products.save(new Product(brand, "상품", 100L, 5));
        long orderId = orders.create("alice", List.of(new OrderQuantities.Item(product.getId(), 2))).orderId();
        return new Fixture(product.getId(), brand.getId(), orderId, UUID.randomUUID(), "LP-ORDER-" + orderId);
    }

    private Fixture prepared() {
        Fixture fixture = draft();
        prepare(fixture);
        return fixture;
    }

    private Product product(String name, int stock) {
        return products.save(new Product(brands.save(new Brand("브랜드")), name, 100L, stock));
    }

    private OrderPaymentStockService.PreparedPayment prepare(Fixture fixture) {
        return payments.prepareNew("alice", fixture.orderId(), fixture.attemptKey(), ACCOUNT, fixture.providerOrderId(),
            "TEST", "0000-0000-0000-0000");
    }

    private OrderPaymentStockService.AppliedPayment apply(Fixture fixture, PaymentTransaction.Status result) {
        return payments.apply(fixture.orderId(), fixture.attemptKey(), transaction(fixture, result));
    }

    private OrderPaymentStockService.AppliedPayment reject(Fixture fixture) {
        return payments.rejectBeforeCreate(fixture.orderId(), fixture.attemptKey(), ACCOUNT, fixture.providerOrderId(), 200L);
    }

    private PaymentTransaction transaction(Fixture fixture, PaymentTransaction.Status result) {
        return new PaymentTransaction(ACCOUNT, fixture.providerOrderId(), "TX-1", 200L, result);
    }

    private boolean success(PaymentTransaction.Status status) {
        return status == PaymentTransaction.Status.SUCCESS;
    }

    private void delete(Fixture fixture, String target) {
        if (target.equals("brand")) {
            adminBrands.delete(UserRole.ADMIN, fixture.brandId());
        } else {
            adminProducts.delete(UserRole.ADMIN, fixture.productId());
        }
    }

    private void assertTerminalState(Fixture fixture, PaymentTransaction.Status status) {
        assertState(fixture, success(status) ? "CONFIRMED" : "PAYMENT_FAILED",
            success(status) ? "CONSUMED" : "RELEASED", status.name(), success(status) ? 3 : 5);
        assertThat(attempts.findByOrderId(fixture.orderId()).orElseThrow().getTransactionKey()).isEqualTo("TX-1");
        Long amount = jdbc.queryForObject("SELECT paid_amount FROM `order` WHERE id=?", Long.class, fixture.orderId());
        Object time = jdbc.queryForObject("SELECT confirmed_at FROM `order` WHERE id=?", Object.class, fixture.orderId());
        if (success(status)) {
            assertThat(amount).isEqualTo(200L);
            assertThat(time).isNotNull();
        } else {
            assertThat(amount).isNull();
            assertThat(time).isNull();
        }
    }

    private void assertState(Fixture fixture, String orderStatus, String reservation, String attemptStatus, int stock) {
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=?", String.class, fixture.orderId()))
            .isEqualTo(orderStatus);
        assertThat(jdbc.queryForObject("SELECT payment_method FROM `order` WHERE id=?", String.class, fixture.orderId()))
            .isEqualTo("PG");
        assertThat(reservations(fixture.orderId())).containsExactly(reservation);
        assertThat(jdbc.queryForObject("SELECT quantity FROM order_item WHERE order_id=?", Integer.class, fixture.orderId()))
            .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_attempt WHERE order_id=?", String.class, fixture.orderId()))
            .isEqualTo(attemptStatus);
        assertThat(stock(fixture.productId())).isEqualTo(stock);
        assertThat(balance()).isZero();
    }

    private List<String> reservations(long orderId) {
        return jdbc.queryForList("SELECT reservation_status FROM order_item WHERE order_id=? ORDER BY id", String.class, orderId);
    }

    private int stock(long productId) {
        return jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, productId);
    }

    private long balance() {
        return jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class);
    }

    private Map<String, List<Map<String, Object>>> storedState() {
        return Map.of("orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "brands", jdbc.queryForList("SELECT * FROM brand ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id"),
            "attempts", jdbc.queryForList("""
                SELECT id, order_id, HEX(attempt_key) AS attempt_key, provider_account, provider_order_id,
                       transaction_key, amount, status, send_started_at FROM payment_attempt ORDER BY id
                """),
            "revisions", jdbc.queryForList("SELECT * FROM product_list_revision ORDER BY id"));
    }

    private TransactionTemplate tx() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    private record Fixture(long productId, long brandId, long orderId, UUID attemptKey, String providerOrderId) {
    }
}
