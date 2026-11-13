package com.loopers.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
class KafkaTestFixtureTest {

    @Container
    private static final KafkaContainer KAFKA = KafkaTestFixture.create();

    @Test
    @Timeout(60)
    @DisplayName("격리 broker의 같은 주소로 topic 생성과 실제 발행·소비를 수행한다")
    void publishesAndConsumesUsingTheFixtureAddress() throws Exception {
        // arrange
        String bootstrapServers = KAFKA.getBootstrapServers();
        String topic = "fixture-smoke-" + UUID.randomUUID();
        String group = "fixture-smoke-" + UUID.randomUUID();
        byte[] payload = "fixture-connectivity".getBytes(StandardCharsets.UTF_8);

        try (
            AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000
            ));
            KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000L,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000
            ));
            KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000,
                ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000,
                ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 100
            ))
        ) {
            admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1))).all().get(10, TimeUnit.SECONDS);

            // act
            var metadata = producer.send(new ProducerRecord<>(topic, "fixture-key", payload))
                .get(10, TimeUnit.SECONDS);
            consumer.subscribe(List.of(topic));
            ConsumerRecord<String, byte[]> received = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (received == null && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    if (record.partition() == metadata.partition() && record.offset() == metadata.offset()) {
                        received = record;
                        break;
                    }
                }
            }

            // assert
            assertThat(received).as("broker=%s, topic=%s, partition=%s, offset=%s, assignment=%s",
                bootstrapServers, topic, metadata.partition(), metadata.offset(), consumer.assignment()).isNotNull();
            assertThat(received.topic()).isEqualTo(topic);
            assertThat(received.key()).isEqualTo("fixture-key");
            assertThat(received.value()).isEqualTo(payload);
        }
    }
}
