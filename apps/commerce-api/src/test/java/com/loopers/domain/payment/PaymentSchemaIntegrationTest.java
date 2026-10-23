package com.loopers.domain.payment;

import com.loopers.domain.common.Money;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 새 코드가 빈 테스트 MySQL 에 만든 스키마를 설계 3.6 표와 비교한다(4.5 「스키마 타입과 기존 데이터 전환 검증」의 새 스키마 항목).
 * 기존 데이터 보존 전환의 재현은 별도 단계(I3-3)다.
 */
@SpringBootTest
@DisplayName("새 스키마의 결제·주문 열 타입·제약은 설계 표와 같다.")
class PaymentSchemaIntegrationTest {

    private static final String INSERT_PAYMENT = """
        INSERT INTO payment (order_id, attempt_key, provider, provider_account, provider_order_id, transaction_key, amount,
            status, dispatch_claimed_at, verification_status, created_at, updated_at)
        VALUES (?, ?, 'TEST_PG', '135135', ?, ?, ?, 'UNKNOWN', NOW(6), 'NOT_CHECKED', NOW(6), NOW(6))
        """;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("신규 enum 열은 VARCHAR, 기존 상태·원인 열은 새 값을 포함한 ENUM 이고 금액 열 이름이 바뀌었다.")
    @Test
    void enumAndRenamedColumns() {
        Map<String, Column> orders = columns("orders");
        Map<String, Column> history = columns("stock_history");
        System.out.println("[SCHEMA ENUM] orders.status=" + orders.get("status").columnType()
            + " stock_history.cause=" + history.get("cause").columnType());

        assertAll(
            () -> assertThat(orders.get("payment_method")).isEqualTo(new Column("varchar", "varchar(16)", "YES", collationOf(orders, "payment_method"))),
            () -> assertThat(orders.get("total_amount").columnType()).isEqualTo("bigint"),
            () -> assertThat(orders.get("total_amount").nullable()).isEqualTo("NO"),
            () -> assertThat(orders.get("point_amount").nullable()).isEqualTo("YES"),
            () -> assertThat(orders.get("paid_amount").nullable()).isEqualTo("YES"),
            () -> assertThat(orders).doesNotContainKeys("order_total", "used_point_amount", "payment_amount"),
            () -> assertThat(orders.get("status").dataType()).isEqualTo("enum"),
            () -> assertThat(enumValues(orders.get("status"))).containsExactlyInAnyOrder(
                "CONFIRMED", "DRAFT", "PAYMENT_FAILED", "PAYMENT_PENDING"),
            () -> assertThat(history.get("cause").dataType()).isEqualTo("enum"),
            () -> assertThat(enumValues(history.get("cause"))).containsExactlyInAnyOrder(
                "ADMIN_CHANGE", "ORDER_DEDUCTION", "ORDER_RELEASE", "ORDER_RESERVATION")
        );
    }

    @DisplayName("payment 열은 표의 타입·길이·nullable 이고 키 열은 ASCII binary collation 이다.")
    @Test
    void paymentColumns() {
        Map<String, Column> payment = columns("payment");

        assertAll(
            () -> assertThat(payment.get("order_id")).isEqualTo(new Column("bigint", "bigint", "NO", null)),
            () -> assertThat(payment.get("attempt_key")).isEqualTo(new Column("char", "char(36)", "NO", "ascii_bin")),
            () -> assertThat(payment.get("provider")).isEqualTo(new Column("varchar", "varchar(16)", "NO", "ascii_bin")),
            () -> assertThat(payment.get("provider_account")).isEqualTo(new Column("varchar", "varchar(32)", "NO", "ascii_bin")),
            () -> assertThat(payment.get("provider_order_id")).isEqualTo(new Column("varchar", "varchar(32)", "NO", "ascii_bin")),
            () -> assertThat(payment.get("transaction_key")).isEqualTo(new Column("varchar", "varchar(255)", "YES", "ascii_bin")),
            () -> assertThat(payment.get("amount")).isEqualTo(new Column("bigint", "bigint", "NO", null)),
            () -> assertThat(payment.get("status").columnType()).isEqualTo("varchar(16)"),
            () -> assertThat(payment.get("status").nullable()).isEqualTo("NO"),
            () -> assertThat(payment.get("failure_kind").columnType()).isEqualTo("varchar(32)"),
            () -> assertThat(payment.get("failure_kind").nullable()).isEqualTo("YES"),
            () -> assertThat(payment.get("verification_status").columnType()).isEqualTo("varchar(16)"),
            () -> assertThat(payment.get("verification_status").nullable()).isEqualTo("NO"),
            () -> assertThat(payment.get("dispatch_claimed_at").nullable()).isEqualTo("NO"),
            () -> assertThat(payment.get("verification_checked_at").nullable()).isEqualTo("YES")
        );
    }

