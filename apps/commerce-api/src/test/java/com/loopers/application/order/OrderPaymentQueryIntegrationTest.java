package com.loopers.application.order;

import com.loopers.application.user.UserResolutionException;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderException;
import com.loopers.domain.order.OrderPaymentMethod;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.UserRole;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Stored order/payment reads use the isolated MySQL container; GET never calls the provider. */
@SpringBootTest
class OrderPaymentQueryIntegrationTest {
    @TestBean(methodName = "com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration#create")
    private PaymentRequestFingerprinter fingerprinter;
    @MockitoBean private PaymentGateway gateway;
    @MockitoSpyBean private PaymentAttemptRepository attempts;
    @MockitoSpyBean private OrderRepository orderRepository;
    @Autowired private OrderPaymentQueryService queries;
    @Autowired private OrderPaymentStockService payments;
    @Autowired private OrderService orders;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private EntityManagerFactory entityManagerFactory;
    private boolean workersTerminated = true;

    @BeforeEach
    void initialize() {
        initializer.initialize();
    }

    @AfterEach
    void cleanup() {
        verifyNoInteractions(gateway);
        entityManagerFactory.unwrap(SessionFactory.class).getStatistics().setStatisticsEnabled(false);
        assertThat(workersTerminated).as("DB cleanup must wait until all query workers terminate").isTrue();
        cleanup.truncateAllTables();
    }

