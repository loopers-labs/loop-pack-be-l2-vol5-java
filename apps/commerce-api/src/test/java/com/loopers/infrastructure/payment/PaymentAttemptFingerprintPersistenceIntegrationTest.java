package com.loopers.infrastructure.payment;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.like.ProductLike;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderItemReservationStatus;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentFingerprintInput;
import com.loopers.domain.payment.PaymentFingerprintUnavailableException;
import com.loopers.domain.payment.PaymentRequestFingerprint;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductListRevision;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.brand.BrandNameConverter;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Own container only: does not load application.yml or connect to the user's datasource. */
@Testcontainers
class PaymentAttemptFingerprintPersistenceIntegrationTest {
    private static final ZonedDateTime START = ZonedDateTime.parse("2026-10-23T00:01:02.123456Z");
    private static final UUID KEY = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final List<String> SCRIPTS = List.of("001-brand.sql", "002-user.sql", "003-product.sql",
        "004-like.sql", "005-order.sql", "006-order-item.sql", "007-product-brand-price-index.sql",
        "008-product-list-revision.sql", "009-order-payment-state.sql", "010-payment-attempt.sql",
        "011-payment-request-fingerprint.sql");
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w5_fingerprint_storage_test");
    private static SessionFactory factory;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void createsOwnedSchemaAndValidatesMappingsWithoutAutomaticDdl() {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(source);
        var scripts = new ResourceDatabasePopulator();
        SCRIPTS.forEach(path -> scripts.addScript(new ClassPathResource("db/schema/" + path)));
        scripts.execute(source);
        var configuration = new Configuration();
        for (var type : List.of(Brand.class, User.class, Product.class, ProductLike.class, Order.class, OrderItem.class,
            ProductListRevision.class, PaymentAttempt.class)) {
            configuration.addAnnotatedClass(type);
        }
        configuration.addAttributeConverter(BrandNameConverter.class, true);
        configuration.getProperties().put("hibernate.connection.datasource", source);
        configuration.setProperty("hibernate.hbm2ddl.auto", "validate");
        configuration.setProperty("hibernate.show_sql", "false");
        configuration.setProperty("hibernate.timezone.default_storage", "NORMALIZE_UTC");
        configuration.setProperty("hibernate.jdbc.time_zone", "UTC");
        factory = configuration.buildSessionFactory();
    }

    @AfterAll
    static void closesOnlyOwnedResources() {
        if (factory != null) {
            factory.close();
        }
    }

