package com.loopers.interfaces.consumer;

import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.ProductPurchaseActivityRepository;
import com.loopers.domain.purchase.PurchaseLine;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** DB 반영에 실패한 record 를 건너뛰지 않고, 원인을 고친 뒤 같은 group 으로 재시작하면 다시 받아 저장하는지 확인한다. */
@SpringBootTest
class OrderConfirmedConsumerFailureTest {

    private static final String TOPIC = "order-events-failure-" + UUID.randomUUID();
    private static final String GROUP = "purchase-activity-failure-" + UUID.randomUUID();
    private static final Duration DEADLINE = Duration.ofSeconds(30);

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
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private ProductPurchaseActivityRepository activityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void cleanUp() {
        databaseCleanUp.truncateAllTables();
    }

    private Long committedOffset() throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS).get(new TopicPartition(TOPIC, 0));
            return committed == null ? null : committed.offset();
        }
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
    }

    @DisplayName("저장에 실패하면 전부 되돌리고 ack 없이 컨테이너를 멈추며, 원인을 없앤 뒤 재시작하면 같은 record 를 다시 받아 저장하고 ack 한다.")
    @Test
    void stopsWithoutAck_thenRedeliversAfterRestart() throws Exception {
        // arrange: 상품 8의 활동이 이미 있어(완료 기록 없이 남은 행) 두 번째 품목 저장이 유일 제약으로 실패한다.
        activityRepository.saveAllAndFlush(List.of(
            new ProductPurchaseActivity(42L, new PurchaseLine(8L, 1), ZonedDateTime.parse("2026-09-07T01:00:00Z"))
        ));
        String json = """
            {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:42",
             "orderId":42,"confirmedAt":"2026-09-07T01:00:00Z",
             "items":[{"productId":7,"quantity":2},{"productId":8,"quantity":1}]}""";
        MessageListenerContainer container = registry.getListenerContainer(OrderConfirmedConsumer.LISTENER_ID);

        // act 1: 실패하는 record 를 받는다
        long offset = kafkaTemplate.send(TOPIC, "42", json.getBytes(StandardCharsets.UTF_8))
            .get(10, TimeUnit.SECONDS).getRecordMetadata().offset();

        // assert 1: 컨테이너가 멈추고, 아무것도 저장·ack 되지 않았다(미리 넣은 행만 남는다)
        await().atMost(DEADLINE).untilAsserted(() -> assertThat(container.isRunning()).isFalse());
        assertThat(count("processed_order_confirmation")).isZero();
        assertThat(count("product_purchase_activity")).isEqualTo(1);
        Long committedAfterFailure = committedOffset();
        assertThat(committedAfterFailure == null || committedAfterFailure <= offset).isTrue();

        // act 2: 원인을 없애고 같은 group 으로 다시 시작한다
        jdbcTemplate.update("delete from product_purchase_activity");
        container.start();

        // assert 2: 같은 record 를 다시 받아 저장하고 ack 한다
        await().atMost(DEADLINE).untilAsserted(() -> assertThat(count("product_purchase_activity")).isEqualTo(2));
        await().atMost(DEADLINE).untilAsserted(() -> assertThat(committedOffset()).isEqualTo(offset + 1));
        assertThat(count("processed_order_confirmation")).isEqualTo(1);
    }
}
