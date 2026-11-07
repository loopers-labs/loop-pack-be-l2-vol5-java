package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderEventOutbox;
import com.loopers.domain.order.OrderRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"outbox.relay.enabled=true", "outbox.relay.interval=100ms"})
@DirtiesContext
class OrderEventRelayScheduleTest {

    private static final Duration DEADLINE = Duration.ofSeconds(15);

    private final OrderConfirmations orders;
    private final OrderEventKafkaSender sender;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    OrderEventRelayScheduleTest(OrderRepository orderRepository, TransactionTemplate transactionTemplate,
                                OrderEventOutbox outbox, OrderEventKafkaSender sender, JdbcTemplate jdbcTemplate,
                                DatabaseCleanUp databaseCleanUp) {
        this.orders = new OrderConfirmations(orderRepository, transactionTemplate, outbox);
        this.sender = sender;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private static boolean within(Duration deadline, BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + deadline.toNanos();
        while (System.nanoTime() < until) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }

    @Test
    @DisplayName("켜 두면 relay 전용 스레드 하나가 주기마다 PENDING 을 보내 PUBLISHED 로 바꾼다")
    void relaysOnItsOwnThread() throws InterruptedException {
        Long orderId = orders.pendingOrderId();
        orders.commit(orderId);

        boolean published = within(DEADLINE, () -> "PUBLISHED".equals(jdbcTemplate.queryForObject(
            "select status from order_event_outbox where order_id = ?", String.class, orderId)));

        assertThat(published).isTrue();
        assertThat(Thread.getAllStackTraces().keySet())
            .filteredOn(thread -> thread.getName().equals("outbox-relay"))
            .hasSize(1);
    }

    @Test
    @DisplayName("relay 의 대기 상한은 producer 의 delivery.timeout.ms 에 여유 5 초를 더한 값이다")
    void waitsAsLongAsProducerDeliveryTimeout() {
        assertThat(sender.waitLimit()).isEqualTo(Duration.ofSeconds(35));
    }
}
