package com.loopers;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// 공통 kafka.yml이 Kafka client에 실제로 전달하는 값을 확인한다. Spring 속성 이름이 아니라 Kafka가 읽는 설정으로 검사한다.
@SpringBootTest
class KafkaClientConfigTest {
    private static final Duration POLL_DEADLINE = Duration.ofSeconds(10);

    @Autowired
    private ConsumerFactory<Object, Object> consumerFactory;

    @Autowired
    private ProducerFactory<Object, Object> producerFactory;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @DisplayName("consumer는 값을 byte 배열로 받고, Kafka가 읽는 자동 commit 설정이 꺼져 있다.")
    @Test
    void consumerReadsBytesWithoutAutoCommit() {
        ConsumerConfig config = new ConsumerConfig(consumerFactory.getConfigurationProperties());

        assertThat(config.getClass(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)).isEqualTo(ByteArrayDeserializer.class);
        assertThat(config.getBoolean(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isFalse();
        assertThat(config.originals()).doesNotContainKey("enable-auto-commit");
    }

    // 기본값에 기대지 않고 명시해야 충돌하는 producer 설정이 멱등성을 조용히 끄지 못하고 기동 시 실패한다.
    @DisplayName("producer는 ISR 전체의 기록 확인과 멱등 전송을 명시적으로 켠다.")
    @Test
    void producerExplicitlyWaitsForAllReplicasAndIsIdempotent() {
        ProducerConfig config = new ProducerConfig(producerFactory.getConfigurationProperties());

        assertThat(config.originals()).containsKeys(ProducerConfig.ACKS_CONFIG, ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG);
        assertThat(config.getString(ProducerConfig.ACKS_CONFIG)).isEqualTo("-1");
        assertThat(config.getBoolean(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isTrue();
    }

    @DisplayName("producer, consumer, admin이 같은 테스트 broker 주소를 쓴다.")
    @Test
    void clientsShareTestBroker() {
        List<String> producer = new ProducerConfig(producerFactory.getConfigurationProperties())
            .getList(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG);
        List<String> consumer = new ConsumerConfig(consumerFactory.getConfigurationProperties())
            .getList(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG);
        List<String> admin = new AdminClientConfig(kafkaAdmin.getConfigurationProperties())
            .getList(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG);

        assertThat(consumer).isEqualTo(producer);
        assertThat(admin).isEqualTo(producer);
    }

    @DisplayName("admin이 만든 topic에 producer가 보낸 record를 consumer가 같은 위치에서 byte 배열로 읽는다.")
    @Test
    void readsRecordSentThroughFixtureBroker() throws Exception {
        String topic = "kafka-client-config-" + UUID.randomUUID();
        kafkaAdmin.createOrModifyTopics(TopicBuilder.name(topic).partitions(1).replicas(1).build());

        SendResult<Object, Object> sent = kafkaTemplate.send(topic, "key-1", "hello").get(10, TimeUnit.SECONDS);
        TopicPartition partition = new TopicPartition(topic, sent.getRecordMetadata().partition());

        try (Consumer<Object, Object> consumer = consumerFactory.createConsumer("kafka-client-config-" + UUID.randomUUID(), null)) {
            consumer.assign(List.of(partition));
            consumer.seek(partition, sent.getRecordMetadata().offset());
            List<ConsumerRecord<Object, Object>> records = pollUntilAny(consumer);

            assertThat(records).hasSize(1);
            ConsumerRecord<Object, Object> record = records.getFirst();
            assertThat(record.offset()).isEqualTo(sent.getRecordMetadata().offset());
            assertThat(record.key()).isEqualTo("key-1");
            assertThat(record.value()).isInstanceOf(byte[].class);
            assertThat(new String((byte[]) record.value(), StandardCharsets.UTF_8)).isEqualTo("\"hello\"");
        }
    }

    private static List<ConsumerRecord<Object, Object>> pollUntilAny(Consumer<Object, Object> consumer) {
        Instant deadline = Instant.now().plus(POLL_DEADLINE);
        List<ConsumerRecord<Object, Object>> records = new ArrayList<>();
        while (records.isEmpty() && Instant.now().isBefore(deadline)) {
            consumer.poll(Duration.ofMillis(200)).forEach(records::add);
        }
        return records;
    }
}