    @DisplayName("payment_conflict 열은 표의 타입·nullable 이다.")
    @Test
    void conflictColumns() {
        Map<String, Column> conflict = columns("payment_conflict");

        assertAll(
            () -> assertThat(conflict.get("payment_id")).isEqualTo(new Column("bigint", "bigint", "NO", null)),
            () -> assertThat(conflict.get("code").columnType()).isEqualTo("varchar(48)"),
            () -> assertThat(conflict.get("code").nullable()).isEqualTo("NO"),
            () -> assertThat(conflict.get("observed_order_id").dataType()).isEqualTo("text"),
            () -> assertThat(conflict.get("observed_transaction_key").dataType()).isEqualTo("text"),
            () -> assertThat(conflict.get("observed_amount")).isEqualTo(new Column("bigint", "bigint", "YES", null)),
            () -> assertThat(conflict.get("observed_status").columnType()).isEqualTo("varchar(16)"),
            () -> assertThat(conflict.get("observed_count")).isEqualTo(new Column("int", "int", "YES", null)),
            () -> assertThat(conflict.get("detected_at").nullable()).isEqualTo("NO")
        );
    }

    @DisplayName("명명한 unique·index·CHECK 가 설계 열 순서대로 있다.")
    @Test
    void namedConstraints() {
        Map<String, String> paymentIndexes = indexes("payment");
        Map<String, String> conflictIndexes = indexes("payment_conflict");
        List<String> checks = jdbcTemplate.queryForList("""
            SELECT cc.CHECK_CLAUSE FROM information_schema.TABLE_CONSTRAINTS tc
            JOIN information_schema.CHECK_CONSTRAINTS cc
              ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
            WHERE tc.TABLE_SCHEMA = DATABASE() AND tc.TABLE_NAME = 'payment' AND tc.CONSTRAINT_TYPE = 'CHECK'
            """, String.class);

        assertAll(
            () -> assertThat(paymentIndexes).containsEntry("uk_payment_order", "unique:order_id"),
            () -> assertThat(paymentIndexes).containsEntry("uk_payment_key", "unique:attempt_key"),
            () -> assertThat(paymentIndexes).containsEntry("uk_payment_transaction", "unique:provider,provider_account,transaction_key"),
            () -> assertThat(paymentIndexes).containsEntry("idx_payment_provider_order", "index:provider,provider_account,provider_order_id"),
            () -> assertThat(conflictIndexes).containsEntry("uk_payment_conflict_payment", "unique:payment_id"),
            () -> assertThat(checks).singleElement().satisfies(clause -> assertThat(clause.replace("`", "")).contains("amount > 0"))
        );
    }

    @DisplayName("CHECK·unique 는 실제 쓰기를 막고, 미결합 거래 키 null 은 여러 결제에 허용한다.")
    @Test
    void enforcesConstraints() {
        insertPayment(1L, "00000000-0000-0000-0000-000000000001", null, 7_000L);
        insertPayment(2L, "00000000-0000-0000-0000-000000000002", null, 7_000L);
        insertPayment(3L, "00000000-0000-0000-0000-000000000003", "tx-A", 7_000L);

        assertAll(
            // MySQL CHECK 위반(3819)은 Spring 이 DataIntegrityViolationException 으로 분류하지 않는다. 오류 코드로 확인한다.
            () -> assertThatThrownBy(() -> insertPayment(4L, "00000000-0000-0000-0000-000000000004", null, 0L))
                .as("amount > 0").isInstanceOf(UncategorizedSQLException.class)
                .satisfies(error -> assertThat(((UncategorizedSQLException) error).getSQLException().getErrorCode())
                    .isEqualTo(3819)),
            () -> assertThatThrownBy(() -> insertPayment(1L, "00000000-0000-0000-0000-000000000005", null, 7_000L))
                .as("주문당 하나").isInstanceOf(DataIntegrityViolationException.class),
            () -> assertThatThrownBy(() -> insertPayment(6L, "00000000-0000-0000-0000-000000000001", null, 7_000L))
                .as("시도 키 전역 unique").isInstanceOf(DataIntegrityViolationException.class),
            () -> assertThatThrownBy(() -> insertPayment(7L, "00000000-0000-0000-0000-000000000007", "tx-A", 7_000L))
                .as("외부 거래 중복 결합").isInstanceOf(DataIntegrityViolationException.class),
            () -> assertThatCode(() -> insertPayment(8L, "00000000-0000-0000-0000-000000000008", "TX-A", 7_000L))
                .as("거래 키는 대소문자 구분").doesNotThrowAnyException()
        );
    }

