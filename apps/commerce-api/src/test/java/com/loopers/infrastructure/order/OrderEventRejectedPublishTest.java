package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "kafka.topics.order-events=" + OrderEventRejectedPublishTest.REJECTING_TOPIC)
class OrderEventRejectedPublishTest {

    static final String REJECTING_TOPIC = "order-events-rejecting";

    private final OrderConfirmations orders;
    private final OrderEventPublishMetrics metrics;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    OrderEventRejectedPublishTest(OrderRepository orderRepository, TransactionTemplate transactionTemplate,
                                  ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry,
                                  KafkaTemplate<String, byte[]> kafkaTemplate, DatabaseCleanUp databaseCleanUp) {
        this.orders = new OrderConfirmations(orderRepository, transactionTemplate, eventPublisher);
        this.metrics = new OrderEventPublishMetrics(meterRegistry);
        this.kafkaTemplate = kafkaTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeAll
    static void createRejectingTopic() {
        KafkaTestContainersConfig.createTopic(REJECTING_TOPIC, 1, Map.of("max.message.bytes", "1"));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private long storedRecords() throws Exception {
        TopicPartition partition = new TopicPartition(REJECTING_TOPIC, 0);
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers()))) {
            return admin.listOffsets(Map.of(partition, OffsetSpec.latest()))
                .partitionResult(partition)
                .get(10, TimeUnit.SECONDS)
                .offset();
        }
    }

    @DisplayName("EVT-013, EVT-015: broker 가 record 를 거절하면 실패 지표가 원인과 함께 하나 늘고, 주문은 CONFIRMED 그대로다")
    @Test
    void countsRejectionAndKeepsConfirmation() throws Exception {
        Long orderId = orders.pendingOrderId();
        double successesBefore = metrics.successes();
        double rejectionsBefore = metrics.failuresCausedBy(RecordTooLargeException.class);

        orders.commit(orderId);
        kafkaTemplate.flush();

        assertThat(metrics.failuresCausedBy(RecordTooLargeException.class) - rejectionsBefore).isEqualTo(1);
        assertThat(metrics.successes() - successesBefore).isZero();
        assertThat(orders.statusOf(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(storedRecords()).isZero();
    }
}
