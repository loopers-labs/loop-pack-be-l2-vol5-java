package com.loopers.application.order;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.event.DomainEventPublisher;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentObservationResult;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** Real isolated MySQL and Spring transaction synchronization; the Kafka boundary is replaced with a mock. */
@SpringBootTest
@Timeout(30)
class OrderConfirmedPublicationIntegrationTest {
    private static final String ACCOUNT = "135135";

    @TestBean(methodName = "com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration#create")
    private PaymentRequestFingerprinter fingerprinter;

    @MockitoBean private DomainEventPublisher publisher;
    @Autowired private OrderPaymentStockService payments;
    @Autowired private OrderService orders;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationEventPublisher events;

    @BeforeEach
    void setUp() {
        initializer.initialize();
    }

    @AfterEach
    void tearDown() {
        cleanUp.truncateAllTables();
    }

    @Test
    void publishesOnlyAfterTheOuterTransactionCommitsAndItsFactsAreVisibleOnAnotherConnection() {
        Fixture fixture = prepared();
        AtomicReference<CommittedObservation> observed = new AtomicReference<>();
        AtomicReference<Long> writerConnection = new AtomicReference<>();
        doAnswer(invocation -> {
            // AFTER_COMMIT can still have thread-bound resources; a separate transaction observes the committed facts.
            TransactionTemplate reader = tx();
            reader.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            observed.set(reader.execute(status -> new CommittedObservation(connectionId(),
                orders.getDetail("alice", fixture.orderId()), reservation(fixture), balance())));
            return null;
        }).when(publisher).publish(any(OrderConfirmedEvent.class));

        tx().executeWithoutResult(status -> {
            writerConnection.set(connectionId());
            assertThat(apply(fixture, PaymentTransaction.Status.SUCCESS).orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
            entityManager.flush();
            assertThat(reservation(fixture)).isEqualTo("CONSUMED");
            verifyNoInteractions(publisher);
        });

        OrderConfirmedEvent event = publishedOnce();
        assertThat(observed.get()).isNotNull();
        assertThat(observed.get().connectionId()).isNotEqualTo(writerConnection.get());
        assertThat(observed.get().order().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(observed.get().reservation()).isEqualTo("CONSUMED");
        assertThat(observed.get().balance()).isZero();
        assertThat(event.eventId()).isEqualTo("order-confirmed:v1:" + fixture.orderId());
        assertThat(event.key()).isEqualTo(Long.toString(fixture.orderId()));
        assertThat(event.confirmedAt()).isEqualTo(observed.get().order().confirmedAt().toInstant());
        assertThat(event.items()).containsExactly(new OrderConfirmedEvent.Item(fixture.productId(), 2));
    }

    @Test
    void rollbackAfterRealSqlSuppressesPublicationAndALaterSuccessfulApplicationCanPublish() {
        Fixture fixture = prepared();
        IllegalStateException injected = new IllegalStateException("test failure after confirmation SQL");

        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            apply(fixture, PaymentTransaction.Status.SUCCESS);
            entityManager.flush();
            assertThat(orderStatus(fixture)).isEqualTo("CONFIRMED");
            assertThat(reservation(fixture)).isEqualTo("CONSUMED");
            verifyNoInteractions(publisher);
            throw injected;
        })).isSameAs(injected);