    @BeforeEach
    void resetsOnlyTheOwnedTestContainer() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("w5_fingerprint_storage_test");
        for (String table : List.of("payment_attempt", "order_item", "like", "order", "product", "user", "brand")) {
            jdbc.update("DELETE FROM `" + table + "`");
        }
        jdbc.update("UPDATE product_list_revision SET revision = 41 WHERE id = 1");
    }

    @ParameterizedTest
    @EnumSource(PaymentAttemptStatus.class)
    void roundTripsAndComparesTheFingerprintAfterReloadInEveryState(PaymentAttemptStatus state) {
        long orderId = draft();
        var original = fingerprint(orderId, false);
        inTx(session -> {
            var order = session.find(Order.class, orderId);
            order.preparePgPayment();
            var attempt = attempt(orderId, original);
            moveTo(attempt, state);
            if (state == PaymentAttemptStatus.SUCCESS) {
                order.confirmPgPayment(START.plusSeconds(1));
            } else if (state == PaymentAttemptStatus.FAILED) {
                order.failPgPayment();
            }
            session.persist(attempt);
        });

        inTx(session -> {
            var loaded = load(session, orderId);
            assertThat(loaded.getStatus()).isEqualTo(state);
            var loadedOrder = session.find(Order.class, orderId);
            var expectedOrder = switch (state) {
                case SUCCESS -> OrderStatus.CONFIRMED;
                case FAILED -> OrderStatus.PAYMENT_FAILED;
                default -> OrderStatus.PAYMENT_PENDING;
            };
            var expectedReservation = switch (state) {
                case SUCCESS -> OrderItemReservationStatus.CONSUMED;
                case FAILED -> OrderItemReservationStatus.RELEASED;
                default -> OrderItemReservationStatus.HELD;
            };
            assertThat(loadedOrder.getStatus()).isEqualTo(expectedOrder);
            assertThat(loadedOrder.getItems().getFirst().getReservationStatus()).isEqualTo(expectedReservation);
            var stored = loaded.requireRequestFingerprint();
            assertThat(stored.fingerprintVersion()).isEqualTo(1);
            assertThat(stored.keyVersion()).isEqualTo(7);
            assertThat(Arrays.equals(stored.digest(), original.digest())).isTrue();
            assertThat(calculator().matches(input(orderId, false), stored)).isTrue();
            assertThat(calculator().matches(input(orderId, true), stored)).isFalse();
            stored.digest()[0] ^= 1;
            loaded.markUnknown();
            assertThat(loaded.startDispatch(START.plusDays(1))).isFalse();
        });
        inTx(session -> assertThat(Arrays.equals(load(session, orderId).requireRequestFingerprint().digest(),
            original.digest())).isTrue());
        assertThat(jdbc.queryForObject("SELECT OCTET_LENGTH(request_fingerprint) FROM payment_attempt", Integer.class))
            .isEqualTo(32);
    }

    @Test
    void legacyNullValuesSurviveReloadAndAuthoritativeResultRecovery() {
        long orderId = draft();
        inTx(session -> {
            session.find(Order.class, orderId).preparePgPayment();
            var legacy = new PaymentAttempt(orderId, KEY, "135135", providerOrder(orderId), 200L);
            legacy.startDispatch(START);
            legacy.markUnknown();
            session.persist(legacy);
        });
        inTx(session -> {
            var legacy = load(session, orderId);
            assertThat(legacy.getRequestFingerprint()).isEmpty();
            assertThatThrownBy(legacy::requireRequestFingerprint).isInstanceOf(PaymentFingerprintUnavailableException.class);
            legacy.observe(transaction(orderId, PaymentTransaction.Status.SUCCESS));
            session.find(Order.class, orderId).confirmPgPayment(START.plusSeconds(1));
        });
        inTx(session -> {
            var legacy = load(session, orderId);
            assertThat(legacy.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
            assertThat(legacy.getRequestFingerprint()).isEmpty();
            assertThat(legacy.getTransactionKey()).isEqualTo("TEST-TX-" + orderId);
            assertThat(session.find(Order.class, orderId).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        });
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM payment_attempt WHERE request_fingerprint IS NULL
                AND fingerprint_version IS NULL AND key_version IS NULL
            """, Integer.class)).isEqualTo(1);
    }

    @Test
    void flushThenFailureRollsBackFingerprintAttemptOrderAndReservationTogether() {
        long orderId = draft();
        var original = fingerprint(orderId, false);
        long[] writingConnection = new long[1];
        var injected = new IllegalStateException("after real fingerprint INSERT");
        assertThatThrownBy(() -> inTx(session -> {
            session.find(Order.class, orderId).preparePgPayment();
            var attempt = attempt(orderId, original);
            attempt.startDispatch(START);
            session.persist(attempt);
            session.flush();
            writingConnection[0] = session.createNativeQuery("SELECT CONNECTION_ID()", Long.class).getSingleResult();
            assertThat(session.createNativeQuery("SELECT COUNT(*) FROM payment_attempt WHERE request_fingerprint IS NOT NULL",
                Long.class).getSingleResult()).isEqualTo(1L);
            assertThat(session.createNativeQuery("SELECT reservation_status FROM order_item", String.class)
                .getSingleResult()).isEqualTo("HELD");
            throw injected;
        })).isSameAs(injected);

        inTx(session -> {
            assertThat(session.createNativeQuery("SELECT CONNECTION_ID()", Long.class).getSingleResult())
                .isNotEqualTo(writingConnection[0]);
            var order = session.find(Order.class, orderId);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(order.getPaymentMethod()).isNull();
            assertThat(order.getItems().getFirst().getReservationStatus()).isEqualTo(OrderItemReservationStatus.NOT_RESERVED);
            assertThat(session.createQuery("select count(p) from PaymentAttempt p", Long.class).getSingleResult()).isZero();
        });
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT point_balance FROM `user`", Long.class)).isEqualTo(12345L);
        assertThat(jdbc.queryForObject("SELECT revision FROM product_list_revision WHERE id=1", Long.class)).isEqualTo(41L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentInsertsKeepExactlyTheWinnersFingerprint(boolean differentContent) throws Exception {
        long orderId = draft();
        inTx(session -> session.find(Order.class, orderId).preparePgPayment());
        var first = fingerprint(orderId, false);
        var second = fingerprint(orderId, differentContent);
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> concurrentInsert(orderId, first, barrier));
            var two = pool.submit(() -> concurrentInsert(orderId, second, barrier));
            boolean firstWon = one.get(10, TimeUnit.SECONDS);
            boolean secondWon = two.get(10, TimeUnit.SECONDS);
            assertThat(firstWon ^ secondWon).isTrue();
            var winner = firstWon ? first : second;
            inTx(session -> assertThat(Arrays.equals(load(session, orderId).requireRequestFingerprint().digest(),
                winner.digest())).isTrue());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Integer.class)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void persistingPositiveFutureVersionsDoesNotInventSupportForComparingThem() {
        long orderId = draft();
        var future = new PaymentRequestFingerprint(2, 99, fingerprint(orderId, false).digest());
        inTx(session -> {
            session.find(Order.class, orderId).preparePgPayment();
            session.persist(attempt(orderId, future));
        });
        inTx(session -> {
            var stored = load(session, orderId).requireRequestFingerprint();
            assertThat(stored.fingerprintVersion()).isEqualTo(2);
            assertThat(stored.keyVersion()).isEqualTo(99);
            assertThatThrownBy(() -> calculator().matches(input(orderId, false), stored))
                .isInstanceOf(PaymentFingerprintUnavailableException.class);
        });
    }

    private static boolean concurrentInsert(long orderId, PaymentRequestFingerprint fingerprint, CyclicBarrier barrier)
        throws Exception {
        try (var session = factory.openSession()) {
            var tx = session.beginTransaction();
            try {
                barrier.await(5, TimeUnit.SECONDS);
                var attempt = attempt(orderId, fingerprint);
                attempt.startDispatch(START);
                session.persist(attempt);
                tx.commit();
                return true;
            } catch (ConstraintViolationException exception) {
                tx.rollback();
                assertThat(exception.getSQLException().getErrorCode()).isEqualTo(1062);
                return false;
            } catch (Exception exception) {
                if (tx.isActive()) {
                    tx.rollback();
                }
                throw exception;
            }
        }
    }

    private static void inTx(Consumer<Session> work) {
        try (var session = factory.openSession()) {
            var tx = session.beginTransaction();
            try {
                work.accept(session);
                tx.commit();
            } catch (RuntimeException | Error failure) {
                if (tx.isActive()) {
                    tx.rollback();
                }
                throw failure;
            }
        }
    }

    private static long draft() {
        long[] id = new long[1];
        inTx(session -> {
            var user = new User(1L);
            user.charge(12345L);
            session.persist(user);
            var brand = new Brand("브랜드");
            session.persist(brand);
            var product = new Product(brand, "상품", 100L, 5);
            session.persist(product);
            var order = Order.create(user, List.of(new OrderItem(product, 2)));
            session.persist(order);
            id[0] = order.getId();
        });
        return id[0];
    }

    private static PaymentAttempt load(Session session, long orderId) {
        return session.createQuery("from PaymentAttempt where orderId=:id", PaymentAttempt.class)
            .setParameter("id", orderId).getSingleResult();
    }

    private static PaymentAttempt attempt(long orderId, PaymentRequestFingerprint fingerprint) {
        return new PaymentAttempt(orderId, KEY, "135135", providerOrder(orderId), 200L, fingerprint);
    }

    private static String providerOrder(long orderId) {
        return "LP-ORD-" + orderId;
    }

    private static PaymentTransaction transaction(long orderId, PaymentTransaction.Status state) {
        return new PaymentTransaction("135135", providerOrder(orderId), "TEST-TX-" + orderId, 200L, state);
    }

    private static void moveTo(PaymentAttempt attempt, PaymentAttemptStatus state) {
        attempt.startDispatch(START);
        if (state == PaymentAttemptStatus.UNKNOWN) {
            attempt.markUnknown();
        } else if (state != PaymentAttemptStatus.NOT_OBSERVED) {
            attempt.observe(transaction(attempt.getOrderId(), PaymentTransaction.Status.valueOf(state.name())));
        }
    }

    private static PaymentRequestFingerprint fingerprint(long orderId, boolean changedCard) {
        return calculator().calculate(input(orderId, changedCard));
    }

    private static PaymentFingerprintInput input(long orderId, boolean changedCard) {
        return new PaymentFingerprintInput(1L, orderId, 200L, "TEST",
            changedCard ? "0000-0000-0000-0001" : "0000-0000-0000-0000");
    }

    private static HmacPaymentRequestFingerprinter calculator() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x0b);
        return new HmacPaymentRequestFingerprinter(7, key);
    }
}
