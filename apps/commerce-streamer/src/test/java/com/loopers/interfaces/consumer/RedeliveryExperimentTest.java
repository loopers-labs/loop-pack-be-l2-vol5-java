package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.application.purchase.PurchaseActivityHandler;
import com.loopers.application.purchase.PurchaseOutcome;
import com.loopers.contract.OrderConfirmedContract;
import com.loopers.domain.purchase.PurchaseReading;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RedeliveryExperimentTest {

    private final String topic = "exp-redelivery-" + UUID.randomUUID();
    private final String group = "exp-redelivery-" + UUID.randomUUID();
    private final TopicPartition partition = new TopicPartition(topic, 0);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final OrderEventPayloadReader reader;
    private final PurchaseActivityHandler handler;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    RedeliveryExperimentTest(KafkaTemplate<String, byte[]> kafkaTemplate, OrderEventPayloadReader reader,
                             PurchaseActivityHandler handler, ObjectMapper objectMapper, JdbcTemplate jdbcTemplate,
                             DatabaseCleanUp databaseCleanUp) {
        this.kafkaTemplate = kafkaTemplate;
        this.reader = reader;
        this.handler = handler;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private byte[] confirmationOf(long orderId) throws Exception {
        ObjectNode node = (ObjectNode) objectMapper.readTree(OrderConfirmedContract.example());
        node.put("orderId", orderId);
        node.put("eventId", "order-confirmed:v1:" + orderId);
        return objectMapper.writeValueAsBytes(node);
    }

    private RecordMetadata send(long orderId) throws Exception {
        ProducerRecord<String, byte[]> producerRecord = new ProducerRecord<>(topic, 0, String.valueOf(orderId),
            confirmationOf(orderId));
        return kafkaTemplate.send(producerRecord).get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    private KafkaConsumer<String, byte[]> consumer() {
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, group,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1),
            new StringDeserializer(), new ByteArrayDeserializer());
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private static ConsumerRecord<String, byte[]> next(KafkaConsumer<String, byte[]> consumer) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, byte[]> polled : consumer.poll(Duration.ofMillis(200))) {
                return polled;
            }
        }
        throw new AssertionError("30 초 안에 record 가 오지 않았다");
    }

    private PurchaseOutcome process(ConsumerRecord<String, byte[]> consumerRecord) {
        PurchaseReading reading = reader.read(consumerRecord.key(), consumerRecord.value()).orElseThrow();
        return handler.handle(reading, OrderConfirmedListener.received(consumerRecord));
    }

    private List<Map<String, Object>> activitiesOf(long orderId) {
        return jdbcTemplate.queryForList(
            "select id, product_id, quantity, updated_at from purchase_activity where order_id = ? order by id", orderId);
    }

    @DisplayName("EXP-005: DB 에 반영한 뒤 offset 을 commit 하지 못하고 consumer 가 닫히면, 같은 group 의 새 consumer 는 그 record 부터 다시 받고 효과 없이 넘긴다")
    @Test
    void redeliversUncommittedRecordWithoutSecondEffect() throws Exception {
        KafkaTestContainersConfig.createTopic(topic, 1, Map.of());
        RecordMetadata prepared = send(41L);
        RecordMetadata target = send(42L);

        KafkaConsumer<String, byte[]> first = consumer();
        ConsumerRecord<String, byte[]> preparedRecord = next(first);
        assertThat(process(preparedRecord)).isEqualTo(PurchaseOutcome.RECORDED);
        first.commitSync(Map.of(partition, new OffsetAndMetadata(preparedRecord.offset() + 1)));
        ConsumerRecord<String, byte[]> targetRecord = next(first);
        assertThat(process(targetRecord)).isEqualTo(PurchaseOutcome.RECORDED);
        List<Map<String, Object>> afterFirst = activitiesOf(42L);
        first.close(Duration.ofSeconds(5));

        try (KafkaConsumer<String, byte[]> second = consumer()) {
            OffsetAndMetadata committed = second.committed(Set.of(partition)).get(partition);
            ConsumerRecord<String, byte[]> redelivered = next(second);
            PurchaseOutcome outcome = process(redelivered);

            System.out.printf("EXP-005 준비용=%d, target=%d, 닫힐 때 committed=%d, 다시 받은 offset=%d, 결과=%s%n",
                prepared.offset(), target.offset(), committed.offset(), redelivered.offset(), outcome);
            assertThat(committed.offset()).isEqualTo(target.offset());
            assertThat(redelivered.offset()).isEqualTo(target.offset());
            assertThat(outcome).isEqualTo(PurchaseOutcome.SAME_FACT);
        }
        assertThat(activitiesOf(42L)).isEqualTo(afterFirst).hasSize(2);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from order_confirmation_receipt where order_id = 42", Integer.class)).isEqualTo(1);
    }
}
