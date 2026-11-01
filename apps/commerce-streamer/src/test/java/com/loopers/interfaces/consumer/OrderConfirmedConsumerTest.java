package com.loopers.interfaces.consumer;

import com.loopers.support.kafka.CommittedOffsets;
import com.loopers.support.kafka.OrderConfirmedMessages;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void cleanUp() {
        databaseCleanUp.truncateAllTables();
    }

    private long publish(long orderId) throws Exception {
        return kafkaTemplate.send(TOPIC, String.valueOf(orderId), OrderConfirmedMessages.validBytes(orderId))
            .get(10, TimeUnit.SECONDS).getRecordMetadata().offset();
    }

    private Long committedOffset() {
        return CommittedOffsets.of(GROUP, new TopicPartition(TOPIC, 0));
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
}
