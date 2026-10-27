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

// 실제 이관 SQL을 전용 테이블에 적용해 애플리케이션 테이블과 격리한다.
@SpringBootTest
class OrderConfirmedAtMigrationIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @DisplayName("확정 시각 컬럼을 추가해도 기존 주문의 확정 시각은 비워 두고 상태·결제 값·수정 시각은 바꾸지 않는다.")
    @Test
    void addsNullableConfirmedAtWithoutBackfilling() throws Exception {
        try {
            jdbc.execute("""
                CREATE TABLE review_confirmed_orders (
                    id BIGINT PRIMARY KEY,
                    status VARCHAR(32),
                    payment_method VARCHAR(16),
                    payment_amount BIGINT,
                    updated_at DATETIME(6)
                )
                """);
            jdbc.update("""
                INSERT INTO review_confirmed_orders VALUES
                (1, 'CONFIRMED', 'POINT', 200, '2026-10-01 00:00:00.000001'),
                (2, 'CONFIRMED', 'PG', 300, '2026-10-02 00:00:00.000002'),
                (3, 'PAYMENT_PENDING', 'PG', NULL, '2026-10-03 00:00:00.000003'),
                (4, 'DRAFT', NULL, NULL, '2026-10-04 00:00:00.000004')
                """);
            String migration = Files.readString(Path.of(System.getProperty("order.confirmed-at-migration")))
                .replace("loopers.orders", "review_confirmed_orders");

            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)));
            }
            jdbc.update("UPDATE review_confirmed_orders SET confirmed_at = '2026-10-05 00:00:00.123456' WHERE id = 3");

            assertThat(jdbc.queryForList("SELECT confirmed_at FROM review_confirmed_orders ORDER BY id", LocalDateTime.class))
                .containsExactly(null, null, LocalDateTime.of(2026, 10, 5, 0, 0, 0, 123_456_000), null);
            assertThat(jdbc.queryForList("SELECT status FROM review_confirmed_orders ORDER BY id", String.class))
                .containsExactly("CONFIRMED", "CONFIRMED", "PAYMENT_PENDING", "DRAFT");
            assertThat(jdbc.queryForList("SELECT payment_amount FROM review_confirmed_orders ORDER BY id", Long.class))
                .containsExactly(200L, 300L, null, null);
            assertThat(jdbc.queryForObject("SELECT updated_at FROM review_confirmed_orders WHERE id = 2", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2026, 10, 2, 0, 0, 0, 2_000));
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS review_confirmed_orders");
        }
    }
}
