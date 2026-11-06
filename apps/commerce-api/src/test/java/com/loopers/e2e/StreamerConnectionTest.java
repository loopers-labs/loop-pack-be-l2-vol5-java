package com.loopers.e2e;

import com.loopers.application.outbox.OutboxPublisher;
import com.loopers.contract.order.OrderConfirmed;
import com.loopers.contract.order.OrderConfirmedItem;
import com.loopers.domain.outbox.Outbox;
import com.loopers.support.kafka.TopicReader;
import com.loopers.support.streamer.StreamerProcess;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** api 가 보낸 주문 확정 이벤트를 별도 프로세스로 띄운 실제 streamer 가 구매 활동으로 저장하는지 연결만 확인한다. */
@SpringBootTest
class StreamerConnectionTest {

    private static final String TOPIC = "streamer-connection-" + UUID.randomUUID();

    @DynamicPropertySource
    static void topicProperty(DynamicPropertyRegistry registry) {
        registry.add("order-event.topic", () -> TOPIC);
    }

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DisplayName("api 가 주문 9001(상품 7×2, 8×1)의 확정 이벤트를 보내면, 실제 streamer 가 상품별 구매 활동 2행과 완료 기록 1행을 남긴다.")
    @Test
    void savesPurchaseActivity_whenApiPublishesOrderConfirmed() throws Exception {
        // given: topic 을 만들고, 그 topic 을 읽는 streamer 를 띄운다.
        TopicReader.createTopic(KafkaTestContainersConfig.bootstrapServers(), TOPIC);
        ByteBuffer buffer = OrderConfirmed.newBuilder()
            .setEventId("order-confirmed:v1:9001")
            .setOrderId(9001L)
            .setConfirmedAt(Instant.parse("2026-11-07T01:00:00Z"))
            .setItems(List.of(new OrderConfirmedItem(7L, 2), new OrderConfirmedItem(8L, 1)))
            .build()
            .toByteBuffer();
        byte[] payload = new byte[buffer.remaining()];
        buffer.get(payload);

        try (StreamerProcess streamer = StreamerProcess.start(TOPIC, "purchase-activity-" + UUID.randomUUID())) {
            // when: api 의 실제 전송 어댑터로 보낸다.
            outboxPublisher.publish(Outbox.record(
                "order-confirmed:v1:9001", 9001L, "OrderConfirmed", "v1", "9001", payload, ZonedDateTime.now()
            ));

            // then: 20초 안에 상품별 구매 활동이 생긴다.
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            List<String> activities = List.of();
            while (activities.size() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(200);
                activities = jdbcTemplate.query(
                    "select product_id, quantity from product_purchase_activity where order_id = 9001 order by product_id",
                    (rs, rowNum) -> rs.getLong("product_id") + "x" + rs.getInt("quantity")
                );
            }
            assertThat(activities).as(streamer.logTail()).containsExactly("7x2", "8x1");
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from processed_order_confirmation where event_id = 'order-confirmed:v1:9001'", Long.class
            )).isEqualTo(1L);
        }
    }
}