    @DisplayName("변환기로 저장한 결제·충돌 요약을 새 영속성 컨텍스트에서 같은 값으로 읽고, 열에는 enum 이름이 저장된다.")
    @Test
    void roundTripsThroughConverters() {
        ZonedDateTime claimedAt = ZonedDateTime.parse("2026-10-23T00:00:00Z");
        Long paymentId = transactionTemplate.execute(status -> {
            PaymentModel payment = PaymentModel.start(42L, "123e4567-e89b-12d3-a456-426614174000", "135135",
                Money.of(7_000L), claimedAt);
            entityManager.persist(payment);
            entityManager.flush();
            payment.recordObservation(new PaymentCheckToken(payment.getId(), 42L, ObservationSource.CREATE, false),
                new PaymentObservation.LocalNotDispatched(LocalNotDispatchedReason.POOL_ACQUIRE_TIMEOUT), claimedAt);
            entityManager.persist(PaymentConflictModel.first(payment.getId(), PaymentConflictCode.MULTIPLE_TRANSACTIONS,
                new ConflictEvidence(null, null, null, PgTransactionStatus.PENDING, 2), claimedAt));
            return payment.getId();
        });

        PaymentModel reloaded = transactionTemplate.execute(status -> entityManager.find(PaymentModel.class, paymentId));
        PaymentConflictModel conflict = transactionTemplate.execute(status -> entityManager.createQuery(
            "select c from PaymentConflictModel c where c.paymentId = :id", PaymentConflictModel.class)
            .setParameter("id", paymentId).getSingleResult());
        Map<String, Object> raw = jdbcTemplate.queryForMap(
            "SELECT status, failure_kind, verification_status FROM payment WHERE id = ?", paymentId);

        assertAll(
            () -> assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.FAILED),
            () -> assertThat(reloaded.getFailureKind()).isEqualTo(PaymentFailureKind.POOL_ACQUIRE_TIMEOUT),
            () -> assertThat(reloaded.getVerificationStatus()).isEqualTo(VerificationStatus.NOT_CHECKED),
            () -> assertThat(reloaded.getProviderOrderId()).isEqualTo("LP-ORD-0000000042"),
            () -> assertThat(conflict.getCode()).isEqualTo(PaymentConflictCode.MULTIPLE_TRANSACTIONS),
            () -> assertThat(conflict.getObservedStatus()).isEqualTo(PgTransactionStatus.PENDING),
            () -> assertThat(raw).containsEntry("status", "FAILED").containsEntry("failure_kind", "POOL_ACQUIRE_TIMEOUT")
                .containsEntry("verification_status", "NOT_CHECKED")
        );
    }

    @DisplayName("열에 알 수 없는 enum 이름이 있으면 조용히 바꾸지 않고 읽기 실패로 드러난다.")
    @Test
    void rejectsUnknownStoredValue() {
        insertPayment(9L, "00000000-0000-0000-0000-000000000009", null, 7_000L);
        jdbcTemplate.update("UPDATE payment SET status = 'CANCELED' WHERE order_id = 9");

        assertThatThrownBy(() -> transactionTemplate.execute(status -> entityManager.createQuery(
            "select p from PaymentModel p where p.orderId = 9", PaymentModel.class).getSingleResult()))
            .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    private void insertPayment(long orderId, String attemptKey, String transactionKey, long amount) {
        jdbcTemplate.update(INSERT_PAYMENT, orderId, attemptKey, PaymentModel.providerOrderIdOf(orderId), transactionKey, amount);
    }

    private Map<String, Column> columns(String table) {
        Map<String, Column> columns = new LinkedHashMap<>();
        jdbcTemplate.query("""
            SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, COLLATION_NAME FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION
            """, rs -> {
            columns.put(rs.getString(1), new Column(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
        }, table);
        return columns;
    }

    /** 인덱스 이름 → "unique|index:열1,열2" (SEQ_IN_INDEX 순서). */
    private Map<String, String> indexes(String table) {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        Map<String, Boolean> unique = new LinkedHashMap<>();
        jdbcTemplate.query("""
            SELECT INDEX_NAME, NON_UNIQUE, COLUMN_NAME FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY INDEX_NAME, SEQ_IN_INDEX
            """, rs -> {
            columns.computeIfAbsent(rs.getString(1), name -> new ArrayList<>()).add(rs.getString(3));
            unique.put(rs.getString(1), rs.getInt(2) == 0);
        }, table);
        Map<String, String> result = new LinkedHashMap<>();
        columns.forEach((name, cols) -> result.put(name, (unique.get(name) ? "unique:" : "index:") + String.join(",", cols)));
        return result;
    }

    private static List<String> enumValues(Column column) {
        String type = column.columnType();
        String inner = type.substring(type.indexOf('(') + 1, type.lastIndexOf(')'));
        return Arrays.stream(inner.split(",")).map(value -> value.replace("'", "")).toList();
    }

    private static String collationOf(Map<String, Column> table, String column) {
        return table.get(column).collation();
    }

    private record Column(String dataType, String columnType, String nullable, String collation) {
    }
}
