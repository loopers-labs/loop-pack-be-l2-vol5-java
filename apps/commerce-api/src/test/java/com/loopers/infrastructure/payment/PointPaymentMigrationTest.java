package com.loopers.infrastructure.payment;

import com.loopers.application.order.OrderApplicationService;
import com.loopers.application.order.OrderResult;
import com.loopers.utils.DatabaseCleanUp;
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

// 이관 원본은 2a 이전 코드가 쓰던 orders.paid_amount·payment_result라, 준비 데이터는 그 시절 행 모양으로 SQL로 직접 넣는다.
@SpringBootTest
class PointPaymentMigrationTest {
    private static final String SCRIPT = "sql/migrate-point-payments.sql";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OrderApplicationService orderApplicationService;
    @Autowired private DatabaseCleanUp cleanup;

    @BeforeEach
    void prepareLegacyOrders() {
        legacyOrder(1, 7000, "CONFIRMED", 7000, "SUCCESS");
        legacyOrder(2, 3000, "CONFIRMED", 3000, "SUCCESS");
        legacyOrder(3, 2000, "DRAFT", 0, "NOT_PAID");
        // 2a 배포 후 확정된 주문: 원본 열은 기본값이고 결제 기록이 이미 있다.
        legacyOrder(4, 5000, "CONFIRMED", 0, "NOT_PAID");
        jdbcTemplate.update("insert into payment_records(order_id, method, amount, result) values (4, 'POINT', 5000, 'SUCCESS')");
    }

    @AfterEach
    void clean() {
        jdbcTemplate.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("CONFIRMED 포인트 주문 2건을 이관하면 결제 기록(POINT·SUCCESS)이 원본 결제액과 같게 생기고 DRAFT·이미 옮긴 주문은 그대로다")
    void migratesConfirmedPointOrders() {
        runScript();

        assertThat(paymentsByOrderId()).containsExactly(
            Map.entry(1L, "POINT/7000/SUCCESS"),
            Map.entry(2L, "POINT/3000/SUCCESS"),
            Map.entry(4L, "POINT/5000/SUCCESS"));
        assertThat(count("select count(*) from orders where status = 'CONFIRMED'"))
            .isEqualTo(count("select count(*) from orders o join payment_records p on p.order_id = o.id where o.status = 'CONFIRMED'"));
        assertThat(count("select count(*) from payment_records p join orders o on o.id = p.order_id where o.status = 'DRAFT'")).isZero();
        assertThat(count("select sum(paid_amount) from orders where status = 'CONFIRMED' and payment_result = 'SUCCESS'"))
            .isEqualTo(count("select sum(p.amount) from payment_records p join orders o on o.id = p.order_id"
                + " where o.status = 'CONFIRMED' and o.payment_result = 'SUCCESS'"))
            .isEqualTo(10000);
        assertThat(count("select count(*) from orders o join payment_records p on p.order_id = o.id"
            + " where o.payment_result = 'SUCCESS' and p.amount <> o.paid_amount")).isZero();
    }

    @Test
    @DisplayName("이관 스크립트를 두 번 실행해도 결제 기록의 건수·금액이 한 번 실행한 결과와 같다")
    void rerunKeepsSameResult() {
        runScript();
        Map<Long, String> afterFirstRun = paymentsByOrderId();

        runScript();

        assertThat(paymentsByOrderId()).isEqualTo(afterFirstRun).hasSize(3);
    }

    @Test
    @DisplayName("이관 후 주문 조회는 결제 기록에서 결제수단 POINT와 원본 결제액을 읽고 DRAFT는 결제 전으로 보인다")
    void ordersReadMigratedPayments() {
        runScript();

        OrderResult confirmed = orderApplicationService.getMyOrder(1, 1);
        OrderResult draft = orderApplicationService.getMyOrder(1, 3);

        assertThat(confirmed.paymentMethod()).isEqualTo("POINT");
        assertThat(confirmed.paidAmount()).isEqualTo(7000);
        assertThat(confirmed.paymentResult()).isEqualTo("SUCCESS");
        assertThat(draft.paymentMethod()).isNull();
        assertThat(draft.paidAmount()).isZero();
        assertThat(draft.paymentResult()).isEqualTo("NOT_PAID");
    }

    private void legacyOrder(long id, long total, String status, long paidAmount, String paymentResult) {
        jdbcTemplate.update("insert into orders(id, user_id, total, status, paid_amount, payment_result) values (?, 1, ?, ?, ?, ?)",
            id, total, status, paidAmount, paymentResult);
        jdbcTemplate.update("insert into order_items(order_id, item_index, product_id, quantity, unit_price) values (?, 0, 10, 1, ?)",
            id, total);
    }

    private void runScript() {
        new ResourceDatabasePopulator(new ClassPathResource(SCRIPT)).execute(dataSource);
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
