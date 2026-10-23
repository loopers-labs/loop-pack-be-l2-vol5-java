package com.loopers.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PaymentSafetyMigrationIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @DisplayName("충돌 사유 ENUM을 확장해도 기존 사유·시각·상태·거래 키를 보존한다.")
    @Test
    void addsMultipleTransactionsReasonWithoutChangingExistingRecords() throws Exception {
        try {
            jdbc.execute("""
                CREATE TABLE review_payment_conflicts (
                    id BIGINT PRIMARY KEY,
                    conflict_reason ENUM('RESULT_CONTRADICTION', 'TRANSACTION_MISMATCH') DEFAULT NULL,
                    conflict_detected_at DATETIME(6),
                    status VARCHAR(32),
                    transaction_key VARCHAR(255)
                )
                """);
            jdbc.update("""
                INSERT INTO review_payment_conflicts VALUES
                (1, 'TRANSACTION_MISMATCH', '2026-10-23 00:00:00.123456', 'PENDING', 'TX-1'),
                (2, 'RESULT_CONTRADICTION', '2026-10-23 00:00:01.123456', 'SUCCEEDED', 'TX-2'),
                (3, NULL, NULL, 'UNKNOWN', NULL)
                """);
            String migration = Files.readString(Path.of(System.getProperty("payment.conflict-migration")))
                .replace("loopers.payment_attempts", "review_payment_conflicts");

            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)));
            }
            jdbc.update("INSERT INTO review_payment_conflicts (id, conflict_reason) VALUES (4, 'MULTIPLE_TRANSACTIONS')");

            assertThat(jdbc.queryForList("SELECT conflict_reason FROM review_payment_conflicts ORDER BY id", String.class))
                .containsExactly("TRANSACTION_MISMATCH", "RESULT_CONTRADICTION", null, "MULTIPLE_TRANSACTIONS");
            assertThat(jdbc.queryForObject("SELECT conflict_detected_at FROM review_payment_conflicts WHERE id=1", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2026, 10, 23, 0, 0, 0, 123_456_000));
            assertThat(jdbc.queryForList("SELECT status FROM review_payment_conflicts WHERE id<=3 ORDER BY id", String.class))
                .containsExactly("PENDING", "SUCCEEDED", "UNKNOWN");
            assertThat(jdbc.queryForList("SELECT transaction_key FROM review_payment_conflicts WHERE id<=3 ORDER BY id", String.class))
                .containsExactly("TX-1", "TX-2", null);
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS review_payment_conflicts");
        }
    }

    @Test
    void addsReservationLookupIndexWithoutChangingOrderOrStock() throws Exception {
        // 실제 SQL을 전용 테이블에 적용해 기존 애플리케이션 테이블과 테스트 격리를 유지한다.
        try {
            jdbc.execute("CREATE TABLE review_products (id BIGINT PRIMARY KEY, stock BIGINT NOT NULL)");
            jdbc.execute("CREATE TABLE review_orders (id BIGINT PRIMARY KEY, status VARCHAR(32), payment_method VARCHAR(16))");
            jdbc.execute("CREATE TABLE review_items (product_id BIGINT, order_id BIGINT, quantity INT)");
            jdbc.update("INSERT INTO review_products VALUES (1, 8)");
            jdbc.update("INSERT INTO review_orders VALUES (1, 'PAYMENT_PENDING', 'PG'), (2, 'CONFIRMED', 'POINT')");
            jdbc.update("INSERT INTO review_items VALUES (1, 1, 2), (1, 2, 99)");
            String migration = Files.readString(Path.of(System.getProperty("payment.safety-migration")))
                .replace("loopers.products", "review_products")
                .replace("loopers.orders", "review_orders")
                .replace("loopers.order_items", "review_items");

            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)));
            }

            assertThat(jdbc.queryForObject("SELECT stock FROM review_products WHERE id=1", Long.class)).isEqualTo(8L);
            assertThat(jdbc.queryForObject("SELECT payment_method FROM review_orders WHERE id=2", String.class)).isEqualTo("POINT");
            assertThat(jdbc.queryForObject("SELECT SUM(quantity) FROM review_items", Long.class)).isEqualTo(101L);
            assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='review_items' AND INDEX_NAME='idx_order_items_product_id'
                """, Integer.class)).isEqualTo(1);
        } finally {
            for (String table : new String[] {"review_items", "review_orders", "review_products"}) {
                jdbc.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }
}
