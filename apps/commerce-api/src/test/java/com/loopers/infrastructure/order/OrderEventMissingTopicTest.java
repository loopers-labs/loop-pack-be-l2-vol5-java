package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "kafka.topics.order-events=" + OrderEventMissingTopicTest.MISSING_TOPIC)
class OrderEventMissingTopicTest {

    static final String MISSING_TOPIC = "order-events-missing";

    private final OrderConfirmations orders;
    private final OrderEventPublishMetrics metrics;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    OrderEventMissingTopicTest(OrderRepository orderRepository, TransactionTemplate transactionTemplate,
                               ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry,
                               DatabaseCleanUp databaseCleanUp) {
        this.orders = new OrderConfirmations(orderRepository, transactionTemplate, eventPublisher);
        this.metrics = new OrderEventPublishMetrics(meterRegistry);
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Set<String> topics() throws Exception {
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers()))) {
            return admin.listTopics().names().get(10, TimeUnit.SECONDS);
        }
    }

    @DisplayName("EVT-013, EVT-014, EVT-015: topic 이 없으면 확정은 1 초 남짓 만에 끝나고, 실패 지표가 원인과 함께 하나 늘고, 주문은 CONFIRMED 그대로다")
    @Test
    void failsWithinMaxBlockAndKeepsConfirmation() throws Exception {
        Long orderId = orders.pendingOrderId();
        double successesBefore = metrics.successes();
        double timeoutsBefore = metrics.failuresCausedBy(TimeoutException.class);
        long startedAt = System.nanoTime();

        orders.commit(orderId);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).isBetween(Duration.ofMillis(900), Duration.ofSeconds(3));
        assertThat(metrics.failuresCausedBy(TimeoutException.class) - timeoutsBefore).isEqualTo(1);
        assertThat(metrics.successes() - successesBefore).isZero();
        assertThat(orders.statusOf(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(topics()).doesNotContain(MISSING_TOPIC);
    }
}
