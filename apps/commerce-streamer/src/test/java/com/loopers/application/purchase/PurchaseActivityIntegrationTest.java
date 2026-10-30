package com.loopers.application.purchase;

import com.loopers.domain.purchase.OrderConfirmation;
import com.loopers.domain.purchase.PurchaseActivityConflictException;
import com.loopers.domain.purchase.PurchaseActivityStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/** Only the class-owned MySQL is used. The manual DDL, not Hibernate create, builds the schema. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "commerce.purchase-activity.auto-startup=false", "demo-kafka.test.enabled=false"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(30)
class PurchaseActivityIntegrationTest {
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-29T01:02:03.123456789Z");
    private static final OrderConfirmation EVENT = event("event-42", 42L, CONFIRMED_AT,
        List.of(new OrderConfirmation.Item(7L, 2), new OrderConfirmation.Item(8L, 1)));

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w6_purchase_activity_test")
        .withInitScript("db/schema/001-purchase-activity.sql");

    @Autowired private PurchaseActivityHandler handler;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private PurchaseActivityStore store;
    private boolean workersTerminated = true;

    @DynamicPropertySource
    static void isolateDatabase(DynamicPropertyRegistry properties) {
        properties.add("datasource.mysql-jpa.main.jdbc-url", MYSQL::getJdbcUrl);
        properties.add("datasource.mysql-jpa.main.username", MYSQL::getUsername);
        properties.add("datasource.mysql-jpa.main.password", MYSQL::getPassword);
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:1");
        properties.add("spring.kafka.admin.properties.bootstrap.servers", () -> "127.0.0.1:1");
    }

    @AfterEach
    void cleanOnlyOwnedRows() {
        assertThat(workersTerminated).isTrue();
        reset(target());
        jdbc.update("DELETE FROM product_purchase_activity");
        jdbc.update("DELETE FROM processed_order_confirmation");
    }

    @Test
    void storesEachOrdersItemsAndPreservesEveryNanosecondWithoutCurrentProductRows() {
        assertThat(handler.consume(EVENT)).isEqualTo(PurchaseActivityHandler.Result.APPLIED);
        assertThat(handler.consume(event("event-43", 43L, CONFIRMED_AT,
            List.of(new OrderConfirmation.Item(7L, 3))))).isEqualTo(PurchaseActivityHandler.Result.APPLIED);

        assertCounts(2, 3);
        assertThat(jdbc.queryForObject("SELECT SUM(quantity) FROM product_purchase_activity WHERE product_id=7", Long.class))
            .isEqualTo(5L);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(store.findByEventId(EVENT.eventId())).contains(EVENT);
            assertThat(store.findByOrderId(EVENT.orderId())).contains(EVENT);
        });
        assertThat(jdbc.queryForObject("SELECT confirmed_at_nano FROM processed_order_confirmation WHERE order_id=42", Integer.class))
            .isEqualTo(123456789);
        assertThat(jdbc.queryForList("SELECT confirmed_at_nano FROM product_purchase_activity WHERE order_id=42", Integer.class))
            .containsExactly(123456789, 123456789);
    }

    @Test
    void reorderedAndSplitItemsAreAnIdenticalReplayAfterTheFailedInsertTransactionRollsBack() {
        handler.consume(EVENT);
        AtomicBoolean readInFreshReadOnlyTransaction = new AtomicBoolean();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            readInFreshReadOnlyTransaction.set(true);
            return invocation.callRealMethod();
        }).when(target()).findByEventId(EVENT.eventId());
        OrderConfirmation reordered = event(EVENT.eventId(), EVENT.orderId(), CONFIRMED_AT,
            List.of(new OrderConfirmation.Item(8L, 1), new OrderConfirmation.Item(7L, 1), new OrderConfirmation.Item(7L, 1)));

        assertThat(handler.consume(reordered)).isEqualTo(PurchaseActivityHandler.Result.DUPLICATE);

        assertThat(readInFreshReadOnlyTransaction).isTrue();
        assertCounts(1, 2);
    }

    @ParameterizedTest
    @MethodSource("conflictingFacts")
    void conflictingIdentityOrFactDoesNotChangeTheFirstStoredPurchase(OrderConfirmation conflicting) {
        handler.consume(EVENT);

        assertThatThrownBy(() -> handler.consume(conflicting)).isInstanceOf(PurchaseActivityConflictException.class);

        assertCounts(1, 2);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            assertThat(store.findByOrderId(42L)).contains(EVENT));
    }

    private static Stream<OrderConfirmation> conflictingFacts() {
        return Stream.of(
            event(EVENT.eventId(), 43L, CONFIRMED_AT, EVENT.items()),
            event("other-event", 42L, CONFIRMED_AT, EVENT.items()),
            event(EVENT.eventId(), 42L, CONFIRMED_AT.plusNanos(1), EVENT.items()),
            event(EVENT.eventId(), 42L, CONFIRMED_AT, List.of(new OrderConfirmation.Item(7L, 3), new OrderConfirmation.Item(8L, 1))),
            event(EVENT.eventId(), 42L, CONFIRMED_AT, List.of(new OrderConfirmation.Item(7L, 2))),
            event(EVENT.eventId(), 42L, CONFIRMED_AT,
                List.of(new OrderConfirmation.Item(7L, 2), new OrderConfirmation.Item(8L, 1), new OrderConfirmation.Item(9L, 1)))
        );
    }

    @Test
    void eventIdsUseExactCaseAndTrailingSpaceEquality() {
        handler.consume(event("Event", 42L, CONFIRMED_AT, EVENT.items()));
        handler.consume(event("event", 43L, CONFIRMED_AT, EVENT.items()));
        handler.consume(event("event ", 44L, CONFIRMED_AT, EVENT.items()));

        assertCounts(3, 6);
        assertThat(handler.consume(event("Event", 42L, CONFIRMED_AT, EVENT.items())))
            .isEqualTo(PurchaseActivityHandler.Result.DUPLICATE);
        assertThatThrownBy(() -> handler.consume(event("event ", 43L, CONFIRMED_AT, EVENT.items())))
            .isInstanceOf(PurchaseActivityConflictException.class);
        assertCounts(3, 6);
    }

    @Test
    void concurrentConsumersUseIndependentTransactionsAndTheDatabaseUniqueConstraints() throws Exception {
        CountDownLatch insertsReady = new CountDownLatch(2);
        Set<Long> connections = ConcurrentHashMap.newKeySet();
        doAnswer(invocation -> {
            connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            insertsReady.countDown();
            if (!insertsReady.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test concurrent inserts did not meet");
            }
            return invocation.callRealMethod();
        }).when(target()).insertConfirmation(EVENT);
        var workers = Executors.newFixedThreadPool(2);
        workersTerminated = false;
        try {
            var first = workers.submit(() -> handler.consume(EVENT));
            var second = workers.submit(() -> handler.consume(EVENT));
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(PurchaseActivityHandler.Result.APPLIED, PurchaseActivityHandler.Result.DUPLICATE);
            assertThat(connections).hasSize(2);
            assertCounts(1, 2);
        } finally {
            workers.shutdownNow();
            workersTerminated = workers.awaitTermination(5, TimeUnit.SECONDS);
            assertThat(workersTerminated).isTrue();
        }
    }

    @Test
    void failureAfterTheFirstRealActivityInsertRollsBackBothTablesAndCanBeRetried() {
        TestWriteFailure injected = new TestWriteFailure();
        AtomicBoolean firstSqlObserved = new AtomicBoolean();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertCounts(1, 1);
            firstSqlObserved.set(true);
            throw injected;
        }).when(target()).insertActivity(anyLong(), any(Instant.class), any(OrderConfirmation.Item.class));

        assertThatThrownBy(() -> handler.consume(EVENT)).isSameAs(injected);

        assertThat(firstSqlObserved).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertCounts(0, 0);
        reset(target());
        assertThat(handler.consume(EVENT)).isEqualTo(PurchaseActivityHandler.Result.APPLIED);
        assertCounts(1, 2);
    }

    @Test
    void anActivityUniqueViolationIsNotACompletedEventDuplicate() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            invocation.callRealMethod();
            return null;
        }).when(target()).insertActivity(anyLong(), any(Instant.class), any(OrderConfirmation.Item.class));

        assertThatThrownBy(() -> handler.consume(EVENT)).isInstanceOf(DataIntegrityViolationException.class);

        assertCounts(0, 0);
    }

    @Test
    void databaseCheckFailureIsNotACompletedEventDuplicate() {
        doAnswer(invocation -> {
            jdbc.update("INSERT INTO product_purchase_activity "
                + "(order_id,product_id,quantity,confirmed_at_second,confirmed_at_nano) VALUES (42,7,0,1,0)");
            return null;
        }).when(target()).insertActivity(anyLong(), any(Instant.class), any(OrderConfirmation.Item.class));

        assertThatThrownBy(() -> handler.consume(EVENT)).isInstanceOfSatisfying(UncategorizedSQLException.class,
            failure -> {
                assertThat(failure.getSQLException().getErrorCode()).isEqualTo(3819);
                assertThat(failure.getSQLException().getSQLState()).isEqualTo("HY000");
                assertThat(failure.getSQLException().getMessage()).contains("ck_purchase_quantity");
            });

        assertCounts(0, 0);
    }

    @Test
    void anOverlongIdentifierFailsTechnicallyWithoutTruncationOrDuplicateClassification() {
        OrderConfirmation tooLong = event("e".repeat(256), 42L, CONFIRMED_AT, EVENT.items());

        assertThatThrownBy(() -> handler.consume(tooLong)).isInstanceOf(DataIntegrityViolationException.class);

        assertCounts(0, 0);
        assertThat(handler.consume(EVENT)).isEqualTo(PurchaseActivityHandler.Result.APPLIED);
    }

    @Test
    void ambientTransactionIsRejectedBeforeAnyWrite() {
        var outer = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> handler.consume(EVENT)))
            .isInstanceOf(IllegalStateException.class);

        assertCounts(0, 0);
    }

    private void assertCounts(int completed, int activities) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_order_confirmation", Integer.class)).isEqualTo(completed);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_purchase_activity", Integer.class)).isEqualTo(activities);
    }

    private PurchaseActivityStore target() {
        return AopTestUtils.getUltimateTargetObject(store);
    }

    private static OrderConfirmation event(String id, long orderId, Instant at, List<OrderConfirmation.Item> items) {
        return new OrderConfirmation("OrderConfirmed", 1, id, orderId, at, items);
    }

    private static final class TestWriteFailure extends RuntimeException {
        private TestWriteFailure() {
            super("failure after first real item SQL");
        }
    }
}
