package com.loopers.infrastructure.payment;

import com.loopers.application.order.OrderApplicationService;
import com.loopers.application.order.OrderResult;
import com.loopers.utils.DatabaseCleanUp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;

// 엔티티 매핑에서 옛 결제 열을 지웠으므로 테스트 스키마에는 그 열이 없다. 삭제 전 운영 DB 모양을 재현하려고 열을 직접 추가해
// 그 시절 행을 넣고, 끝나면 삭제 스크립트로 되돌려 같은 컨텍스트를 쓰는 다른 테스트가 원래 스키마를 보게 한다.
@SpringBootTest
class PointPaymentMigrationTest {
    private static final String MIGRATE = "sql/migrate-point-payments.sql";
    private static final String VERIFY = "sql/verify-point-payment-migration.sql";
    private static final String DROP = "sql/drop-legacy-order-payment-columns.sql";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OrderApplicationService orderApplicationService;
    @Autowired private DatabaseCleanUp cleanup;

    @BeforeEach
    void prepareLegacyOrders() {
        if (legacyColumns() == 0) {
            jdbcTemplate.execute("alter table orders add column paid_amount bigint not null default 0,"
                + " add column payment_result varchar(255) not null default 'NOT_PAID'");
        }
        legacyOrder(1, 7000, "CONFIRMED", 7000, "SUCCESS");
        legacyOrder(2, 3000, "CONFIRMED", 3000, "SUCCESS");
        legacyOrder(3, 2000, "DRAFT", 0, "NOT_PAID");
        // 2a 배포 후 확정된 주문: 원본 열은 기본값이고 결제 기록이 이미 있다.
        legacyOrder(4, 5000, "CONFIRMED", 0, "NOT_PAID");
        jdbcTemplate.update("insert into payment_records(order_id, method, amount, result) values (4, 'POINT', 5000, 'SUCCESS')");
    }

