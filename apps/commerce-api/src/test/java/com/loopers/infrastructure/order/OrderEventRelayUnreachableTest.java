package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderEventRelay;
import com.loopers.domain.order.OrderEventOutbox;
import com.loopers.domain.order.OrderRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "kafka.topics.order-events=order-events-relay-missing")
class OrderEventRelayUnreachableTest {

    private final OrderConfirmations orders;
    private final OrderEventRelay relay;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    OrderEventRelayUnreachableTest(OrderRepository orderRepository, TransactionTemplate transactionTemplate,
                                   OrderEventOutbox outbox, OrderEventRelay relay, JdbcTemplate jdbcTemplate,
                                   DatabaseCleanUp databaseCleanUp) {
        this.orders = new OrderConfirmations(orderRepository, transactionTemplate, outbox);
        this.relay = relay;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("broker 확인을 받지 못하면 PENDING 으로 남기고 시도 수 · 다음 시각 · 원인을 기록하며, 그 시각 전에는 다시 고르지 않는다")
    void keepsPendingAndBacksOff() {
        Long orderId = orders.pendingOrderId();
        orders.commit(orderId);

        relay.relayOnce(10);

        Map<String, Object> row = jdbcTemplate.queryForMap("""
            select status, attempt_count, last_error,
                   timestampdiff(MICROSECOND, utc_timestamp(6), next_attempt_at) as wait_micros
            from order_event_outbox where order_id = ?
            """, orderId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat((String) row.get("last_error")).contains("TimeoutException");
        assertThat(((Number) row.get("wait_micros")).longValue()).isPositive();
        assertThat(relay.relayOnce(10)).isZero();
    }
}
