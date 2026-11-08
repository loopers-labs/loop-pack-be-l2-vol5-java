package com.loopers.application.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.event.DomainEvent;
import com.loopers.domain.event.DomainEventPublisher;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.outbox.OutboxMessage;
import com.loopers.domain.outbox.OutboxMessageSerializer;
import com.loopers.domain.outbox.OutboxRecorder;
import com.loopers.domain.outbox.OutboxRepository;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentObservationResult;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.payment.HttpTestPgClient;
import com.loopers.infrastructure.payment.PgHttpTimeouts;
import com.loopers.infrastructure.payment.support.LocalTestPg;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

/** Actual MySQL commits and rollbacks. The broker is never used; its former publication port must stay idle. */
@SpringBootTest(properties = {"commerce.order-events.enabled=false", "commerce.order-events.topic=w7-outbox-atomicity"})
@Timeout(30)
class OrderOutboxAtomicityIntegrationTest {
    private static final String ACCOUNT = "135135";
    private static final String CARD = "0000-0000-0000-0000";
    private static final Instant CONFIRMED_AT = Instant.parse("2026-11-04T00:01:02.123456Z");

    @TestBean(methodName = "com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration#create")
    private PaymentRequestFingerprinter fingerprinter;
    @TestBean(name = "orderPaymentClock", methodName = "fixedOrderPaymentClock")
    private Clock orderPaymentClock;
    @MockitoBean private DomainEventPublisher publisher;
    @MockitoSpyBean private OutboxMessageSerializer serializer;
    @MockitoSpyBean private OutboxRepository outbox;
    @Autowired private OutboxRecorder recorder;
    @Autowired private OrderPaymentStockService payments;
    @Autowired private OrderService orders;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private ObjectMapper mapper;

    static Clock fixedOrderPaymentClock() {
        return Clock.fixed(CONFIRMED_AT, ZoneOffset.UTC);
    }

    @BeforeEach
    void prepareOwnedTestDatabase() {
        initializer.initialize();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @AfterEach
    void releaseOnlyOwnedTestData() {
        reset(serializer, repositoryTarget());
        cleanup.truncateAllTables();
    }

    @Test
    void firstSuccessStoresItsImmutableFactBeforeCommitEvenWhenKafkaSendingIsDisabled() throws Exception {
        Fixture fixture = prepared();
        AtomicLong writingConnection = new AtomicLong();
        AtomicBoolean recordedBeforeCommit = new AtomicBoolean();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(connectionId()).isEqualTo(writingConnection.get());
            Object saved = invocation.callRealMethod();
            entityManager.flush();
            assertConfirmed(fixture);
            assertThat(outboxCount()).isEqualTo(1);
            TransactionTemplate independent = tx();
            independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            independent.executeWithoutResult(status -> {
                assertThat(connectionId()).isNotEqualTo(writingConnection.get());
                assertThat(orderStatus(fixture)).isEqualTo("PAYMENT_PENDING");
                assertThat(outboxCount()).isZero();
            });
            recordedBeforeCommit.set(true);
            return saved;
        }).when(repositoryTarget()).save(any(OutboxMessage.class));

        tx().executeWithoutResult(status -> {
            writingConnection.set(connectionId());
            apply(fixture, PaymentTransaction.Status.SUCCESS);
            entityManager.flush();
            assertThat(outboxCount()).as("listener writes at BEFORE_COMMIT, not inside publishEvent").isZero();
        });

