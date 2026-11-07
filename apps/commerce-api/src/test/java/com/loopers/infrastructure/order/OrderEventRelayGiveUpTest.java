package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderEventRelay;
import com.loopers.application.order.OrderEventResume;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "kafka.topics.order-events=order-events-relay-give-up",
    "outbox.relay.give-up-after=1h"
})
class OrderEventRelayGiveUpTest {

    private final OrderConfirmations orders;
    private final OrderEventRelay relay;
    private final OrderEventResume resume;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    OrderEventRelayGiveUpTest(OrderRepository orderRepository, TransactionTemplate transactionTemplate,
                              OrderEventOutbox outbox, OrderEventRelay relay, OrderEventResume resume,
                              JdbcTemplate jdbcTemplate, DatabaseCleanUp databaseCleanUp) {
        this.orders = new OrderConfirmations(orderRepository, transactionTemplate, outbox);
        this.relay = relay;
        this.resume = resume;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Long confirmedOrderRetryingFor(String interval) {
        Long orderId = orders.pendingOrderId();
        orders.commit(orderId);
        jdbcTemplate.update("update order_event_outbox set retry_since = utc_timestamp(6) - interval "
            + interval + " where order_id = ?", orderId);
        return orderId;
    }

    private Map<String, Object> row(Long orderId) {
        return jdbcTemplate.queryForMap("""
            select event_id, status, attempt_count, last_error,
                   timestampdiff(SECOND, retry_since, utc_timestamp(6)) retrying_seconds
            from order_event_outbox where order_id = ?
            """, orderId);
    }

    @Test
    @DisplayName("재시도를 시작한 지 상한을 넘긴 행이 또 broker 확인을 받지 못하면 BLOCKED 로 옮기고 원인을 남기며, 다시 고르지 않는다")
    void blocksWhenRetryingLongerThanLimit() {
        Long orderId = confirmedOrderRetryingFor("2 hour");

        relay.relayOnce(10);

        Map<String, Object> blocked = row(orderId);
        assertThat(blocked.get("status")).isEqualTo("BLOCKED");
        assertThat(((Number) blocked.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat((String) blocked.get("last_error")).contains("TimeoutException");
        assertThat(relay.relayOnce(10)).isZero();
    }

    @Test
    @DisplayName("재시도를 시작한 지 상한 안이면 broker 확인을 받지 못해도 PENDING 으로 남는다")
    void keepsPendingWithinLimit() {
        Long orderId = confirmedOrderRetryingFor("30 minute");

        relay.relayOnce(10);

        assertThat(row(orderId).get("status")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("재개하면 재시도 시작 시각이 지금으로 바뀌어, 재개 뒤 한 번 실패해도 BLOCKED 가 되지 않는다")
    void resumeRestartsRetryWindow() {
        Long orderId = confirmedOrderRetryingFor("2 hour");
        relay.relayOnce(10);
        String eventId = (String) row(orderId).get("event_id");

        resume.resume(List.of(eventId));

        assertThat(((Number) row(orderId).get("retrying_seconds")).longValue()).isLessThan(60);
        relay.relayOnce(10);
        assertThat(row(orderId).get("status")).isEqualTo("PENDING");
    }
}
