package com.loopers.batch.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.batch.repeat.RepeatStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcPaymentReconciliationRepositoryTest {
    private JdbcTemplate jdbcTemplate;
    private JdbcPaymentReconciliationRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:payment-reconciliation;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbcTemplate = new JdbcTemplate(dataSource);
        createTables();
        clearTables();
        repository = new JdbcPaymentReconciliationRepository(jdbcTemplate, dataSource);
        insertSuccessfulOrder();
    }

    @Test
    @DisplayName("PG 조회 미확정은 대사 기록으로 남기고 최근 조회 범위를 지나도 계속 이월한다")
    void carriesUnresolvedLookupBeyondRecentWindow() {
        AtomicInteger lookupCount = new AtomicInteger();
        PaymentLookupClient pg = orderId -> {
            lookupCount.incrementAndGet();
            return new PaymentLookupClient.LookupResult(500, null, "{}");
        };

        runAt(pg, "2026-10-22T03:00:00Z");
        runAt(pg, "2026-10-22T03:11:00Z");
        runAt(pg, "2026-10-22T03:16:00Z");
        runAt(pg, "2026-10-22T03:21:00Z");
        runAt(pg, "2026-10-22T03:26:00Z");

        assertThat(lookupCount).hasValue(5);
        assertReconciliation("UNRESOLVED", "SUCCESS", null, "{}", 5);
        assertBusinessDataUnchanged();
    }

    @Test
    @DisplayName("PG 최종 상태가 우리 상태와 다르면 MISMATCH로 기록하고 결제·주문·재고는 보정하지 않는다")
    void recordsMismatchWithoutChangingBusinessData() {
        PaymentLookupClient pg = orderId -> new PaymentLookupClient.LookupResult(
            200, "ABORTED", "{\"status\":\"ABORTED\"}");

        runAt(pg, "2026-10-22T03:00:00Z");

        assertReconciliation("MISMATCH", "SUCCESS", "ABORTED", "{\"status\":\"ABORTED\"}", 1);
        assertBusinessDataUnchanged();
    }

    @Test
    @DisplayName("PG 상태가 우리 결제 상태와 같으면 대사 기록을 MATCHED로 남긴다")
    void recordsMatchedPayment() {
        PaymentLookupClient pg = orderId -> new PaymentLookupClient.LookupResult(
            200, "DONE", "{\"status\":\"DONE\"}");

        runAt(pg, "2026-10-22T03:00:00Z");

        assertReconciliation("MATCHED", "SUCCESS", "DONE", "{\"status\":\"DONE\"}", 1);
        assertBusinessDataUnchanged();
    }

    private void runAt(PaymentLookupClient pg, String instant) {
        CompareRecentPaymentsTasklet tasklet = new CompareRecentPaymentsTasklet(
            repository, pg, new PaymentStateInterpreter(),
            Clock.fixed(Instant.parse(instant), ZoneId.of("Asia/Seoul")));
        tasklet.execute(null, null);
    }

    private void createTables() {
        jdbcTemplate.execute("create table if not exists payments (id bigint primary key, status varchar(40),"
            + " raw_provider_response text, provider_order_id varchar(100), updated_at timestamp null,"
            + " deleted_at timestamp null)");
        jdbcTemplate.execute("create table if not exists orders (id bigint primary key, payment_id bigint,"
            + " status varchar(40), stock_reservation_status varchar(40), updated_at timestamp null)");
        jdbcTemplate.execute("create table if not exists orders_items (order_id bigint, product_id bigint,"
            + " quantity int, product_name varchar(100), unit_price bigint)");
        jdbcTemplate.execute("create table if not exists stock (product_id bigint primary key, quantity int)");
        jdbcTemplate.execute("create table if not exists payment_reconciliations (payment_id bigint primary key,"
            + " status varchar(30), local_status varchar(40), pg_status varchar(40), pg_response text,"
            + " attempt_count integer, last_checked_at timestamp)");
    }

    private void clearTables() {
        jdbcTemplate.update("delete from orders_items");
        jdbcTemplate.update("delete from orders");
        jdbcTemplate.update("delete from payment_reconciliations");
        jdbcTemplate.update("delete from payments");
        jdbcTemplate.update("delete from stock");
    }

    private void insertSuccessfulOrder() {
        jdbcTemplate.update("insert into payments(id, status, provider_order_id, updated_at)"
            + " values (1, 'SUCCESS', 'LP-ORD-42', '2026-10-22 11:55:00')");
        jdbcTemplate.update("insert into orders(id, payment_id, status, stock_reservation_status)"
            + " values (21, 1, 'CONFIRMED', 'CONSUMED')");
        jdbcTemplate.update("insert into orders_items(order_id, product_id, quantity, product_name, unit_price)"
            + " values (21, 101, 2, 'item', 1000)");
        jdbcTemplate.update("insert into stock(product_id, quantity) values (101, 3)");
    }

    private String scalar(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }

    private void assertReconciliation(String status, String localStatus, String pgStatus, String response,
                                      int attemptCount) {
        assertThat(jdbcTemplate.queryForObject("select count(*) from payment_reconciliations where payment_id = 1",
            Integer.class)).isEqualTo(1);
        assertThat(scalar("select status from payment_reconciliations where payment_id = 1")).isEqualTo(status);
        assertThat(scalar("select local_status from payment_reconciliations where payment_id = 1"))
            .isEqualTo(localStatus);
        assertThat(scalar("select pg_status from payment_reconciliations where payment_id = 1")).isEqualTo(pgStatus);
        assertThat(scalar("select pg_response from payment_reconciliations where payment_id = 1"))
            .isEqualTo(response);
        assertThat(jdbcTemplate.queryForObject(
            "select attempt_count from payment_reconciliations where payment_id = 1", Integer.class))
            .isEqualTo(attemptCount);
    }

    private void assertBusinessDataUnchanged() {
        assertThat(scalar("select status from payments where id = 1")).isEqualTo("SUCCESS");
        assertThat(scalar("select status from orders where id = 21")).isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject("select quantity from stock where product_id = 101", Integer.class))
            .isEqualTo(3);
    }
}
