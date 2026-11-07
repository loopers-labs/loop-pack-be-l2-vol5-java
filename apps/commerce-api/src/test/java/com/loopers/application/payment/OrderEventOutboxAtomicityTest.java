package com.loopers.application.payment;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.order.OrderCreateCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.product.Price;
import com.loopers.support.payment.TestPayments;
import com.loopers.support.pg.PgFixture;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OrderEventOutboxAtomicityTest {

    private static final PgFixture PG = PgFixture.start();
    private static final Long USER = 1L;
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    private final TestPayments payments;
    private final OrderFacade orderFacade;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    private Long coat;

    @Autowired
    OrderEventOutboxAtomicityTest(TestPayments payments, OrderFacade orderFacade, BrandFacade brandFacade,
                                  ProductFacade productFacade, JdbcTemplate jdbcTemplate,
                                  DatabaseCleanUp databaseCleanUp) {
        this.payments = payments;
        this.orderFacade = orderFacade;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @DynamicPropertySource
    static void pgBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("pg.base-url", PG::baseUrl);
    }

    @AfterAll
    static void stopPg() {
        PG.close();
    }

    @BeforeEach
    void setUp() {
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        coat = productFacade.register(brandId, "코트", Price.of(3_000)).getId();
        productFacade.adjustStock(coat, Quantity.of(10));
        PG.reset();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Long placeOrder() {
        return orderFacade.place(new OrderCreateCommand(USER,
            List.of(new OrderCreateCommand.Line(coat, OrderQuantity.of(2)))), NOW).getId();
    }

    private int outboxRows(Long orderId) {
        return jdbcTemplate.queryForObject(
            "select count(*) from order_event_outbox where order_id = ?", Integer.class, orderId);
    }

    private String orderStatus(Long orderId) {
        return jdbcTemplate.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    @Test
    @DisplayName("최초 성공 반영과 같은 TX 에서 PENDING outbox 한 행이 저장되고, 재시도 시각은 DB 시각이다")
    void confirmAppendsPendingOutbox() {
        Long orderId = placeOrder();

        payments.startAndSucceed(USER, orderId, NOW);

        Map<String, Object> row = jdbcTemplate.queryForMap("""
            select event_id, record_key, event_type, schema_version, status, attempt_count, published_at,
                   timestampdiff(SECOND, next_attempt_at, utc_timestamp(6)) as due_seconds_ago
            from order_event_outbox where order_id = ?
            """, orderId);
        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
        assertThat(row.get("event_id")).isEqualTo("order-confirmed:v1:" + orderId);
        assertThat(row.get("record_key")).isEqualTo(String.valueOf(orderId));
        assertThat(row.get("event_type")).isEqualTo("OrderConfirmed");
        assertThat(((Number) row.get("schema_version")).intValue()).isEqualTo(1);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("attempt_count")).intValue()).isZero();
        assertThat(row.get("published_at")).isNull();
        assertThat(((Number) row.get("due_seconds_ago")).longValue()).isBetween(0L, 10L);
    }

    @Test
    @DisplayName("같은 거래의 성공을 다시 반영해도 outbox 는 한 행이다")
    void repeatedSuccessKeepsOneRow() {
        Long orderId = placeOrder();
        Payment payment = payments.startAndSucceed(USER, orderId, NOW);

        payments.succeed(payment, NOW);

        assertThat(outboxRows(orderId)).isEqualTo(1);
    }

    private int stockOf(Long productId) {
        return jdbcTemplate.queryForObject("select quantity from product where id = ?", Integer.class, productId);
    }

    private void occupyEventId(Long orderId) {
        jdbcTemplate.update("""
            insert into order_event_outbox
              (event_id, order_id, event_type, schema_version, topic, record_key, payload,
               status, attempt_count, created_at, updated_at)
            values (?, ?, 'OrderConfirmed', 1, 'order-events', ?, x'7B7D', 'PENDING', 0, now(6), now(6))
            """, "order-confirmed:v1:" + orderId, orderId, String.valueOf(orderId));
    }

    @Test
    @DisplayName("outbox insert 가 실패하면 확정이 rollback 되어 주문은 PENDING, 결제 상태와 예약한 재고는 그대로이고, outbox 는 미리 있던 한 행이며, PG 결제 생성은 늘지 않는다")
    void outboxFailureRollsBackConfirmation() {
        Long orderId = placeOrder();
        payments.start(USER, orderId, NOW);
        occupyEventId(orderId);
        Payment payment = payments.started(orderId);
        int reservedStock = stockOf(coat);
        int pgCreates = PG.createCount();

        assertThatThrownBy(() -> payments.succeed(payment, NOW))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        assertThat(payments.started(orderId).getStatus()).isEqualTo(payment.getStatus());
        assertThat(stockOf(coat)).isEqualTo(reservedStock);
        assertThat(outboxRows(orderId)).isEqualTo(1);
        assertThat(PG.createCount()).isEqualTo(pgCreates);
    }

    @Test
    @DisplayName("rollback 뒤 같은 거래의 성공을 다시 반영하면 주문이 CONFIRMED 가 되고, 재고는 한 번만 소비되며, outbox 는 한 행이고, PG 결제 생성은 늘지 않는다")
    void reapplyAfterRollbackConfirmsOnce() {
        Long orderId = placeOrder();
        payments.start(USER, orderId, NOW);
        occupyEventId(orderId);
        Payment payment = payments.started(orderId);
        int reservedStock = stockOf(coat);
        int pgCreates = PG.createCount();
        assertThatThrownBy(() -> payments.succeed(payment, NOW))
            .isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.update("delete from order_event_outbox where order_id = ?", orderId);

        payments.succeed(payment, NOW);

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "select status, convert(payload using utf8mb4) payload from order_event_outbox where order_id = ?", orderId);
        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
        assertThat(stockOf(coat)).isEqualTo(reservedStock);
        assertThat(outboxRows(orderId)).isEqualTo(1);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat((String) row.get("payload")).contains("\"orderId\":" + orderId);
        assertThat(PG.createCount()).isEqualTo(pgCreates);
    }
}