    @AfterEach
    void clean() {
        run(DROP);
        jdbcTemplate.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("CONFIRMED 포인트 주문 2건을 이관하면 결제 기록(POINT·SUCCESS)이 원본 결제액과 같게 생기고 DRAFT·이미 옮긴 주문은 그대로다")
    void migratesConfirmedPointOrders() {
        run(MIGRATE);

        assertThat(paymentsByOrderId()).containsExactly(
            Map.entry(1L, "POINT/7000/SUCCESS"),
            Map.entry(2L, "POINT/3000/SUCCESS"),
            Map.entry(4L, "POINT/5000/SUCCESS"));
        assertThat(count("select count(*) from orders where status = 'CONFIRMED'"))
            .isEqualTo(count("select count(*) from orders o join payment_records p on p.order_id = o.id where o.status = 'CONFIRMED'"));
        assertThat(count("select count(*) from payment_records p join orders o on o.id = p.order_id where o.status = 'DRAFT'")).isZero();
    }

    @Test
    @DisplayName("이관 스크립트를 두 번 실행해도 결제 기록의 건수·금액이 한 번 실행한 결과와 같다")
    void rerunKeepsSameResult() {
        run(MIGRATE);
        Map<Long, String> afterFirstRun = paymentsByOrderId();

        run(MIGRATE);

        assertThat(paymentsByOrderId()).isEqualTo(afterFirstRun).hasSize(3);
    }

    @Test
    @DisplayName("이관 뒤 검증 SQL은 옛 열 결제 주문 2건·POINT 기록 2건·금액 합계 10000원 일치·불일치 0건을 보고한다")
    void verificationPassesAfterMigration() {
        run(MIGRATE);

        assertThat(verify()).isEqualTo(verification(2, 2, 10000, 10000, 0));
    }

    @Test
    @DisplayName("이관 전 주문이 남거나 결제액이 다르면 검증 SQL이 건수·금액 차이와 불일치 주문 수를 보고한다")
    void verificationReportsMismatch() {
        legacyOrder(5, 4000, "CONFIRMED", 4000, "SUCCESS");
        jdbcTemplate.update("insert into payment_records(order_id, method, amount, result) values (5, 'POINT', 3999, 'SUCCESS')");

        assertThat(verify()).isEqualTo(verification(3, 1, 14000, 3999, 3));
    }

    @Test
    @DisplayName("검증 통과 후 삭제 SQL은 옛 결제 열 두 개를 지우고 다시 실행해도 오류 없이 그대로다")
    void dropsLegacyColumnsIdempotently() {
        run(MIGRATE);
        assertThat(verify()).isEqualTo(verification(2, 2, 10000, 10000, 0));

        run(DROP);
        run(DROP);

        assertThat(legacyColumns()).isZero();
        assertThat(paymentsByOrderId()).hasSize(3);
    }

    @Test
    @DisplayName("옛 열 삭제 후에도 과거 포인트 주문은 결제 기록에서 POINT·원본 결제액을 읽고 DRAFT는 결제 전으로 보인다")
    void ordersReadMigratedPaymentsAfterDrop() {
        run(MIGRATE);
        run(DROP);

        OrderResult confirmed = orderApplicationService.getMyOrder(1, 1);
        OrderResult draft = orderApplicationService.getMyOrder(1, 3);

        assertThat(confirmed.paymentMethod()).isEqualTo("POINT");
        assertThat(confirmed.paidAmount()).isEqualTo(7000);
        assertThat(confirmed.paymentResult()).isEqualTo("SUCCESS");
        assertThat(draft.paymentMethod()).isNull();
        assertThat(draft.paidAmount()).isZero();
        assertThat(draft.paymentResult()).isEqualTo("NOT_PAID");
        assertThat(orderApplicationService.listMyOrders(1, 0, 10)).hasSize(4);
    }

    private void legacyOrder(long id, long total, String status, long paidAmount, String paymentResult) {
        jdbcTemplate.update("insert into orders(id, user_id, total, status, paid_amount, payment_result) values (?, 1, ?, ?, ?, ?)",
            id, total, status, paidAmount, paymentResult);
        jdbcTemplate.update("insert into order_items(order_id, item_index, product_id, quantity, unit_price) values (?, 0, 10, 1, ?)",
            id, total);
    }

    private void run(String script) {
        new ResourceDatabasePopulator(new ClassPathResource(script)).execute(dataSource);
    }

    private Map<String, Long> verify() {
        String sql;
        try {
            sql = new ClassPathResource(VERIFY).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("검증 SQL을 읽지 못했습니다.", e);
        }
        Map<String, Long> result = new LinkedHashMap<>();
        jdbcTemplate.queryForMap(sql).forEach((column, value) -> result.put(column, ((Number) value).longValue()));
        return result;
    }

    private Map<String, Long> verification(long legacyPaidOrders, long pointRecords, long legacyAmount, long pointAmount,
        long mismatchedOrders) {
        Map<String, Long> expected = new LinkedHashMap<>();
        expected.put("legacy_paid_orders", legacyPaidOrders);
        expected.put("point_records", pointRecords);
        expected.put("legacy_amount", legacyAmount);
        expected.put("point_amount", pointAmount);
        expected.put("mismatched_orders", mismatchedOrders);
        return expected;
    }

    private long legacyColumns() {
        return count("select count(*) from information_schema.columns where table_schema = database()"
            + " and table_name = 'orders' and column_name in ('paid_amount', 'payment_result')");
    }

    private Map<Long, String> paymentsByOrderId() {
        Map<Long, String> byOrderId = new LinkedHashMap<>();
        jdbcTemplate.query("select order_id, method, amount, result from payment_records order by order_id",
            resultSet -> {
                byOrderId.put(resultSet.getLong("order_id"),
                    resultSet.getString("method") + "/" + resultSet.getLong("amount") + "/" + resultSet.getString("result"));
            });
        return byOrderId;
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
