package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.PurchaseActivityRepository;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 과제 2절 "정상 발행·소비 사례"의 소비 쪽 (📄 p.4). commerce-api 가 실제 broker 에 발행한 record 를
 * 녹화한 experiments/delivery-gap-case2.json 의 key·value bytes 를 그대로 이 클래스의 격리 topic 에 넣고,
 * 실제 listener·parser·DB TX 로 구매 활동이 저장되는지 본다 (7절 표 "실제 PG 확정→Kafka→streamer").
 */
@SpringBootTest(properties = {
    "purchase-activity.topic=" + OrderConfirmedReplayTest.TOPIC,
    "purchase-activity.group-id=" + OrderConfirmedReplayTest.GROUP
})
class OrderConfirmedReplayTest {

    static final String TOPIC = "order-events-OrderConfirmedReplayTest";
    static final String GROUP = "purchase-activity-OrderConfirmedReplayTest";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

    static {
        KafkaTestContainersConfig.createTopic(TOPIC, 1);
    }

    @Autowired
    private PurchaseActivityRepository purchaseActivityRepository;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("녹화한 주문 2 의 record 를 재생하면 장부 1행과 구매 활동 2행(상품 1 × 2, 상품 2 × 1)이 녹화된 confirmedAt 으로 저장되고 ack 된다.")
    @Test
    void storesPurchaseActivity_fromRecordedBrokerRecord() throws Exception {
        // arrange - 녹화 record
        JsonNode recorded = objectMapper.readTree(new ClassPathResource("experiments/delivery-gap-case2.json").getInputStream());
        String key = recorded.get("key").asText();
        byte[] value = recorded.get("value").asText().getBytes(StandardCharsets.UTF_8);
        long orderId = recorded.get("orderId").asLong();
        Instant confirmedAt = Instant.parse(recorded.get("confirmedAt").asText());

        // act - 같은 bytes 를 실제 broker 에 넣고 실제 listener 가 처리할 때까지 기다린다 (ack = committed offset 전진)
        RecordMetadata metadata = send(key, value);
        try (AdminClient admin = AdminClient.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers()))) {
            await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
                OffsetAndMetadata committed = admin.listConsumerGroupOffsets(GROUP)
                    .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS).get(PARTITION);
                assertThat(committed).isNotNull();
                assertThat(committed.offset()).isEqualTo(metadata.offset() + 1);
            });
        }

        // assert - 저장된 사실이 녹화값(= commerce-api DB 의 품목·수량·confirmedAt)과 같다
        ProcessedOrderConfirmation processed = purchaseActivityRepository.findProcessedByOrderId(orderId).orElseThrow();
        assertThat(processed.getEventId()).isEqualTo(recorded.get("eventId").asText());
        assertThat(processed.getConfirmedAt().toInstant()).isEqualTo(confirmedAt);
        List<ProductPurchaseActivity> activities = purchaseActivityRepository.findActivitiesByOrderId(orderId);
        assertThat(activities).hasSize(recorded.get("items").size());
        for (int i = 0; i < activities.size(); i++) {
            assertThat(activities.get(i).getProductId()).isEqualTo(recorded.get("items").get(i).get("productId").asLong());
            assertThat(activities.get(i).getQuantity()).isEqualTo(recorded.get("items").get(i).get("quantity").asInt());
            assertThat(activities.get(i).getConfirmedAt().toInstant()).isEqualTo(confirmedAt);
        }
    }

    private static RecordMetadata send(String key, byte[] value) throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
            return producer.send(new ProducerRecord<>(TOPIC, key, value)).get(10, TimeUnit.SECONDS);
        }
    }
}
