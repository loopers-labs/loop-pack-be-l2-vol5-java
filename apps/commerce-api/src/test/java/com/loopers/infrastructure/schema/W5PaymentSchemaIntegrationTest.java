package com.loopers.infrastructure.schema;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.like.ProductLike;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderException;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderItemReservationStatus;
import com.loopers.domain.order.OrderPaymentMethod;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductListRevision;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.brand.BrandNameConverter;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.init.ScriptStatementFailedException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@Testcontainers
class W5PaymentSchemaIntegrationTest {
    private static final List<String> BASE_SCRIPTS = List.of("001-brand.sql", "002-user.sql", "003-product.sql",
        "004-like.sql", "005-order.sql", "006-order-item.sql", "007-product-brand-price-index.sql",
        "008-product-list-revision.sql");
    private static final List<String> W5_SCRIPTS = List.of("009-order-payment-state.sql", "010-payment-attempt.sql",
        "011-payment-request-fingerprint.sql");
    private static final UUID ATTEMPT_KEY = UUID.fromString("00000000-0000-0000-0000-000000001002");
    private static final ZonedDateTime STARTED_AT = ZonedDateTime.parse("2026-10-23T09:01:02.123456+09:00");
    private static final String FIRST_KEY_SQL = "UNHEX('00000000000000000000000000001002')";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w5_payment_schema_test");

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void preparesOnlyTheOwnedContainerWithTheUnmodifiedPreviousSchema() {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        // 이 테스트 클래스가 만든 컨테이너만 초기화한다. 사용자 DB·볼륨에는 접근하지 않는다.
        for (String table : List.of("payment_attempt", "product_list_revision", "order_item", "like", "order",
            "product", "user", "brand")) {
            jdbc.execute("DROP TABLE IF EXISTS `" + table + "`");
        }
        applyScripts(BASE_SCRIPTS);
    }

