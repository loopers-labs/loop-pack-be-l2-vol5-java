package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderEventRelay;
import com.loopers.domain.order.OrderEventOutbox;
import com.loopers.domain.order.OrderRepository;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "kafka.topics.order-events=" + OrderEventRelayRejectedTest.TOPIC)
class OrderEventRelayRejectedTest {

    static final String TOPIC = "order-events-relay-rejecting";

    private final OrderConfirmations orders;
    private final OrderEventPublishMetrics metrics;
    private final OrderEventRelay relay;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    OrderEventRelayRejectedTest(OrderRepository orderRepository, TransactionTemplate transactionTemplate,
                                OrderEventOutbox outbox, MeterRegistry meterRegistry, OrderEventRelay relay,
                                JdbcTemplate jdbcTemplate, DatabaseCleanUp databaseCleanUp) {
        this.orders = new OrderConfirmations(orderRepository, transactionTemplate, outbox);
        this.metrics = new OrderEventPublishMetrics(meterRegistry);
        this.relay = relay;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeAll
    static void createRejectingTopic() {
        KafkaTestContainersConfig.createTopic(TOPIC, 1, Map.of("max.message.bytes", "1"));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("broker 가 거절하면 BLOCKED 로 옮기고 원인을 남기며, 다시 고르지 않는다")
    void blocksRejectedEvent() {
        Long orderId = orders.pendingOrderId();
        orders.commit(orderId);
        double rejectionsBefore = metrics.failuresCausedBy(RecordTooLargeException.class);

        relay.relayOnce(10);

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "select status, attempt_count, last_error from order_event_outbox where order_id = ?", orderId);
        assertThat(row.get("status")).isEqualTo("BLOCKED");
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat((String) row.get("last_error")).startsWith("RecordTooLargeException");
        assertThat(metrics.failuresCausedBy(RecordTooLargeException.class) - rejectionsBefore).isEqualTo(1);
        assertThat(relay.relayOnce(10)).isZero();
    }
}
