package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderEventRelay;
import com.loopers.application.order.OrderEventRelayTransactions;
import com.loopers.domain.order.OrderEventDelivery;
import com.loopers.domain.order.OrderEventOutbox;
import com.loopers.domain.order.OrderEventSender;
import com.loopers.domain.order.OrderRepository;
import com.loopers.support.kafka.TopicRecords;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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

@SpringBootTest
class OrderEventRelayTest {

    private static final String TOPIC = KafkaTestContainersConfig.ORDER_EVENTS;

    private final OrderConfirmations orders;
    private final OrderEventPublishMetrics metrics;
    private final OrderEventRelay relay;
    private final OrderEventRelayTransactions transactions;
    private final OrderEventSender sender;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    OrderEventRelayTest(OrderRepository orderRepository, TransactionTemplate transactionTemplate,
                        OrderEventOutbox outbox, MeterRegistry meterRegistry, OrderEventRelay relay,
                        OrderEventRelayTransactions transactions, OrderEventSender sender,
                        JdbcTemplate jdbcTemplate, DatabaseCleanUp databaseCleanUp) {
        this.orders = new OrderConfirmations(orderRepository, transactionTemplate, outbox);
        this.metrics = new OrderEventPublishMetrics(meterRegistry);
        this.relay = relay;
        this.transactions = transactions;
        this.sender = sender;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Long confirmedOrder() {
        Long orderId = orders.pendingOrderId();
        orders.commit(orderId);
        return orderId;
    }

    private Map<String, Object> outboxRow(Long orderId) {
        return jdbcTemplate.queryForMap(
            "select status, attempt_count, published_at, payload from order_event_outbox where order_id = ?", orderId);
    }

    @Test
    @DisplayName("재시도 시각이 된 PENDING 을 저장된 key · payload 그대로 보내고 PUBLISHED 로 바꾼다")
    void publishesDueEvent() {
        Long orderId = confirmedOrder();
        byte[] stored = (byte[]) outboxRow(orderId).get("payload");
        double successesBefore = metrics.successes();

        List<ConsumerRecord<String, byte[]>> records = TopicRecords.during(TOPIC, () -> relay.relayOnce(10));

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.key()).isEqualTo(String.valueOf(orderId));
            assertThat(record.value()).isEqualTo(stored);
        });
        Map<String, Object> row = outboxRow(orderId);
        assertThat(row.get("status")).isEqualTo("PUBLISHED");
        assertThat(row.get("published_at")).isNotNull();
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(metrics.successes() - successesBefore).isEqualTo(1);
    }

    @Test
    @DisplayName("PUBLISHED 는 다시 고르지 않는다")
    void doesNotResendPublished() {
        confirmedOrder();
        relay.relayOnce(10);

        assertThat(relay.relayOnce(10)).isZero();
    }

    @Test
    @DisplayName("broker 확인 뒤 상태를 저장하기 전에 멈추면 PENDING 이 남고, 새로 만든 relay 가 같은 사건을 다른 offset 으로 다시 보낸다")
    void resendsAfterStopBeforeRecord() {
        Long orderId = confirmedOrder();
        byte[] stored = (byte[]) outboxRow(orderId).get("payload");

        List<ConsumerRecord<String, byte[]>> records = TopicRecords.during(TOPIC, () -> {
            assertThat(relay.sendDue(10)).singleElement().isInstanceOf(OrderEventDelivery.Published.class);
            assertThat(outboxRow(orderId).get("status")).isEqualTo("PENDING");
            new OrderEventRelay(transactions, sender).relayOnce(10);
        });

        assertThat(records).hasSize(2);
        assertThat(records).allSatisfy(record -> {
            assertThat(record.key()).isEqualTo(String.valueOf(orderId));
            assertThat(record.value()).isEqualTo(stored);
        });
        assertThat(records).extracting(ConsumerRecord::offset).doesNotHaveDuplicates();
        assertThat(outboxRow(orderId).get("status")).isEqualTo("PUBLISHED");
    }
}