    @Test
    void migratesPastPointAndDraftRowsWithoutRecalculatingOrInventingPaymentFacts() {
        seedPreviousRows();
        var before = previousColumnsSnapshot();

        applyScripts(W5_SCRIPTS);

        assertThat(previousColumnsSnapshot()).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT payment_method FROM `order` ORDER BY id", String.class))
            .containsExactly("POINT", null);
        assertThat(jdbc.queryForList("SELECT reservation_status FROM order_item ORDER BY id", String.class))
            .containsExactly("NOT_RESERVED", "NOT_RESERVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Long.class)).isZero();
        try (SessionFactory factory = validatedSessionFactory(); Session session = factory.openSession()) {
            Order past = session.find(Order.class, 1001L);
            assertThat(past.getPaymentMethod()).isEqualTo(OrderPaymentMethod.POINT);
            assertThat(past.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(past.getPaidAmount()).isEqualTo(2000L);
            assertThat(past.getConfirmedAt().toInstant()).isEqualTo(ZonedDateTime.parse("2026-10-20T02:03:04.123456Z").toInstant());
            assertThat(past.getItems().getFirst().getProductName()).isEqualTo("이전 상품명");
            assertThat(past.getItems().getFirst().getUnitPrice()).isEqualTo(1000L);
            assertThat(past.getItems().getFirst().getQuantity()).isEqualTo(2);
            assertThat(past.getItems().getFirst().getReservationStatus()).isEqualTo(OrderItemReservationStatus.NOT_RESERVED);
            assertThatThrownBy(past::requireDraft).isInstanceOfSatisfying(OrderException.class,
                failure -> assertThat(failure.getReason()).isEqualTo(OrderException.Reason.ORDER_ALREADY_CONFIRMED));
            Order draft = session.find(Order.class, 1002L);
            assertThat(draft.getPaymentMethod()).isNull();
            assertThat(draft.getItems().getFirst().getReservationStatus()).isEqualTo(OrderItemReservationStatus.NOT_RESERVED);
            draft.requireDraft();
        }
        assertThat(previousColumnsSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(PaymentAttemptStatus.class)
    void validatesFreshSchemaAndRoundTripsEveryAttemptState(PaymentAttemptStatus expectedStatus) {
        applyScripts(W5_SCRIPTS);
        seedCurrentRows();
        try (SessionFactory factory = validatedSessionFactory()) {
            try (Session session = factory.openSession()) {
                var transaction = session.beginTransaction();
                Order order = session.find(Order.class, 1002L);
                order.preparePgPayment();
                PaymentAttempt attempt = new PaymentAttempt(1002, ATTEMPT_KEY, "135135", "LP-ORD-0000001002", 3000);
                attempt.startDispatch(STARTED_AT);
                if (expectedStatus == PaymentAttemptStatus.UNKNOWN) {
                    attempt.markUnknown();
                } else if (expectedStatus != PaymentAttemptStatus.NOT_OBSERVED) {
                    attempt.observe(new PaymentTransaction("135135", "LP-ORD-0000001002", "TEST-TX-1002", 3000,
                        PaymentTransaction.Status.valueOf(expectedStatus.name())));
                }
                if (expectedStatus == PaymentAttemptStatus.SUCCESS) {
                    order.confirmPgPayment(STARTED_AT.plusSeconds(1));
                } else if (expectedStatus == PaymentAttemptStatus.FAILED) {
                    order.failPgPayment();
                }
                session.persist(attempt);
                transaction.commit();
            }
            try (Session session = factory.openSession()) {
                PaymentAttempt loaded = session.createQuery("from PaymentAttempt where orderId = :orderId", PaymentAttempt.class)
                    .setParameter("orderId", 1002L).getSingleResult();
                assertThat(loaded.getAttemptKey()).isEqualTo(ATTEMPT_KEY);
                assertThat(loaded.getOrderId()).isEqualTo(1002);
                assertThat(loaded.getProviderAccount()).isEqualTo("135135");
                assertThat(loaded.getProviderOrderId()).isEqualTo("LP-ORD-0000001002");
                assertThat(loaded.getAmount()).isEqualTo(3000);
                assertThat(loaded.getStatus()).isEqualTo(expectedStatus);
                assertThat(loaded.getSendStartedAt().toInstant()).isEqualTo(STARTED_AT.toInstant());
                assertThat(loaded.startDispatch(STARTED_AT.plusDays(1))).isFalse();
                String expectedTransaction = expectedStatus == PaymentAttemptStatus.NOT_OBSERVED
                    || expectedStatus == PaymentAttemptStatus.UNKNOWN ? null : "TEST-TX-1002";
                assertThat(loaded.getTransactionKey()).isEqualTo(expectedTransaction);
                Order order = session.find(Order.class, 1002L);
                assertThat(order.getPaymentMethod()).isEqualTo(OrderPaymentMethod.PG);
                assertThat(order.getStatus()).isEqualTo(expectedOrderStatus(expectedStatus));
                assertThat(order.getItems().getFirst().getReservationStatus()).isEqualTo(expectedReservation(expectedStatus));
                assertThat(order.getItems().getFirst().getProductName()).isEqualTo("주문 생성 당시 이름");
                assertThat(order.getItems().getFirst().getUnitPrice()).isEqualTo(3000);
                if (expectedStatus == PaymentAttemptStatus.SUCCESS) {
                    assertThat(order.getPaidAmount()).isEqualTo(3000L);
                    assertThat(order.getConfirmedAt().toInstant()).isEqualTo(STARTED_AT.plusSeconds(1).toInstant());
                } else {
                    assertThat(order.getPaidAmount()).isNull();
                    assertThat(order.getConfirmedAt()).isNull();
                }
            }
        }
        // 모델/저장 연결만 검증한다. 실제 재고·포인트·목록 버전의 TX A/B 구현이 아니다.
        assertThat(jdbc.queryForObject("SELECT point_balance FROM `user` WHERE id = 1", Long.class)).isEqualTo(12345);
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id = 102", Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT revision FROM product_list_revision WHERE id = 1", Long.class)).isEqualTo(41);
    }

    @Test
    void roundTripsARejectionWithoutInventingATransactionKey() {
        seedPreviousRows();
        applyScripts(W5_SCRIPTS);
        try (SessionFactory factory = validatedSessionFactory()) {
            try (Session session = factory.openSession()) {
                var transaction = session.beginTransaction();
                Order order = session.find(Order.class, 1002L);
                order.preparePgPayment();
                order.failPgPayment();
                PaymentAttempt attempt = new PaymentAttempt(1002, ATTEMPT_KEY, "135135", "LP-ORD-0000001002", 3000);
                attempt.startDispatch(STARTED_AT);
                attempt.observeRejectedBeforeCreate("135135", "LP-ORD-0000001002", 3000);
                session.persist(attempt);
                transaction.commit();
            }
            try (Session session = factory.openSession()) {
                PaymentAttempt loaded = session.createQuery("from PaymentAttempt", PaymentAttempt.class).getSingleResult();
                assertThat(loaded.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
                assertThat(loaded.getTransactionKey()).isNull();
                assertThat(loaded.getSendStartedAt().toInstant()).isEqualTo(STARTED_AT.toInstant());
            }
        }
    }

    @Test
    void rollsBackFlushedOrderReservationsAndAttemptAndRechecksThroughANewSession() {
        seedPreviousRows();
        applyScripts(W5_SCRIPTS);
        var before = currentRowsSnapshot();
        try (SessionFactory factory = validatedSessionFactory()) {
            try (Session session = factory.openSession()) {
                var transaction = session.beginTransaction();
                Order order = session.find(Order.class, 1002L);
                order.preparePgPayment();
                PaymentAttempt attempt = new PaymentAttempt(1002, ATTEMPT_KEY, "135135", "LP-ORD-0000001002", 3000);
                attempt.startDispatch(STARTED_AT);
                session.persist(attempt);
                session.flush();
                assertThat(session.createNativeQuery("SELECT status FROM `order` WHERE id = 1002", String.class)
                    .getSingleResult()).isEqualTo("PAYMENT_PENDING");
                assertThat(session.createNativeQuery("SELECT reservation_status FROM order_item WHERE id = 10002", String.class)
                    .getSingleResult()).isEqualTo("HELD");
                assertThat(session.createNativeQuery("SELECT COUNT(*) FROM payment_attempt", Long.class)
                    .getSingleResult()).isEqualTo(1);
                transaction.rollback();
            }
            try (Session session = factory.openSession()) {
                Order loaded = session.find(Order.class, 1002L);
                assertThat(loaded.getStatus()).isEqualTo(OrderStatus.DRAFT);
                assertThat(loaded.getPaymentMethod()).isNull();
                assertThat(loaded.getItems().getFirst().getReservationStatus()).isEqualTo(OrderItemReservationStatus.NOT_RESERVED);
                assertThat(session.createQuery("select count(p) from PaymentAttempt p", Long.class).getSingleResult()).isZero();
            }
        }
        assertThat(currentRowsSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @MethodSource("invalidOrderCombinations")
    void rejectsInvalidOrderMethodStateCombinationsIncludingNullTruthValues(String update) {
        seedPreviousRows();
        applyScripts(W5_SCRIPTS);
        var before = currentRowsSnapshot();
        assertCheckRejected(update, "ck_order_payment_state");
        assertThat(currentRowsSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @MethodSource("invalidAttemptCombinations")
    void rejectsInvalidAttemptStateCombinationsIncludingNullTruthValues(String state, String transactionKey,
                                                                       String startedAt) {
        seedPreviousRows();
        applyScripts(W5_SCRIPTS);
        assertCheckRejected("INSERT INTO payment_attempt (order_id,attempt_key,provider_account,provider_order_id,amount,status,"
            + "transaction_key,send_started_at) VALUES (1002," + FIRST_KEY_SQL + ",'135135','LP-ORD-1002',3000,'"
            + state + "'," + transactionKey + "," + startedAt + ")", "ck_payment_attempt_observation_state");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "order_id = 1001", "attempt_key = UNHEX('00000000000000000000000000001001')",
        "provider_order_id = 'LP-ORD-1001'", "transaction_key = 'TEST-TX-1001'"
    })
    void rejectsEveryDuplicateIdentityWithoutReplacingTheFirstAttempt(String change) {
        seedPreviousRows();
        applyScripts(W5_SCRIPTS);
        insertAttempt(1001, "00000000000000000000000000001001", "135135", "LP-ORD-1001", "TEST-TX-1001");
        insertAttempt(1002, "00000000000000000000000000001002", "135135", "LP-ORD-1002", "TEST-TX-1002");
        var before = currentRowsSnapshot();
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_attempt SET " + change + " WHERE order_id = 1002"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(currentRowsSnapshot()).isEqualTo(before);
    }

    @Test
    void scopesExternalIdentitiesByAccountAndPreservesCaseAndTrailingSpaces() {
        seedPreviousRows();
        applyScripts(W5_SCRIPTS);
        List<String[]> identities = List.of(
            new String[] {"Account", "Provider-Order", "Transaction"},
            new String[] {"account", "Provider-Order", "Transaction"},
            new String[] {"Account ", "Provider-Order", "Transaction"},
            new String[] {"Account", "provider-order", "transaction"},
            new String[] {"Account", "Provider-Order ", "Transaction "}
        );
        for (int index = 0; index < identities.size(); index++) {
            long orderId = 2000L + index;
            insertDraft(orderId);
            String[] identity = identities.get(index);
            insertAttempt(orderId, String.format("%032x", orderId), identity[0], identity[1], identity[2]);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Long.class)).isEqualTo(identities.size());
        assertThat(jdbc.queryForList("""
            SELECT COLLATION_NAME FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_attempt'
                AND COLUMN_NAME IN ('provider_account','provider_order_id','transaction_key')
            """, String.class)).hasSize(3).containsOnly("utf8mb4_0900_bin");
    }

    @Test
    void allowsUnknownTransactionsOnDifferentAttemptsAndRejectsMissingParentsAndNegativeAmounts() {
        seedPreviousRows();
        applyScripts(W5_SCRIPTS);
        for (long orderId : List.of(1001L, 1002L)) {
            jdbc.update("""
                INSERT INTO payment_attempt (order_id,attempt_key,provider_account,provider_order_id,amount,status)
                VALUES (?,UNHEX(?),'135135',?,0,'NOT_OBSERVED')
                """, orderId, String.format("%032x", orderId), "LP-ORD-" + orderId);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE transaction_key IS NULL", Long.class))
            .isEqualTo(2);
        assertCheckRejected("UPDATE payment_attempt SET amount = -1 WHERE order_id = 1002", "ck_payment_attempt_nonnegative_amount");
        var before = currentRowsSnapshot();
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_attempt SET order_id = 99999 WHERE order_id = 1002"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM `order` WHERE id = 1002"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(currentRowsSnapshot()).isEqualTo(before);
    }

    @Test
    void preservesDataWhenTheOneTimeMigrationIsAccidentallyReapplied() {
        seedPreviousRows();
        applyScripts(W5_SCRIPTS);
        var before = currentRowsSnapshot();
        assertThatThrownBy(() -> applyScripts(W5_SCRIPTS)).isInstanceOf(ScriptStatementFailedException.class);
        assertThat(currentRowsSnapshot()).isEqualTo(before);
    }

    @Test
    void addsFingerprintColumnsToEveryPreviousPgStateWithoutRewritingPointDraftOrPaymentFacts() {
        seedPreviousRows();
        applyScripts(List.of("009-order-payment-state.sql", "010-payment-attempt.sql"));
        int index = 0;
        for (PaymentAttemptStatus status : PaymentAttemptStatus.values()) {
            seedPreviousPgAttempt(2000 + index++, status,
                status == PaymentAttemptStatus.PENDING || status == PaymentAttemptStatus.SUCCESS
                    || status == PaymentAttemptStatus.FAILED);
        }
        // UNKNOWN의 이미 알려진 거래와 거래 생성 전 확정 거절의 FAILED도 기존 사실 그대로 보존한다.
        seedPreviousPgAttempt(2100, PaymentAttemptStatus.UNKNOWN, true);
        seedPreviousPgAttempt(2101, PaymentAttemptStatus.FAILED, false);
        var before = currentRowsSnapshot();

        applyScripts(List.of("011-payment-request-fingerprint.sql"));

        assertThat(currentRowsSnapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Long.class)).isEqualTo(7L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM payment_attempt
            WHERE request_fingerprint IS NULL AND fingerprint_version IS NULL AND key_version IS NULL
            """, Long.class)).isEqualTo(7L);
        try (SessionFactory factory = validatedSessionFactory(); Session session = factory.openSession()) {
            Order point = session.find(Order.class, 1001L);
            assertThat(point.getPaymentMethod()).isEqualTo(OrderPaymentMethod.POINT);
            assertThat(point.getPaidAmount()).isEqualTo(2000L);
            assertThat(point.getItems().getFirst().getProductName()).isEqualTo("이전 상품명");
            Order draft = session.find(Order.class, 1002L);
            assertThat(draft.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(draft.getPaymentMethod()).isNull();
            var migrated = session.createQuery("from PaymentAttempt order by orderId", PaymentAttempt.class).list();
            assertThat(migrated).hasSize(7);
            for (PaymentAttempt attempt : migrated) {
                Order order = session.find(Order.class, attempt.getOrderId());
                assertThat(order.getStatus()).isEqualTo(expectedOrderStatus(attempt.getStatus()));
                assertThat(order.getPaymentMethod()).isEqualTo(OrderPaymentMethod.PG);
                assertThat(order.getItems().getFirst().getReservationStatus()).isEqualTo(expectedReservation(attempt.getStatus()));
            }
        }
        assertThat(currentRowsSnapshot()).isEqualTo(before);
    }

    @Test
    void declaresNullableVariableBinaryFingerprintAndTwoIndependentIntegerVersions() {
        applyScripts(W5_SCRIPTS);

        assertThat(jdbc.queryForObject("""
            SELECT COLUMN_TYPE FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_attempt' AND COLUMN_NAME = 'request_fingerprint'
            """, String.class)).isEqualTo("varbinary(32)");
        assertThat(jdbc.queryForList("""
            SELECT COLUMN_TYPE FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_attempt'
                AND COLUMN_NAME IN ('fingerprint_version','key_version') ORDER BY COLUMN_NAME
            """, String.class)).containsExactly("int", "int");
        assertThat(jdbc.queryForList("""
            SELECT IS_NULLABLE FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_attempt'
                AND COLUMN_NAME IN ('request_fingerprint','fingerprint_version','key_version') ORDER BY COLUMN_NAME
            """, String.class)).containsExactly("YES", "YES", "YES");
        assertThat(jdbc.queryForObject("""
            SELECT ENFORCED FROM information_schema.TABLE_CONSTRAINTS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_attempt'
                AND CONSTRAINT_NAME = 'ck_payment_attempt_fingerprint' AND CONSTRAINT_TYPE = 'CHECK'
            """, String.class)).isEqualTo("YES");
    }

    @Test
    void acceptsAllNullLegacyFingerprintOrAComplete32ByteValueWithIndependentPositiveVersions() {
        seedFingerprintAttempt();
        var before = currentRowsSnapshot();
        assertAllFingerprintColumnsNull();
        byte[] value = syntheticFingerprint();

        updateFingerprint(value, 3, 7);

        assertStoredFingerprint(value, 3, 7);
        assertThat(currentRowsSnapshot()).isEqualTo(before);
        updateFingerprint(value, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertStoredFingerprint(value, Integer.MAX_VALUE, Integer.MAX_VALUE);
        // 011은 과거 행을 위한 전체 NULL을 허용한다. 실제 업무의 변경 허용 정책을 대신 정하지 않는다.
        updateFingerprint(null, null, null);
        assertAllFingerprintColumnsNull();
        assertThat(currentRowsSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void rejectsEveryPartialNullFingerprintTupleByItsNamedCheck(int presentFields) {
        seedFingerprintAttempt();
        byte[] value = syntheticFingerprint();
        updateFingerprint(value, 3, 7);
        var before = currentRowsSnapshot();

        assertFingerprintCheckRejected((presentFields & 1) == 0 ? null : value,
            (presentFields & 2) == 0 ? null : 3, (presentFields & 4) == 0 ? null : 7);

        assertStoredFingerprint(value, 3, 7);
        assertThat(currentRowsSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 31})
    void rejectsShortFingerprintValuesWithoutPaddingOrChangingExistingData(int length) {
        seedFingerprintAttempt();
        byte[] value = syntheticFingerprint();
        updateFingerprint(value, 3, 7);
        var before = currentRowsSnapshot();

        assertFingerprintCheckRejected(new byte[length], 3, 7);

        assertStoredFingerprint(value, 3, 7);
        assertThat(currentRowsSnapshot()).isEqualTo(before);
    }

    @Test
    void rejects33ByteFingerprintAtTheColumnBoundaryWithoutTruncatingTheExistingValue() {
        seedFingerprintAttempt();
        byte[] value = syntheticFingerprint();
        updateFingerprint(value, 3, 7);
        var before = currentRowsSnapshot();

        Throwable failure = catchThrowable(() -> updateFingerprint(new byte[33], 3, 7));

        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(failure.getCause()).isInstanceOfSatisfying(SQLException.class,
            sql -> assertThat(sql.getErrorCode()).isEqualTo(1406));
        assertStoredFingerprint(value, 3, 7);
        assertThat(currentRowsSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"0,1", "-1,1", "1,0", "1,-1"})
    void rejectsNonPositiveFingerprintOrKeyVersionWithoutChangingTheStoredValue(int fingerprintVersion, int keyVersion) {
        seedFingerprintAttempt();
        byte[] value = syntheticFingerprint();
        updateFingerprint(value, 3, 7);
        var before = currentRowsSnapshot();

        assertFingerprintCheckRejected(value, fingerprintVersion, keyVersion);

        assertStoredFingerprint(value, 3, 7);
        assertThat(currentRowsSnapshot()).isEqualTo(before);
    }

    @Test
    void reapplyingOnlyFingerprintMigrationFailsWithoutReplacingPopulatedOrLegacyRows() {
        seedFingerprintAttempt();
        byte[] value = syntheticFingerprint();
        updateFingerprint(value, 3, 7);
        insertAttempt(1001, "00000000000000000000000000001001", "135135", "LP-ORD-1001", "TEST-TX-1001");
        var before = currentRowsSnapshot();
        var metadataBefore = fingerprintMetadataSnapshot();

        assertThatThrownBy(() -> applyScripts(List.of("011-payment-request-fingerprint.sql")))
            .isInstanceOf(ScriptStatementFailedException.class);

        assertThat(currentRowsSnapshot()).isEqualTo(before);
        assertThat(fingerprintMetadataSnapshot()).isEqualTo(metadataBefore);
        assertStoredFingerprint(value, 3, 7);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM payment_attempt WHERE order_id=1001
                AND request_fingerprint IS NULL AND fingerprint_version IS NULL AND key_version IS NULL
            """, Long.class)).isEqualTo(1L);
    }

    private void seedPreviousPgAttempt(long orderId, PaymentAttemptStatus status, boolean hasTransactionKey) {
        insertDraft(orderId);
        boolean success = status == PaymentAttemptStatus.SUCCESS;
        jdbc.update("""
            UPDATE `order` SET status=?,payment_method='PG',paid_amount=?,confirmed_at=? WHERE id=?
            """, expectedOrderStatus(status).name(), success ? 3000L : null,
            success ? "2026-10-23 00:01:03.123456" : null, orderId);
        jdbc.update("""
            INSERT INTO order_item (order_id,product_id,product_name_snapshot,unit_price_snapshot,quantity,reservation_status)
            VALUES (?,102,'PG 주문 당시 상품',3000,1,?)
            """, orderId, expectedReservation(status).name());
        insertAttempt(orderId, String.format("%032x", orderId), "135135", "LP-OLD-" + orderId, "TX-OLD-" + orderId);
        jdbc.update("UPDATE payment_attempt SET status=?,transaction_key=? WHERE order_id=?",
            status.name(), hasTransactionKey ? "TX-OLD-" + orderId : null, orderId);
    }

    private void seedFingerprintAttempt() {
        seedPreviousRows();
        applyScripts(W5_SCRIPTS);
        insertAttempt(1002, "00000000000000000000000000001002", "135135", "LP-ORD-1002", "TEST-TX-1002");
    }

    private void updateFingerprint(byte[] value, Integer fingerprintVersion, Integer keyVersion) {
        // 파라미터로만 전달하여 SQL 문자열이나 실패 메시지에 지문 값을 만들지 않는다.
        jdbc.update("UPDATE payment_attempt SET request_fingerprint=?,fingerprint_version=?,key_version=? WHERE order_id=1002",
            value, fingerprintVersion, keyVersion);
    }

    private void assertFingerprintCheckRejected(byte[] value, Integer fingerprintVersion, Integer keyVersion) {
        Throwable failure = catchThrowable(() -> updateFingerprint(value, fingerprintVersion, keyVersion));
        assertThat(failure).isInstanceOf(DataAccessException.class);
        assertThat(failure.getCause()).isInstanceOfSatisfying(SQLException.class, sql -> {
            assertThat(sql.getErrorCode()).isEqualTo(3819);
            assertThat(sql.getMessage()).contains("ck_payment_attempt_fingerprint");
        });
    }

    private void assertStoredFingerprint(byte[] expected, int fingerprintVersion, int keyVersion) {
        byte[] stored = jdbc.queryForObject("SELECT request_fingerprint FROM payment_attempt WHERE order_id=1002", byte[].class);
        assertThat(Arrays.equals(stored, expected)).as("저장된 지문 바이트의 동일성만 검사").isTrue();
        assertThat(jdbc.queryForObject("SELECT OCTET_LENGTH(request_fingerprint) FROM payment_attempt WHERE order_id=1002",
            Integer.class)).isEqualTo(32);
        assertThat(jdbc.queryForObject("SELECT fingerprint_version FROM payment_attempt WHERE order_id=1002", Integer.class))
            .isEqualTo(fingerprintVersion);
        assertThat(jdbc.queryForObject("SELECT key_version FROM payment_attempt WHERE order_id=1002", Integer.class))
            .isEqualTo(keyVersion);
    }

    private void assertAllFingerprintColumnsNull() {
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM payment_attempt WHERE order_id=1002
                AND request_fingerprint IS NULL AND fingerprint_version IS NULL AND key_version IS NULL
            """, Long.class)).isEqualTo(1L);
    }

    private List<Map<String, Object>> fingerprintMetadataSnapshot() {
        return jdbc.queryForList("""
            SELECT order_id,request_fingerprint IS NULL AS fingerprint_missing,
                OCTET_LENGTH(request_fingerprint) AS fingerprint_bytes,fingerprint_version,key_version
            FROM payment_attempt ORDER BY id
            """);
    }

    private byte[] syntheticFingerprint() {
        byte[] value = new byte[32];
        Arrays.fill(value, (byte) 42);
        return value;
    }

    private void insertAttempt(long orderId, String key, String account, String providerOrder, String transactionKey) {
        jdbc.update("""
            INSERT INTO payment_attempt (order_id,attempt_key,provider_account,provider_order_id,transaction_key,amount,status,send_started_at)
            VALUES (?,UNHEX(?),?,?,?,3000,'PENDING','2026-10-23 00:01:02.123456')
            """, orderId, key, account, providerOrder, transactionKey);
    }

    private void insertDraft(long id) {
        jdbc.update("""
            INSERT INTO `order` (id,user_id,status,total_amount,created_at,updated_at)
            VALUES (?,1,'DRAFT',3000,'2026-10-20 01:02:03.123456','2026-10-20 01:02:03.123456')
            """, id);
    }

    private void seedPreviousRows() {
        jdbc.update("""
            INSERT INTO brand (id,name,created_at,updated_at) VALUES (10,'브랜드','2026-10-19 00:00:00.123456','2026-10-19 01:00:00.123456')
            """);
        jdbc.update("""
            INSERT INTO `user` (id,point_balance,created_at,updated_at)
            VALUES (1,12345,'2026-10-19 00:00:00.123456','2026-10-19 01:00:00.123456')
            """);
        jdbc.update("""
            INSERT INTO product (id,brand_id,name,price,stock_quantity,deleted_at,created_at,updated_at)
            VALUES (101,10,'현재는 삭제된 상품',9999,7,'2026-10-22 00:00:00.123456','2026-10-19 00:00:00.123456','2026-10-22 00:00:00.123456'),
                (102,10,'현재는 다른 이름',9999,9,NULL,'2026-10-19 00:00:00.123456','2026-10-22 00:00:00.123456')
            """);
        jdbc.update("""
            INSERT INTO `order` (id,user_id,status,total_amount,paid_amount,confirmed_at,created_at,updated_at)
            VALUES (1001,1,'CONFIRMED',2000,2000,'2026-10-20 02:03:04.123456','2026-10-20 01:02:03.123456','2026-10-20 02:03:04.123456'),
                (1002,1,'DRAFT',3000,NULL,NULL,'2026-10-20 01:02:03.123456','2026-10-20 01:02:03.123456')
            """);
        jdbc.update("""
            INSERT INTO order_item (id,order_id,product_id,product_name_snapshot,unit_price_snapshot,quantity)
            VALUES (10001,1001,101,'이전 상품명',1000,2),(10002,1002,102,'주문 생성 당시 이름',3000,1)
            """);
        jdbc.update("INSERT INTO `like` (id,user_id,product_id,created_at) VALUES (100001,1,102,'2026-10-20 00:00:00.123456')");
        jdbc.update("UPDATE product_list_revision SET revision = 41 WHERE id = 1");
    }

    private void seedCurrentRows() {
        jdbc.update("INSERT INTO brand (id,name,created_at,updated_at) VALUES (10,'브랜드',NOW(6),NOW(6))");
        jdbc.update("INSERT INTO `user` (id,point_balance,created_at,updated_at) VALUES (1,12345,NOW(6),NOW(6))");
        jdbc.update("""
            INSERT INTO product (id,brand_id,name,price,stock_quantity,created_at,updated_at)
            VALUES (102,10,'현재는 다른 이름',9999,9,NOW(6),NOW(6))
            """);
        insertDraft(1002);
        jdbc.update("""
            INSERT INTO order_item (id,order_id,product_id,product_name_snapshot,unit_price_snapshot,quantity)
            VALUES (10002,1002,102,'주문 생성 당시 이름',3000,1)
            """);
        jdbc.update("UPDATE product_list_revision SET revision = 41 WHERE id = 1");
    }

    private Map<String, List<Map<String, Object>>> previousColumnsSnapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : List.of("brand", "user", "product", "like", "product_list_revision")) {
            result.put(table, jdbc.queryForList("SELECT * FROM `" + table + "` ORDER BY id"));
        }
        result.put("order", jdbc.queryForList("""
            SELECT id,user_id,status,total_amount,paid_amount,confirmed_at,created_at,updated_at FROM `order` ORDER BY id
            """));
        result.put("order_item", jdbc.queryForList("""
            SELECT id,order_id,product_id,product_name_snapshot,unit_price_snapshot,quantity FROM order_item ORDER BY id
            """));
        return result;
    }

    private Map<String, List<Map<String, Object>>> currentRowsSnapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : List.of("brand", "user", "product", "like", "order", "order_item", "product_list_revision")) {
            result.put(table, jdbc.queryForList("SELECT * FROM `" + table + "` ORDER BY id"));
        }
        // JDBC의 새 byte[] 객체 참조가 아니라 UUID의 전체 16바이트 값을 비교한다.
        result.put("payment_attempt", jdbc.queryForList("""
            SELECT id,order_id,HEX(attempt_key) AS attempt_key,provider_account,provider_order_id,
                transaction_key,amount,status,send_started_at FROM payment_attempt ORDER BY id
            """));
        return result;
    }

    private void applyScripts(List<String> scripts) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        scripts.forEach(script -> populator.addScript(new ClassPathResource("db/schema/" + script)));
        populator.execute(dataSource);
    }

    private SessionFactory validatedSessionFactory() {
        Configuration configuration = new Configuration();
        for (Class<?> type : List.of(Brand.class, User.class, Product.class, ProductLike.class, Order.class, OrderItem.class,
            ProductListRevision.class, PaymentAttempt.class)) {
            configuration.addAnnotatedClass(type);
        }
        configuration.addAttributeConverter(BrandNameConverter.class, true);
        configuration.getProperties().put("hibernate.connection.datasource", dataSource);
        configuration.setProperty("hibernate.hbm2ddl.auto", "validate");
        configuration.setProperty("hibernate.timezone.default_storage", "NORMALIZE_UTC");
        configuration.setProperty("hibernate.jdbc.time_zone", "UTC");
        return configuration.buildSessionFactory();
    }

    private void assertCheckRejected(String statement, String constraint) {
        Throwable failure = catchThrowable(() -> jdbc.update(statement));
        assertThat(failure).isInstanceOf(DataAccessException.class);
        assertThat(failure.getCause()).isInstanceOfSatisfying(SQLException.class, sql -> {
            assertThat(sql.getErrorCode()).isEqualTo(3819);
            assertThat(sql.getMessage()).contains(constraint);
        });
    }

    private static OrderStatus expectedOrderStatus(PaymentAttemptStatus status) {
        return switch (status) {
            case SUCCESS -> OrderStatus.CONFIRMED;
            case FAILED -> OrderStatus.PAYMENT_FAILED;
            default -> OrderStatus.PAYMENT_PENDING;
        };
    }

    private static OrderItemReservationStatus expectedReservation(PaymentAttemptStatus status) {
        return switch (status) {
            case SUCCESS -> OrderItemReservationStatus.CONSUMED;
            case FAILED -> OrderItemReservationStatus.RELEASED;
            default -> OrderItemReservationStatus.HELD;
        };
    }

    private static Stream<String> invalidOrderCombinations() {
        return Stream.of(
            "UPDATE `order` SET payment_method = 'PG' WHERE id = 1002",
            "UPDATE `order` SET status = 'PAYMENT_PENDING' WHERE id = 1002",
            "UPDATE `order` SET status = 'PAYMENT_FAILED' WHERE id = 1002",
            "UPDATE `order` SET status = 'PAYMENT_PENDING',payment_method = 'POINT' WHERE id = 1002",
            "UPDATE `order` SET status = 'PAYMENT_FAILED',payment_method = 'POINT' WHERE id = 1002",
            "UPDATE `order` SET status = 'PAYMENT_PENDING',payment_method = 'PG',paid_amount = 0 WHERE id = 1002",
            "UPDATE `order` SET status = 'PAYMENT_FAILED',payment_method = 'PG',confirmed_at = NOW(6) WHERE id = 1002",
            "UPDATE `order` SET payment_method = NULL WHERE id = 1001",
            "UPDATE `order` SET paid_amount = NULL WHERE id = 1001",
            "UPDATE `order` SET confirmed_at = NULL WHERE id = 1001",
            "UPDATE `order` SET paid_amount = 1999 WHERE id = 1001"
        );
    }

    private static Stream<Arguments> invalidAttemptCombinations() {
        return Stream.of(
            Arguments.of("NOT_OBSERVED", "'TEST-TX'", "NULL"),
            Arguments.of("NOT_OBSERVED", "'TEST-TX'", "NOW(6)"),
            Arguments.of("PENDING", "NULL", "NULL"),
            Arguments.of("PENDING", "NULL", "NOW(6)"),
            Arguments.of("PENDING", "'TEST-TX'", "NULL"),
            Arguments.of("SUCCESS", "NULL", "NULL"),
            Arguments.of("SUCCESS", "NULL", "NOW(6)"),
            Arguments.of("SUCCESS", "'TEST-TX'", "NULL"),
            Arguments.of("UNKNOWN", "NULL", "NULL"),
            Arguments.of("UNKNOWN", "'TEST-TX'", "NULL"),
            Arguments.of("FAILED", "NULL", "NULL"),
            Arguments.of("FAILED", "'TEST-TX'", "NULL")
        );
    }
}