        verifyNoInteractions(publisher);
        assertThat(orderStatus(fixture)).isEqualTo("PAYMENT_PENDING");
        assertThat(reservation(fixture)).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_attempt WHERE order_id=?", String.class,
            fixture.orderId())).isEqualTo("NOT_OBSERVED");
        assertThat(orders.getDetail("alice", fixture.orderId()).confirmedAt()).isNull();
        assertThat(stock(fixture)).isEqualTo(3);
        assertThat(balance()).isZero();

        apply(fixture, PaymentTransaction.Status.SUCCESS);
        assertConfirmed(fixture);
        assertThat(publishedOnce().orderId()).isEqualTo(fixture.orderId());
    }

    @Test
    void repeatedSuccessAndLatePendingOrUnknownPreserveTheFirstFactWithoutPublishingAgain() {
        Fixture fixture = prepared();
        apply(fixture, PaymentTransaction.Status.SUCCESS);
        OrderInfo first = orders.getDetail("alice", fixture.orderId());

        assertThat(apply(fixture, PaymentTransaction.Status.SUCCESS).observationResult())
            .isEqualTo(PaymentObservationResult.UNCHANGED);
        apply(fixture, PaymentTransaction.Status.PENDING);
        payments.markUnknown(fixture.orderId(), fixture.attemptKey());

        assertThat(orders.getDetail("alice", fixture.orderId())).isEqualTo(first);
        assertThat(publishedOnce().confirmedAt()).isEqualTo(first.confirmedAt().toInstant());
        assertConfirmed(fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "UNKNOWN", "FAILED", "REJECTED_NO_EFFECT"})
    void draftPreparationAndNonSuccessfulObservationsNeverPublish(String observation) {
        Fixture fixture = draft();
        orders.getDetail("alice", fixture.orderId());
        verifyNoInteractions(publisher);
        prepare(fixture);
        verifyNoInteractions(publisher);

        if (observation.equals("UNKNOWN")) {
            payments.markUnknown(fixture.orderId(), fixture.attemptKey());
        } else if (observation.equals("REJECTED_NO_EFFECT")) {
            payments.rejectBeforeCreate(fixture.orderId(), fixture.attemptKey(), ACCOUNT, fixture.providerOrderId(), 200L);
        } else {
            apply(fixture, PaymentTransaction.Status.valueOf(observation));
        }

        verifyNoInteractions(publisher);
        boolean failed = observation.equals("FAILED") || observation.equals("REJECTED_NO_EFFECT");
        assertThat(orderStatus(fixture)).isEqualTo(failed ? "PAYMENT_FAILED" : "PAYMENT_PENDING");
        assertThat(reservation(fixture)).isEqualTo(failed ? "RELEASED" : "HELD");
        assertThat(stock(fixture)).isEqualTo(failed ? 5 : 3);
        assertThat(balance()).isZero();
    }

    @Test
    void mismatchingSuccessDoesNotConfirmOrPublish() {
        Fixture fixture = prepared();
        PaymentTransaction mismatching = new PaymentTransaction(ACCOUNT, fixture.providerOrderId(), "TX-1", 201L,
            PaymentTransaction.Status.SUCCESS);

        assertThat(payments.apply(fixture.orderId(), fixture.attemptKey(), mismatching).observationResult())
            .isEqualTo(PaymentObservationResult.CONFLICT);

        verifyNoInteractions(publisher);
        assertThat(orderStatus(fixture)).isEqualTo("PAYMENT_PENDING");
        assertThat(reservation(fixture)).isEqualTo("HELD");
        assertThat(balance()).isZero();
    }

    @Test
    void localEventWithoutATransactionDoesNotBypassTheCommitBoundary() {
        OrderConfirmedEvent event = new OrderConfirmedEvent("OrderConfirmed", 1, "order-confirmed:v1:42", 42L,
            Instant.parse("2026-10-29T00:00:00Z"), List.of(new OrderConfirmedEvent.Item(7L, 2)));

        events.publishEvent(event);

        verifyNoInteractions(publisher);
    }

    @Test
    void concurrentSuccessfulObservationsUseTheExistingLocksToPublishOnlyTheFirstConfirmation() throws Exception {
        Fixture fixture = prepared();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> concurrentApply(fixture, ready, start));
            var second = workers.submit(() -> concurrentApply(fixture, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(PaymentObservationResult.APPLIED, PaymentObservationResult.UNCHANGED);
            assertThat(publishedOnce().confirmedAt()).isEqualTo(orders.getDetail("alice", fixture.orderId()).confirmedAt().toInstant());
            assertConfirmed(fixture);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void publisherFailureDoesNotChangeTheCommittedResultOrCauseARepeatedPublication() {
        Fixture fixture = prepared();
        doThrow(new IllegalStateException("test producer unavailable")).when(publisher).publish(any(OrderConfirmedEvent.class));

        assertThat(apply(fixture, PaymentTransaction.Status.SUCCESS).orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertConfirmed(fixture);
        assertThat(apply(fixture, PaymentTransaction.Status.SUCCESS).observationResult())
            .isEqualTo(PaymentObservationResult.UNCHANGED);

        assertThat(publishedOnce().orderId()).isEqualTo(fixture.orderId());
        assertConfirmed(fixture);
    }

    @Test
    void historicalPointConfirmationAndReadsDoNotCreatePgConfirmationEvents() {
        Fixture fixture = draft();
        jdbc.update("UPDATE user SET point_balance=1000 WHERE id=1");
        OrderInfo historical = orders.confirm("alice", fixture.orderId());

        assertThat(orders.getDetail("alice", fixture.orderId())).isEqualTo(historical);
        assertThat(jdbc.queryForObject("SELECT payment_method FROM `order` WHERE id=?", String.class, fixture.orderId()))
            .isEqualTo("POINT");
        assertThat(balance()).isEqualTo(800L);
        verifyNoInteractions(publisher);
    }

    private PaymentObservationResult concurrentApply(Fixture fixture, CountDownLatch ready,
                                                      CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent test start was not released");
        }
        return apply(fixture, PaymentTransaction.Status.SUCCESS).observationResult();
    }

    private Fixture draft() {
        Product product = products.save(new Product(brands.save(new Brand("브랜드")), "상품", 100L, 5));
        long orderId = orders.create("alice", List.of(new OrderQuantities.Item(product.getId(), 2))).orderId();
        return new Fixture(product.getId(), orderId, UUID.randomUUID(), "LP-ORDER-" + orderId);
    }

    private Fixture prepared() {
        Fixture fixture = draft();
        prepare(fixture);
        return fixture;
    }

    private void prepare(Fixture fixture) {
        payments.prepareNew("alice", fixture.orderId(), fixture.attemptKey(), ACCOUNT, fixture.providerOrderId(),
            "TEST", "0000-0000-0000-0000");
    }

    private OrderPaymentStockService.AppliedPayment apply(Fixture fixture, PaymentTransaction.Status status) {
        return payments.apply(fixture.orderId(), fixture.attemptKey(),
            new PaymentTransaction(ACCOUNT, fixture.providerOrderId(), "TX-1", 200L, status));
    }

    private OrderConfirmedEvent publishedOnce() {
        ArgumentCaptor<OrderConfirmedEvent> event = ArgumentCaptor.forClass(OrderConfirmedEvent.class);
        verify(publisher).publish(event.capture());
        verifyNoMoreInteractions(publisher);
        return event.getValue();
    }

    private void assertConfirmed(Fixture fixture) {
        assertThat(orderStatus(fixture)).isEqualTo("CONFIRMED");
        assertThat(reservation(fixture)).isEqualTo("CONSUMED");
        assertThat(stock(fixture)).isEqualTo(3);
        assertThat(orders.getDetail("alice", fixture.orderId()).paidAmount()).isEqualTo(200L);
        assertThat(balance()).isZero();
    }

    private String orderStatus(Fixture fixture) {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id=?", String.class, fixture.orderId());
    }

    private String reservation(Fixture fixture) {
        return jdbc.queryForObject("SELECT reservation_status FROM order_item WHERE order_id=?", String.class, fixture.orderId());
    }

    private int stock(Fixture fixture) {
        return jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, fixture.productId());
    }

    private long balance() {
        return jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class);
    }

    private long connectionId() {
        return jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
    }

    private TransactionTemplate tx() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private record Fixture(long productId, long orderId, UUID attemptKey, String providerOrderId) {
    }

    private record CommittedObservation(long connectionId, OrderInfo order, String reservation, long balance) {
    }
}
