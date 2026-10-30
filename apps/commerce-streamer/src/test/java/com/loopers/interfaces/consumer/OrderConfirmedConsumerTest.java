package com.loopers.interfaces.consumer;

import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 실제 broker 의 record 가 listener 를 거쳐 구매 활동으로 저장되고, 저장 뒤에 offset 이 commit 되는지 확인한다. */
@SpringBootTest
class OrderConfirmedConsumerTest {

    private static final String TOPIC = "order-events-consumer-" + UUID.randomUUID();
    private static final String GROUP = "purchase-activity-consumer-" + UUID.randomUUID();
    private static final Duration DEADLINE = Duration.ofSeconds(20);

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("order-event.topic", () -> TOPIC);
        registry.add("order-event.consumer-group", () -> GROUP);
    }

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    @Qualifier(OrderActivityKafkaConfig.ORDER_ACTIVITY_LISTENER)
    private ConcurrentKafkaListenerContainerFactory<String, byte[]> listenerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void cleanUp() {
        databaseCleanUp.truncateAllTables();
    }

    private long publish(long orderId) throws Exception {
        String json = """
            {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:%d",
             "orderId":%d,"confirmedAt":"2026-09-07T01:00:00Z",
             "items":[{"productId":7,"quantity":2},{"productId":8,"quantity":1}]}""".formatted(orderId, orderId);
        return kafkaTemplate.send(TOPIC, String.valueOf(orderId), json.getBytes(StandardCharsets.UTF_8))
            .get(10, TimeUnit.SECONDS).getRecordMetadata().offset();
    }

    private Long committedOffset() throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS).get(new TopicPartition(TOPIC, 0));
            return committed == null ? null : committed.offset();
        }
    }

    private long activityRows() {
        return jdbcTemplate.queryForObject("select count(*) from product_purchase_activity", Long.class);
    }

    @DisplayName("주문 확정 record 를 받으면 품목별 구매 활동으로 저장하고, 저장한 뒤에야 그 record 다음 위치를 commit 한다.")
    @Test
    void savesPurchaseActivity_thenCommitsNextOffset() throws Exception {
        // act
        long offset = publish(42L);

        // assert
        await().atMost(DEADLINE).untilAsserted(() -> assertThat(activityRows()).isEqualTo(2));
        await().atMost(DEADLINE).untilAsserted(() -> assertThat(committedOffset()).isEqualTo(offset + 1));
    }

    @DisplayName("listener 는 자동 commit 없이 record 단위 즉시 ack 로 동작하고, 새 group 도 처음부터 읽는다.")
    @Test
    void usesManualImmediateAckWithoutAutoCommit() {
        ContainerProperties properties = listenerFactory.getContainerProperties();

        assertThat(properties.getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        assertThat(listenerFactory.isBatchListener()).isFalse();
        assertThat(String.valueOf(listenerFactory.getConsumerFactory().getConfigurationProperties()
            .get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG))).isEqualTo("false");
        assertThat(listenerFactory.getConsumerFactory().getConfigurationProperties()
            .get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).isEqualTo("earliest");
    }
}
