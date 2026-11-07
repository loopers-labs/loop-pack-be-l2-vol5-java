package com.loopers.application.order;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.product.Price;
import com.loopers.support.error.DomainError;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OrderEventRecoveryTest {

    private static final PgFixture PG = PgFixture.start();
    private static final Long USER = 1L;
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    private final OrderEventRecovery recovery;
    private final TestPayments payments;
    private final OrderFacade orderFacade;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    private Long coat;

    @Autowired
    OrderEventRecoveryTest(OrderEventRecovery recovery, TestPayments payments, OrderFacade orderFacade,
                           BrandFacade brandFacade, ProductFacade productFacade, JdbcTemplate jdbcTemplate,
                           DatabaseCleanUp databaseCleanUp) {
        this.recovery = recovery;
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

    private Long confirmedOrder() {
        Long orderId = placeOrder();
        payments.startAndSucceed(USER, orderId, NOW);
        return orderId;
    }

    private Map<String, Object> outboxRow(Long orderId) {
        return jdbcTemplate.queryForMap("""
            select event_id, record_key, event_type, schema_version, payload, status
            from order_event_outbox where order_id = ?
            """, orderId);
    }

    private int outboxRows(Long orderId) {
        return jdbcTemplate.queryForObject(
            "select count(*) from order_event_outbox where order_id = ?", Integer.class, orderId);
    }

    private void loseEvent(Long orderId) {
        jdbcTemplate.update("delete from order_event_outbox where order_id = ?", orderId);
    }

    private Map<String, Object> row(String table, String column, Long id) {
        return jdbcTemplate.queryForMap("select * from " + table + " where " + column + " = ?", id);
    }

    @Test
    @DisplayName("확정됐는데 outbox 가 없는 주문을 복구하면 같은 eventId·key·payload 로 PENDING 한 행이 생기고, PG·주문·재고·결제는 그대로다")
    void recoversSameIdentity() {
        Long orderId = confirmedOrder();
        Map<String, Object> original = outboxRow(orderId);
        loseEvent(orderId);
        PG.reset();
        Map<String, Object> order = row("orders", "id", orderId);
        Map<String, Object> product = row("product", "id", coat);
        Map<String, Object> payment = row("payment", "order_id", orderId);

        OrderEventRecovery.Result result = recovery.recover(orderId);

        Map<String, Object> recovered = outboxRow(orderId);
        assertThat(result).isEqualTo(OrderEventRecovery.Result.APPENDED);
        assertThat(recovered.get("event_id")).isEqualTo(original.get("event_id"));
        assertThat(recovered.get("record_key")).isEqualTo(original.get("record_key"));
        assertThat(recovered.get("event_type")).isEqualTo(original.get("event_type"));
        assertThat(recovered.get("schema_version")).isEqualTo(original.get("schema_version"));
        assertThat((byte[]) recovered.get("payload")).isEqualTo((byte[]) original.get("payload"));
        assertThat(recovered.get("status")).isEqualTo("PENDING");
        assertThat(PG.receivedRequests()).isEmpty();
        assertThat(row("orders", "id", orderId)).isEqualTo(order);
        assertThat(row("product", "id", coat)).isEqualTo(product);
        assertThat(row("payment", "order_id", orderId)).isEqualTo(payment);
    }

    @Test
    @DisplayName("같은 주문을 다시 복구하면 이미 있다고 답하고 행은 하나다")
    void secondRecoveryKeepsOneRow() {
        Long orderId = confirmedOrder();
        loseEvent(orderId);
        recovery.recover(orderId);

        OrderEventRecovery.Result again = recovery.recover(orderId);

        assertThat(again).isEqualTo(OrderEventRecovery.Result.ALREADY_PRESENT);
        assertThat(outboxRows(orderId)).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 발행된 행이 있으면 다시 PENDING 으로 되돌리지 않는다")
    void leavesPublishedRowAlone() {
        Long orderId = confirmedOrder();
        jdbcTemplate.update("update order_event_outbox set status = 'PUBLISHED' where order_id = ?", orderId);

        OrderEventRecovery.Result result = recovery.recover(orderId);

        assertThat(result).isEqualTo(OrderEventRecovery.Result.ALREADY_PRESENT);
        assertThat(outboxRow(orderId).get("status")).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("저장된 행의 payload 가 원본 사실과 다르면 덮지 않고 충돌로 실패한다")
    void refusesConflictingRow() {
        Long orderId = confirmedOrder();
        jdbcTemplate.update("update order_event_outbox set payload = x'7B7D' where order_id = ?", orderId);

        assertThatThrownBy(() -> recovery.recover(orderId))
            .hasFieldOrPropertyWithValue("error", DomainError.ORDER_EVENT_CONFLICT);
        assertThat((byte[]) outboxRow(orderId).get("payload")).isEqualTo(new byte[] {'{', '}'});
    }

    @Test
    @DisplayName("확정되지 않은 주문은 거절하고 행을 만들지 않는다")
    void refusesUnconfirmedOrder() {
        Long orderId = placeOrder();

        assertThatThrownBy(() -> recovery.recover(orderId))
            .hasFieldOrPropertyWithValue("error", DomainError.ORDER_NOT_CONFIRMED);
        assertThat(outboxRows(orderId)).isZero();
    }
}
