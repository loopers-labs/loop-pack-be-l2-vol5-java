package com.loopers.infrastructure.payment;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderItemReservationStatus;
import com.loopers.domain.order.OrderPaymentMethod;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentObservationResult;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import com.loopers.domain.payment.PaymentRequestConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PaymentAttemptPersistenceIntegrationTest {
    private static final ZonedDateTime START = ZonedDateTime.parse("2026-10-23T12:34:56.123456+09:00");
    private static final String ACCOUNT = "135135";
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanUp;

    @AfterEach
    void tearDown() {
        cleanUp.truncateAllTables();
    }

    @Test
    void preservesNormalizedDispatchTimeAcrossReloadAndDuplicateStart() {
        long id = draft();
        ZonedDateTime precise = START.withNano(123456789);
        tx().executeWithoutResult(status -> {
            entityManager.find(Order.class, id).preparePgPayment();
            PaymentAttempt attempt = attempt(id, UUID.randomUUID(), "LP-PRECISE");
            attempt.startDispatch(precise);
            assertThat(attempt.getSendStartedAt()).isEqualTo(START);
            attempts.save(attempt);
            entityManager.flush();
        });
        tx().executeWithoutResult(status -> {
            PaymentAttempt loaded = attempts.findByOrderId(id).orElseThrow();
            assertThat(loaded.getSendStartedAt().toInstant()).isEqualTo(START.toInstant());
            assertThat(loaded.startDispatch(precise.plusDays(1))).isFalse();
            assertThat(loaded.getSendStartedAt().toInstant()).isEqualTo(START.toInstant());
        });
    }

    @Test
    void reloadsUnknownWithTheSameIdentityDispatchTimeAndTransactionThroughEveryLookup() {
        long orderId = draft();
        UUID key = UUID.randomUUID();
        tx().executeWithoutResult(status -> {
            entityManager.find(Order.class, orderId).preparePgPayment();
            PaymentAttempt attempt = attempt(orderId, key, "LP-ORDER-1");
            assertThat(attempt.startDispatch(START)).isTrue();
            assertThat(attempt.observe(transaction("LP-ORDER-1", "TX-1", PaymentTransaction.Status.PENDING)))
                .isEqualTo(PaymentObservationResult.APPLIED);
            attempt.markUnknown();
            attempts.save(attempt);
            entityManager.flush();
        });

        tx().executeWithoutResult(status -> {
            PaymentAttempt loaded = attempts.findByOrderId(orderId).orElseThrow();
            assertThat(loaded.getAttemptKey()).isEqualTo(key);
            assertThat(loaded.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(loaded.getSendStartedAt().toInstant()).isEqualTo(START.toInstant());
            assertThat(loaded.getTransactionKey()).isEqualTo("TX-1");
            assertThat(loaded.getAmount()).isEqualTo(200L);
            assertThat(loaded.startDispatch(START.plusHours(1))).isFalse();
            assertThat(attempts.findByAttemptKey(key)).containsSame(loaded);
            assertThat(attempts.findByProviderOrderId(ACCOUNT, "LP-ORDER-1")).containsSame(loaded);
            assertThat(attempts.findByTransactionKey(ACCOUNT, "TX-1")).containsSame(loaded);
            assertThat(attempts.lockByOrderId(orderId)).containsSame(loaded);
            assertThat(attempts.findByTransactionKey("other", "TX-1")).isEmpty();
            assertThat(attempts.findByProviderOrderId(ACCOUNT, "lp-order-1")).isEmpty();
            assertThat(attempts.findByTransactionKey(ACCOUNT, "TX-1 ")).isEmpty();
        });
    }

    @ParameterizedTest
    @EnumSource(value = PaymentTransaction.Status.class, names = {"SUCCESS", "FAILED"})
    void terminalOrderReservationAndAttemptSurviveReloadAndDuplicateObservation(PaymentTransaction.Status result) {
        long id = draft();
        tx().executeWithoutResult(status -> {
            Order order = entityManager.find(Order.class, id);
            order.preparePgPayment();
            PaymentAttempt attempt = attempt(id, UUID.randomUUID(), "LP-ORDER-2");
            attempt.startDispatch(START);
            attempt.observe(transaction("LP-ORDER-2", "TX-2", result));
            if (result == PaymentTransaction.Status.SUCCESS) {
                order.confirmPgPayment(START.plusSeconds(1));
            } else {
                order.failPgPayment();
            }
            attempts.save(attempt);
            entityManager.flush();
        });
        tx().executeWithoutResult(status -> {
            Order order = entityManager.find(Order.class, id);
            PaymentAttempt attempt = attempts.lockByOrderId(id).orElseThrow();
            assertThat(attempt.observe(transaction("LP-ORDER-2", "TX-2", result)))
                .isEqualTo(PaymentObservationResult.UNCHANGED);
            assertThat(attempt.markUnknown()).isEqualTo(PaymentObservationResult.UNCHANGED);
            assertThat(order.getPaymentMethod()).isEqualTo(OrderPaymentMethod.PG);
            boolean success = result == PaymentTransaction.Status.SUCCESS;
            assertThat(order.getStatus()).isEqualTo(success ? OrderStatus.CONFIRMED : OrderStatus.PAYMENT_FAILED);
            assertThat(order.getItems()).extracting(OrderItem::getReservationStatus)
                .containsOnly(success ? OrderItemReservationStatus.CONSUMED : OrderItemReservationStatus.RELEASED);
            if (success) {
                assertThat(order.confirmPgPayment(START.plusDays(1))).isFalse();
                assertThat(order.getPaidAmount()).isEqualTo(200L);
                assertThat(order.getConfirmedAt().toInstant()).isEqualTo(START.plusSeconds(1).toInstant());
            } else {
                assertThat(order.failPgPayment()).isFalse();
                assertThat(order.getPaidAmount()).isNull();
                assertThat(order.getConfirmedAt()).isNull();
            }
        });
    }

    @Test
    void noEffectFailurePersistsWithoutInventingATransactionKey() {
        long id = draft();
        tx().executeWithoutResult(status -> {
            Order order = entityManager.find(Order.class, id);
            order.preparePgPayment();
            PaymentAttempt attempt = attempt(id, UUID.randomUUID(), "LP-ORDER-3");
            attempt.startDispatch(START);
            attempt.observeRejectedBeforeCreate(ACCOUNT, "LP-ORDER-3", 200L);
            order.failPgPayment();
            attempts.save(attempt);
        });
        tx().executeWithoutResult(status -> {
            PaymentAttempt loaded = attempts.findByOrderId(id).orElseThrow();
            assertThat(loaded.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(loaded.getTransactionKey()).isNull();
            assertThat(loaded.observeRejectedBeforeCreate(ACCOUNT, "LP-ORDER-3", 200L))
                .isEqualTo(PaymentObservationResult.UNCHANGED);
            assertThat(loaded.startDispatch(START)).isFalse();
        });
    }

    @Test
    void rollsBackOrderItemsAndAttemptAfterActualFlushAndReadsAgainInAnotherTransaction() {
        long id = draft();
        IllegalStateException injected = new IllegalStateException("after actual flush");
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            entityManager.find(Order.class, id).preparePgPayment();
            PaymentAttempt attempt = attempt(id, UUID.randomUUID(), "LP-ORDER-4");
            attempt.startDispatch(START);
            attempts.save(attempt);
            entityManager.flush();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=?", String.class, id))
                .isEqualTo("PAYMENT_PENDING");
            throw injected;
        })).isSameAs(injected);
        tx().executeWithoutResult(status -> {
            Order order = entityManager.find(Order.class, id);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(order.getPaymentMethod()).isNull();
            assertThat(order.getItems()).extracting(OrderItem::getReservationStatus)
                .containsOnly(OrderItemReservationStatus.NOT_RESERVED);
            assertThat(attempts.findByOrderId(id)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Long.class)).isZero();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"order", "key", "providerOrder", "transaction"})
    void databaseRejectsASecondConflictingAttempt(String duplicate) {
        long firstId = draft();
        long secondId = draft();
        UUID key = UUID.randomUUID();
        tx().executeWithoutResult(status -> {
            entityManager.find(Order.class, firstId).preparePgPayment();
            PaymentAttempt first = attempt(firstId, key, "LP-ORDER-5");
            first.startDispatch(START);
            first.observe(transaction("LP-ORDER-5", "TX-5", PaymentTransaction.Status.PENDING));
            attempts.save(first);
        });
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            if (!duplicate.equals("order")) {
                entityManager.find(Order.class, secondId).preparePgPayment();
            }
            String providerOrder = duplicate.equals("providerOrder") ? "LP-ORDER-5" : "LP-ORDER-6";
            PaymentAttempt second = attempt(duplicate.equals("order") ? firstId : secondId,
                duplicate.equals("key") ? key : UUID.randomUUID(), providerOrder);
            second.startDispatch(START);
            second.observe(transaction(providerOrder, duplicate.equals("transaction") ? "TX-5" : "TX-6",
                PaymentTransaction.Status.PENDING));
            attempts.save(second);
            entityManager.flush();
        })).isInstanceOf(duplicate.equals("key") ? PaymentRequestConflictException.class : DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Long.class)).isEqualTo(1L);
    }

    private long draft() {
        return tx().execute(status -> {
            User user = entityManager.find(User.class, 1L);
            if (user == null) {
                user = new User(1L);
                entityManager.persist(user);
            }
            Brand brand = new Brand("브랜드");
            entityManager.persist(brand);
            Product product = new Product(brand, "상품", 100, 5);
            entityManager.persist(product);
            Order order = Order.create(user, List.of(new OrderItem(product, 2)));
            entityManager.persist(order);
            return order.getId();
        });
    }

    private PaymentAttempt attempt(long orderId, UUID key, String providerOrder) {
        return new PaymentAttempt(orderId, key, ACCOUNT, providerOrder, 200L);
    }

    private PaymentTransaction transaction(String providerOrder, String key, PaymentTransaction.Status result) {
        return new PaymentTransaction(ACCOUNT, providerOrder, key, 200L, result);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }
}
