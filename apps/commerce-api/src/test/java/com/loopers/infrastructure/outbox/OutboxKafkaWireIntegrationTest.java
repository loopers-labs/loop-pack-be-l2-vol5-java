package com.loopers.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.outbox.OutboxDeliveryAttempt;
import com.loopers.domain.outbox.OutboxReceipt;
import com.loopers.domain.outbox.OutboxSender;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Real owned broker with production sender/configuration. No application or user DB is started. */
@Testcontainers
@Timeout(40)
class OutboxKafkaWireIntegrationTest {
    private static final String TOPIC = "w7-outbox-wire-" + UUID.randomUUID();
    private static final String PAYLOAD = """
        { "eventType":"OrderConfirmed", "schemaVersion":1,
          "eventId":"order-confirmed:v1:42", "orderId":42,
          "confirmedAt":"2026-11-04T01:02:03.123456Z",
          "items":[{"productId":7,"quantity":2},{"productId":8,"quantity":1}] }
        """;

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @BeforeAll
    static void prepareOwnedTopic() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000,
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 2, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void brokerContainsExactlyTheSavedJsonKeyAndTopicAndReturnsItsActualOffset() {
        new ApplicationContextRunner().withUserConfiguration(OutboxKafkaConfiguration.class)
            .withPropertyValues("spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "spring.kafka.producer.acks=all", "spring.kafka.producer.retries=3",
                "spring.kafka.producer.properties.enable.idempotence=true",
                "spring.kafka.producer.properties.max.block.ms=1000",
                "spring.kafka.producer.properties.request.timeout.ms=3000",
                "spring.kafka.producer.properties.delivery.timeout.ms=10000",
                "commerce.order-events.topic=unused-current-setting")
            .run(context -> {
                assertThat(context).hasNotFailed();
                try (KafkaConsumer<String, String> consumer = consumer()) {
                    List<TopicPartition> partitions = List.of(new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1));
                    consumer.assign(partitions);
                    consumer.seekToBeginning(partitions);
                    var attempt = new OutboxDeliveryAttempt(1, "order-confirmed:v1:42", TOPIC, "42", PAYLOAD, 1);

                    OutboxReceipt receipt = context.getBean(OutboxSender.class).send(attempt).get(12, TimeUnit.SECONDS);
                    List<ConsumerRecord<String, String>> records = new ArrayList<>();
                    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    while (records.isEmpty() && System.nanoTime() < deadline) {
                        consumer.poll(Duration.ofMillis(200)).forEach(records::add);
                    }

                    assertThat(records).as("actual records from owned broker before deadline").hasSize(1);
                    ConsumerRecord<String, String> record = records.getFirst();
                    assertThat(record.topic()).isEqualTo(TOPIC);
                    assertThat(record.key()).isEqualTo("42");
                    assertThat(record.value()).isEqualTo(PAYLOAD);
                    assertThat(record.headers().toArray()).isEmpty();
                    assertThat(receipt).isEqualTo(new OutboxReceipt(record.topic(), record.partition(), record.offset()));
                    var json = new ObjectMapper().readTree(record.value());
                    assertThat(json.isObject()).isTrue();
                    assertThat(json.size()).isEqualTo(6);
                    assertThat(json.get("items").size()).isEqualTo(2);
                    assertThat(json.get("orderId").isIntegralNumber()).isTrue();
                }
                // Context closes its producer factory, and Testcontainers removes only this test's broker.
            });
    }

    private KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "w7-outbox-wire-" + UUID.randomUUID(),
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000,
            ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000));
    }
}