        assertThat(recordedBeforeCommit).isTrue();
        assertConfirmed(fixture);
        assertStoredFact(fixture);
        verifyNoInteractions(publisher);
    }

    @Test
    void repeatedSuccessAndLateNonterminalResultsPreserveOneOriginalRow() {
        Fixture fixture = prepared();
        apply(fixture, PaymentTransaction.Status.SUCCESS);
        var firstRow = outboxRows();
        var firstBusiness = businessSnapshot();

        assertThat(apply(fixture, PaymentTransaction.Status.SUCCESS).observationResult())
            .isEqualTo(PaymentObservationResult.UNCHANGED);
        apply(fixture, PaymentTransaction.Status.PENDING);
        payments.markUnknown(fixture.orderId(), fixture.attemptKey());

        assertThat(outboxRows()).isEqualTo(firstRow);
        assertThat(businessSnapshot()).isEqualTo(firstBusiness);
        assertThat(outboxCount()).isEqualTo(1);
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "UNKNOWN", "FAILED", "REJECTED_NO_EFFECT"})
    void preparationAndNonSuccessfulResultsNeverCreateAnOrderConfirmedRow(String observation) {
        Fixture fixture = draft();
        assertThat(outboxCount()).isZero();
        prepare(fixture);
        assertThat(outboxCount()).isZero();

        if (observation.equals("UNKNOWN")) {
            payments.markUnknown(fixture.orderId(), fixture.attemptKey());
        } else if (observation.equals("REJECTED_NO_EFFECT")) {
            payments.rejectBeforeCreate(fixture.orderId(), fixture.attemptKey(), ACCOUNT, fixture.providerOrderId(), 200L);
        } else {
            apply(fixture, PaymentTransaction.Status.valueOf(observation));
        }

        boolean failed = observation.equals("FAILED") || observation.equals("REJECTED_NO_EFFECT");
        assertThat(orderStatus(fixture)).isEqualTo(failed ? "PAYMENT_FAILED" : "PAYMENT_PENDING");
        assertThat(reservation(fixture)).isEqualTo(failed ? "RELEASED" : "HELD");
        assertThat(stock(fixture)).isEqualTo(failed ? 5 : 3);
        assertThat(outboxCount()).isZero();
        assertThat(balance()).isZero();
        verifyNoInteractions(publisher);
    }

    @Test
    void mismatchingSuccessPreservesTheReservationWithoutCreatingAnEvent() {
        Fixture fixture = prepared();
        var before = businessSnapshot();

        var result = payments.apply(fixture.orderId(), fixture.attemptKey(),
            new PaymentTransaction(ACCOUNT, fixture.providerOrderId(), "TX-1", 201L, PaymentTransaction.Status.SUCCESS));

        assertThat(result.observationResult()).isEqualTo(PaymentObservationResult.CONFLICT);
        assertThat(businessSnapshot()).isEqualTo(before);
        assertThat(outboxCount()).isZero();
        verifyNoInteractions(publisher);
    }

    @Test
    void historicalPointConfirmationAndReadsDoNotCreatePgEvents() {
        Fixture fixture = draft();
        jdbc.update("UPDATE user SET point_balance=1000 WHERE id=1");
        OrderInfo historical = orders.confirm("alice", fixture.orderId());

        assertThat(orders.getDetail("alice", fixture.orderId())).isEqualTo(historical);
        assertThat(jdbc.queryForObject("SELECT payment_method FROM `order` WHERE id=?", String.class, fixture.orderId()))
            .isEqualTo("POINT");
        assertThat(balance()).isEqualTo(800L);
        assertThat(outboxCount()).isZero();
        verifyNoInteractions(publisher);
    }

    @Test
    void concurrentSuccessesKeepTheExistingLocksAndStoreOnlyTheFirstConfirmation() throws Exception {
        Fixture fixture = prepared();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> concurrentApply(fixture, ready, start));
            var second = workers.submit(() -> concurrentApply(fixture, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(PaymentObservationResult.APPLIED, PaymentObservationResult.UNCHANGED);
            assertConfirmed(fixture);
            assertStoredFact(fixture);
            verifyNoInteractions(publisher);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void serializationFailureAfterRealBusinessSqlRollsBackTheExistingOrderAttemptAndReservation() {
        Fixture fixture = prepared();
        var before = businessSnapshot();
        AtomicBoolean sqlObserved = new AtomicBoolean();
        var injected = new IllegalStateException("test serialization failure");
        doAnswer(invocation -> {
            entityManager.flush();
            assertConfirmed(fixture);
            assertThat(outboxCount()).isZero();
            sqlObserved.set(true);
            throw injected;
        }).when(serializer).serialize(any(DomainEvent.class));

        assertThatThrownBy(() -> apply(fixture, PaymentTransaction.Status.SUCCESS)).isSameAs(injected);

        assertThat(sqlObserved).isTrue();
        assertRolledBack(fixture, before);
        verifyNoInteractions(publisher);
    }

    @Test
    void actualMysqlInsertRejectionRollsBackBusinessSqlAndCanBeRetriedAfterTheCauseIsRemoved() {
        Fixture fixture = prepared();
        var before = businessSnapshot();
        AtomicBoolean sqlObserved = new AtomicBoolean();
        doAnswer(invocation -> {
            entityManager.flush();
            assertConfirmed(fixture);
            sqlObserved.set(true);
            return invocation.callRealMethod();
        }).when(repositoryTarget()).save(any(OutboxMessage.class));
        // Only this test's disposable MySQL is connected. A temporary constraint rejects the actual INSERT.
        jdbc.execute("ALTER TABLE outbox_message ADD CONSTRAINT w7_outbox_reject_insert "
            + "CHECK (event_type <> 'OrderConfirmed')");
        try {
            assertThatThrownBy(() -> apply(fixture, PaymentTransaction.Status.SUCCESS))
                .isInstanceOf(DataAccessException.class);
            assertThat(sqlObserved).isTrue();
            assertRolledBack(fixture, before);
        } finally {
            jdbc.execute("ALTER TABLE outbox_message DROP CHECK w7_outbox_reject_insert");
            reset(repositoryTarget());
        }

        apply(fixture, PaymentTransaction.Status.SUCCESS);
        assertConfirmed(fixture);
        assertThat(outboxCount()).isEqualTo(1);
        verifyNoInteractions(publisher);
    }

    @Test
    void failureAfterActualOutboxInsertRollsBackEveryWriteIncludingThatInsertedRow() {
        Fixture fixture = prepared();
        var before = businessSnapshot();
        AtomicBoolean inserted = new AtomicBoolean();
        var injected = new InjectedOutboxFailure();
        failAfterRealInsert(fixture, inserted, injected);

        assertThatThrownBy(() -> apply(fixture, PaymentTransaction.Status.SUCCESS)).isSameAs(injected);

        assertThat(inserted).isTrue();
        assertRolledBack(fixture, before);
        verifyNoInteractions(publisher);
    }

    @Test
    void outerRollbackAfterBusinessFlushNeverRunsTheBeforeCommitInsert() {
        Fixture fixture = prepared();
        var before = businessSnapshot();
        var injected = new IllegalStateException("test outer transaction failure");

        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            apply(fixture, PaymentTransaction.Status.SUCCESS);
            entityManager.flush();
            assertConfirmed(fixture);
            assertThat(outboxCount()).isZero();
            throw injected;
        })).isSameAs(injected);

        assertRolledBack(fixture, before);
        verifyNoInteractions(publisher);
    }

    @Test
    void pgSuccessSurvivesLocalRollbackAndSameTransactionLookupRecoversWithoutAnotherCreate() throws Exception {
        Fixture fixture = draft();
        try (var pg = new LocalTestPg();
             var client = new HttpTestPgClient(pg.baseUri(), pg.callbackUri(), mapper, PgHttpTimeouts.defaults())) {
            var coordinator = new OrderPaymentCoordinator(payments, client);
            coordinator.startNew("alice", fixture.orderId(), fixture.attemptKey(), fixture.providerOrderId(), "TEST", CARD);
            String transactionKey = attempts.findByOrderId(fixture.orderId()).orElseThrow().getTransactionKey();
            pg.setStatus(transactionKey, "SUCCESS");
            var before = businessSnapshot();
            AtomicBoolean inserted = new AtomicBoolean();
            var injected = new InjectedOutboxFailure();
            failAfterRealInsert(fixture, inserted, injected);

            assertThatThrownBy(() -> coordinator.refreshOnce("alice", fixture.orderId(), fixture.attemptKey()))
                .isSameAs(injected);

            assertThat(inserted).isTrue();
            assertRolledBack(fixture, before);
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(pg.transactionCount()).isEqualTo(1);
            assertThat(client.findByTransactionKey(transactionKey).transactions()).singleElement()
                .satisfies(transaction -> assertThat(transaction.status()).isEqualTo(PaymentTransaction.Status.SUCCESS));
            int lookupsBeforeRecovery = pg.lookupCalls();
            reset(repositoryTarget());

            var restarted = new OrderPaymentCoordinator(payments, client);
            var recovered = restarted.refreshOnce("alice", fixture.orderId(), fixture.attemptKey());

            assertThat(recovered.paymentStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
            assertConfirmed(fixture);
            assertStoredFact(fixture);
            assertThat(attempts.findByOrderId(fixture.orderId()).orElseThrow().getTransactionKey()).isEqualTo(transactionKey);
            assertThat(pg.lookupCalls()).isEqualTo(lookupsBeforeRecovery + 1);
            var recoveredBusiness = businessSnapshot();
            var recoveredOutbox = outboxRows();
            restarted.refreshOnce("alice", fixture.orderId(), fixture.attemptKey());
            assertThat(pg.lookupCalls()).isEqualTo(lookupsBeforeRecovery + 1);
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(pg.transactionCount()).isEqualTo(1);
            assertThat(businessSnapshot()).isEqualTo(recoveredBusiness);
            assertThat(outboxRows()).isEqualTo(recoveredOutbox);
            verifyNoInteractions(publisher);
        }
    }

    @Test
    void localEventsAndCallsOutsideATransactionNeverCreateAnOutboxRow() {
        var event = new OrderConfirmedEvent("OrderConfirmed", 1, "order-confirmed:v1:42", 42L, CONFIRMED_AT,
            List.of(new OrderConfirmedEvent.Item(7L, 2)));

        events.publishEvent(event);
        tx().executeWithoutResult(status -> events.publishEvent("local event only"));

        assertThat(outboxCount()).isZero();
        verifyNoInteractions(publisher);
    }

    @Test
    void directRecorderInvocationRequiresTheExistingBusinessTransaction() {
        var event = new OrderConfirmedEvent("OrderConfirmed", 1, "order-confirmed:v1:42", 42L, CONFIRMED_AT,
            List.of(new OrderConfirmedEvent.Item(7L, 2)));

        assertThatThrownBy(() -> recorder.record(event)).isInstanceOf(IllegalTransactionStateException.class);

        assertThat(outboxCount()).isZero();
        verifyNoInteractions(serializer, outbox, publisher);
    }

    private void failAfterRealInsert(Fixture fixture, AtomicBoolean inserted, RuntimeException injected) {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            entityManager.flush();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertConfirmed(fixture);
            assertThat(outboxCount()).isEqualTo(1);
            inserted.set(true);
            throw injected;
        }).when(repositoryTarget()).save(any(OutboxMessage.class));
    }

    private OutboxRepository repositoryTarget() {
        return AopTestUtils.getUltimateTargetObject(outbox);
    }

    private PaymentObservationResult concurrentApply(Fixture fixture, CountDownLatch ready,
                                                      CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent test start timed out");
        }
        return apply(fixture, PaymentTransaction.Status.SUCCESS).observationResult();
    }

    private Fixture draft() {
        Product product = products.save(new Product(brands.save(new Brand("Outbox 브랜드")), "상품", 100L, 5));
        long orderId = orders.create("alice", List.of(new OrderQuantities.Item(product.getId(), 2))).orderId();
        return new Fixture(product.getId(), orderId, UUID.randomUUID(), "W7-ORDER-" + orderId);
    }

    private Fixture prepared() {
        Fixture fixture = draft();
        prepare(fixture);
        return fixture;
    }

    private void prepare(Fixture fixture) {
        payments.prepareNew("alice", fixture.orderId(), fixture.attemptKey(), ACCOUNT, fixture.providerOrderId(), "TEST", CARD);
    }

    private OrderPaymentStockService.AppliedPayment apply(Fixture fixture, PaymentTransaction.Status status) {
        return payments.apply(fixture.orderId(), fixture.attemptKey(),
            new PaymentTransaction(ACCOUNT, fixture.providerOrderId(), "TX-1", 200L, status));
    }

    private void assertStoredFact(Fixture fixture) throws Exception {
        assertThat(outboxCount()).isEqualTo(1);
        var row = jdbc.queryForMap("SELECT * FROM outbox_message");
        assertThat(row.get("event_id")).isEqualTo("order-confirmed:v1:" + fixture.orderId());
        assertThat(row.get("event_type")).isEqualTo("OrderConfirmed");
        assertThat(row.get("aggregate_type")).isEqualTo("Order");
        assertThat(row.get("aggregate_id")).isEqualTo(Long.toString(fixture.orderId()));
        assertThat(row.get("schema_version")).isEqualTo(1);
        assertThat(row.get("message_key")).isEqualTo(Long.toString(fixture.orderId()));
        assertThat(row.get("topic")).isEqualTo("w7-outbox-atomicity");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("attempt_count")).longValue()).isZero();
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("next_attempt_at")).isNotNull();
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("last_error")).isNull();
        String payload = jdbc.queryForObject("SELECT payload FROM outbox_message", String.class);
        JsonNode json = mapper.readTree(payload);
        assertThat(json.isObject()).as("Stored JSON is an object, not a doubly encoded JSON string").isTrue();
        assertThat(json.size()).isEqualTo(6);
        assertThat(json.path("eventType").asText()).isEqualTo("OrderConfirmed");
        assertThat(json.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(json.path("eventId").asText()).isEqualTo("order-confirmed:v1:" + fixture.orderId());
        assertThat(json.path("orderId").asLong()).isEqualTo(fixture.orderId());
        assertThat(Instant.parse(json.path("confirmedAt").asText())).isEqualTo(CONFIRMED_AT);
        assertThat(json.path("items").size()).isEqualTo(1);
        assertThat(json.path("items").get(0).path("productId").asLong()).isEqualTo(fixture.productId());
        assertThat(json.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
        assertThat(payload).doesNotContain("cardNo", CARD, "fingerprint", "providerAccount", "transactionKey");
    }

    private void assertConfirmed(Fixture fixture) {
        assertThat(orderStatus(fixture)).isEqualTo("CONFIRMED");
        assertThat(reservation(fixture)).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_attempt WHERE order_id=?", String.class, fixture.orderId()))
            .isEqualTo("SUCCESS");
        assertThat(stock(fixture)).isEqualTo(3);
        assertThat(orders.getDetail("alice", fixture.orderId()).paidAmount()).isEqualTo(200L);
        assertThat(balance()).isZero();
    }

    private void assertRolledBack(Fixture fixture, Map<String, List<Map<String, Object>>> before) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(businessSnapshot()).isEqualTo(before);
        assertThat(orderStatus(fixture)).isEqualTo("PAYMENT_PENDING");
        assertThat(reservation(fixture)).isEqualTo("HELD");
        assertThat(stock(fixture)).isEqualTo(3);
        assertThat(orders.getDetail("alice", fixture.orderId()).confirmedAt()).isNull();
        assertThat(orders.getDetail("alice", fixture.orderId()).paidAmount()).isNull();
        assertThat(outboxCount()).isZero();
    }

    private Map<String, List<Map<String, Object>>> businessSnapshot() {
        return Map.of("orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "attempts", jdbc.queryForList("SELECT id,order_id,HEX(attempt_key),provider_account,provider_order_id,amount,"
                + "status,send_started_at,transaction_key,HEX(request_fingerprint),fingerprint_version,key_version "
                + "FROM payment_attempt ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id"),
            "revisions", jdbc.queryForList("SELECT * FROM product_list_revision ORDER BY id"));
    }

    private List<Map<String, Object>> outboxRows() {
        return jdbc.queryForList("SELECT * FROM outbox_message ORDER BY id");
    }

    private long outboxCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_message", Long.class);
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

    private record Fixture(long productId, long orderId, UUID attemptKey, String providerOrderId) { }

    /** Test control failure, not a JPA API misuse subject to persistence exception translation. */
    private static final class InjectedOutboxFailure extends RuntimeException {
        private InjectedOutboxFailure() {
            super("test failure after outbox INSERT");
        }
    }
}