    @ParameterizedTest
    @EnumSource(PaymentAttemptStatus.class)
    void readsEveryCommittedPgStateWithoutCallingPgOrRequiringAFingerprint(PaymentAttemptStatus status) {
        Fixture fixture = prepared("alice");
        setState(fixture, status);
        jdbc.update("UPDATE payment_attempt SET request_fingerprint=NULL,fingerprint_version=NULL,key_version=NULL WHERE order_id=?",
            fixture.orderId());

        OrderPaymentInfo result = queries.getDetail("alice", fixture.orderId());
        OrderPaymentAdminInfo admin = queries.adminDetail(UserRole.ADMIN, fixture.orderId());

        assertThat(result.paymentMethod()).isEqualTo(OrderPaymentMethod.PG);
        assertThat(result.payment().paymentAttemptKey()).isEqualTo(fixture.key());
        assertThat(result.payment().status()).isEqualTo(status);
        assertThat(result.order().status()).isEqualTo(expectedOrderStatus(status));
        assertThat(result.order().totalAmount()).isEqualTo(200L);
        assertThat(admin.order()).isEqualTo(result);
        assertThat(admin.userId()).isEqualTo("alice");
        assertThat(jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class)).isZero();
    }

    @Test
    void preservesDraftAndHistoricalPointFactsIncludingDeletedProductSnapshots() {
        Fixture draft = draft("alice");
        Fixture point = draft("alice");
        jdbc.update("UPDATE user SET point_balance=1000 WHERE id=1");
        OrderInfo historical = orders.confirm("alice", point.orderId());
        jdbc.update("UPDATE product SET deleted_at=CURRENT_TIMESTAMP(6),name='changed',price=999 WHERE id=?", point.productId());

        OrderPaymentInfo draftResult = queries.getDetail("alice", draft.orderId());
        OrderPaymentInfo pointResult = queries.getDetail("alice", point.orderId());

        assertThat(draftResult.paymentMethod()).isNull();
        assertThat(draftResult.payment()).isNull();
        assertThat(draftResult.order().status()).isEqualTo(OrderStatus.DRAFT);
        assertThat(pointResult.paymentMethod()).isEqualTo(OrderPaymentMethod.POINT);
        assertThat(pointResult.payment()).isNull();
        assertThat(pointResult.order()).usingRecursiveComparison().ignoringFields("createdAt", "confirmedAt")
            .isEqualTo(historical);
        assertThat(pointResult.order().createdAt().toInstant()).isEqualTo(historical.createdAt().toInstant());
        assertThat(pointResult.order().confirmedAt().toInstant()).isEqualTo(historical.confirmedAt().toInstant());
        assertThat(attempts.findByOrderId(point.orderId())).isEmpty();
    }

    @Test
    void readsMixedPagesWithOneBatchAttemptQueryAndPreservesPagingAndFilters() {
        Fixture draft = draft("alice");
        Fixture pending = prepared("alice");
        Fixture failed = prepared("alice");
        setState(failed, PaymentAttemptStatus.FAILED);
        Fixture other = prepared("bob");
        clearInvocations(attempts, orderRepository);

        var page = queries.getPage("alice", 0, 2);

        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.items()).extracting(result -> result.order().orderId())
            .containsExactly(failed.orderId(), pending.orderId());
        verify(attempts, times(1)).findByOrderIds(anyList());
        verify(attempts, never()).findByOrderId(anyLong());
        verify(attempts, never()).lockByOrderId(anyLong());
        verify(orderRepository, never()).lockById(anyLong());
        clearInvocations(attempts);
        assertThat(queries.getPage("alice", 1, 2).items()).extracting(result -> result.order().orderId())
            .containsExactly(draft.orderId());
        verify(attempts, times(1)).findByOrderIds(anyList());
        var admin = queries.adminPage(UserRole.ADMIN, null, OrderStatus.PAYMENT_PENDING, 0, 20);
        assertThat(admin.items()).extracting(result -> result.order().order().orderId())
            .containsExactly(other.orderId(), pending.orderId());
        assertThat(queries.adminPage(UserRole.ADMIN, "alice", OrderStatus.PAYMENT_FAILED, 0, 20).items())
            .extracting(OrderPaymentAdminInfo::userId).containsExactly("alice");
        clearInvocations(attempts);
        assertThat(queries.getPage("alice", 99, 20).items()).isEmpty();
        assertThat(queries.adminPage(UserRole.ADMIN, "missing-user", null, 0, 20).items()).isEmpty();
        verifyNoInteractions(attempts);
    }

    @Test
    void hidesOtherOwnersBeforeReadingAttemptsAndRequiresAdminFirst() {
        Fixture fixture = prepared("alice");
        clearInvocations(attempts, orderRepository);

        assertThatThrownBy(() -> queries.getDetail("bob", fixture.orderId()))
            .isInstanceOfSatisfying(OrderException.class,
                exception -> assertThat(exception.getReason()).isEqualTo(OrderException.Reason.ORDER_NOT_FOUND));
        verifyNoInteractions(attempts);
        clearInvocations(orderRepository);
        assertThatThrownBy(() -> queries.adminDetail(UserRole.CUSTOMER, fixture.orderId()))
            .isInstanceOf(UserResolutionException.class);
        assertThatThrownBy(() -> queries.adminPage(UserRole.CUSTOMER, "alice", null, 0, 20))
            .isInstanceOf(UserResolutionException.class);
        verifyNoInteractions(orderRepository, attempts);
    }

    @Test
    void actualSqlCountDoesNotGrowWithTheNumberOfPgOrdersOnAPage() {
        for (int i = 0; i < 8; i++) {
            prepared("alice");
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        assertThat(queries.getPage("alice", 0, 1).items()).hasSize(1);
        long oneOrderQueries = statistics.getPrepareStatementCount();
        statistics.clear();

        assertThat(queries.getPage("alice", 0, 20).items()).hasSize(8);

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(oneOrderQueries);
        assertThat(oneOrderQueries).isBetween(1L, 6L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "amount", "state", "dispatch", "reservation"})
    void refusesInconsistentPgFactsAsTechnicalErrorsWithoutChangingThem(String corruption) {
        Fixture fixture = prepared("alice");
        switch (corruption) {
            case "missing" -> jdbc.update("DELETE FROM payment_attempt WHERE order_id=?", fixture.orderId());
            case "amount" -> jdbc.update("UPDATE payment_attempt SET amount=201 WHERE order_id=?", fixture.orderId());
            case "state" -> jdbc.update("UPDATE payment_attempt SET status='SUCCESS' WHERE order_id=?", fixture.orderId());
            case "dispatch" -> jdbc.update("UPDATE payment_attempt SET send_started_at=NULL WHERE order_id=?", fixture.orderId());
            default -> jdbc.update("UPDATE order_item SET reservation_status='CONSUMED' WHERE order_id=?", fixture.orderId());
        }
        var before = jdbc.queryForList("SELECT * FROM payment_attempt");

        assertThatThrownBy(() -> queries.getDetail("alice", fixture.orderId())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> queries.getPage("alice", 0, 20)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForList("SELECT * FROM payment_attempt")).usingRecursiveComparison().isEqualTo(before);
    }

    @Test
    @Timeout(30)
    void usesOneReadOnlySnapshotWhenPaymentCommitsBetweenOrderAndAttemptReads() throws Exception {
        Fixture fixture = prepared("alice");
        CountDownLatch orderRead = new CountDownLatch(1);
        CountDownLatch continueRead = new CountDownLatch(1);
        AtomicBoolean intercept = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (intercept.compareAndSet(true, false)) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
                orderRead.countDown();
                assertThat(continueRead.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(orderRepository).findById(fixture.orderId());
        var executor = Executors.newSingleThreadExecutor();
        workersTerminated = false;
        try {
            var reading = executor.submit(() -> queries.getDetail("alice", fixture.orderId()));
            assertThat(orderRead.await(10, TimeUnit.SECONDS)).isTrue();
            setState(fixture, PaymentAttemptStatus.SUCCESS);
            assertThat(jdbc.queryForObject("SELECT status FROM payment_attempt WHERE order_id=?", String.class, fixture.orderId()))
                .isEqualTo("SUCCESS");
            continueRead.countDown();
            var oldSnapshot = reading.get(10, TimeUnit.SECONDS);
            assertThat(oldSnapshot.order().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(oldSnapshot.payment().status()).isEqualTo(PaymentAttemptStatus.NOT_OBSERVED);
            assertThat(queries.getDetail("alice", fixture.orderId()).payment().status()).isEqualTo(PaymentAttemptStatus.SUCCESS);
        } finally {
            continueRead.countDown();
            executor.shutdownNow();
            workersTerminated = executor.awaitTermination(10, TimeUnit.SECONDS);
            assertThat(workersTerminated).isTrue();
        }
    }

    private Fixture draft(String owner) {
        Product product = products.save(new Product(brands.save(new Brand("query brand")), "original product", 100L, 5));
        long id = orders.create(owner, List.of(new OrderQuantities.Item(product.getId(), 2))).orderId();
        return new Fixture(id, product.getId(), UUID.randomUUID(), "QUERY-ORD-" + id);
    }

    private Fixture prepared(String owner) {
        Fixture fixture = draft(owner);
        payments.prepareNew(owner, fixture.orderId(), fixture.key(), "135135", fixture.providerId(), "TEST", "0000-0000-0000-0000");
        return fixture;
    }

    private void setState(Fixture fixture, PaymentAttemptStatus status) {
        if (status == PaymentAttemptStatus.UNKNOWN) {
            payments.markUnknown(fixture.orderId(), fixture.key());
        } else if (status != PaymentAttemptStatus.NOT_OBSERVED) {
            payments.apply(fixture.orderId(), fixture.key(), new PaymentTransaction("135135", fixture.providerId(),
                "QUERY-TX-" + fixture.orderId(), 200L, PaymentTransaction.Status.valueOf(status.name())));
        }
    }

    private OrderStatus expectedOrderStatus(PaymentAttemptStatus status) {
        return switch (status) {
            case SUCCESS -> OrderStatus.CONFIRMED;
            case FAILED -> OrderStatus.PAYMENT_FAILED;
            default -> OrderStatus.PAYMENT_PENDING;
        };
    }

    private record Fixture(long orderId, long productId, UUID key, String providerId) {
    }
}
