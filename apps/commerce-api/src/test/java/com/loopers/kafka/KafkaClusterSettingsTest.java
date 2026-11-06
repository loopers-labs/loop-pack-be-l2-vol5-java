package com.loopers.kafka;

import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.config.OrderEventsTopicConfig;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W6 D-5·ADR-W6-04 — 앱이 실제로 쓰는 producer 설정과 topic이 설계대로인지 확인한다.
 * 설정 파일의 표기가 아니라 Kafka 클라이언트가 해석한 값(ProducerConfig)과 broker에 만들어진 topic을 본다.
 */
@SpringBootTest
class KafkaClusterSettingsTest {

    @Autowired
    @Qualifier(KafkaConfig.STRING_TEMPLATE)
    private KafkaTemplate<String, String> stringKafkaTemplate;

    @Value("${kafka.topics.order-events}")
    private String orderEventsTopic;

    @DisplayName("producer는 acks=all · idempotence · max.block.ms 1s로 해석된다")
    @Test
    void producerConfigAsKafkaReadsIt() {
        ProducerConfig config = new ProducerConfig(stringKafkaTemplate.getProducerFactory().getConfigurationProperties());

        assertThat(config.getString(ProducerConfig.ACKS_CONFIG)).isEqualTo("-1"); // all
        assertThat(config.getBoolean(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isTrue();
        assertThat(config.getLong(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isEqualTo(1_000L);
        assertThat(config.getList(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)).hasSize(3);
    }

    @DisplayName("order-events는 partition 3 · 모든 partition의 replica 3 · min.insync.replicas 2로 만들어진다")
    @Test
    void orderEventsTopicIsReplicated() throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers()))) {
            TopicDescription description = admin.describeTopics(List.of(orderEventsTopic)).allTopicNames().get(10, TimeUnit.SECONDS)
                .get(orderEventsTopic);
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, orderEventsTopic);
            Config config = admin.describeConfigs(List.of(resource)).all().get(10, TimeUnit.SECONDS).get(resource);

            assertThat(description.partitions()).hasSize(OrderEventsTopicConfig.PARTITIONS);
            assertThat(description.partitions()).allSatisfy(partition -> {
                assertThat(partition.replicas()).hasSize(3);
                assertThat(partition.isr()).hasSize(3);
            });
            assertThat(config.get(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG).value()).isEqualTo("2");
        }
    }

    @DisplayName("문자열 template은 JSON 원문을 따옴표로 다시 감싸지 않고 그대로 보낸다 (업무 topic이 아닌 probe topic)")
    @Test
    void stringTemplateSendsRawJson() throws Exception {
        String probeTopic = "w6-probe-" + UUID.randomUUID();
        String json = "{\"probe\":1}";
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(probeTopic, 1, (short) 3))).all().get(10, TimeUnit.SECONDS);
        }
        // 방금 만든 topic은 metadata가 broker 3대에 퍼지기 전이라, 앱 producer(max.block.ms 1s)의 첫 send가
        // UNKNOWN_TOPIC_OR_PARTITION → "not present in metadata after 1000 ms"로 막힐 수 있다 (전체 실행에서 간헐 실패, 11-06).
        // ISR은 topic을 만들 때부터 3이라 admin의 describe로는 퍼졌는지 알 수 없다 — 앱 producer 자신이 metadata를 받을 때까지 기다린다.
        // 앱 설정(max.block.ms)은 이 테스트가 확인하는 값이라 바꾸지 않는다.
        awaitProducerMetadata(probeTopic);

        SendResult<String, String> sent = stringKafkaTemplate.send(new ProducerRecord<>(probeTopic, "probe", json)).get(10, TimeUnit.SECONDS);

        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            TopicPartition partition = new TopicPartition(probeTopic, sent.getRecordMetadata().partition());
            consumer.assign(List.of(partition));
            consumer.seek(partition, sent.getRecordMetadata().offset());
            ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofSeconds(10));

            assertThat(records.count()).isEqualTo(1);
            assertThat(new String(records.iterator().next().value(), StandardCharsets.UTF_8)).isEqualTo(json);
        }
    }

    private void awaitProducerMetadata(String topic) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (!stringKafkaTemplate.partitionsFor(topic).isEmpty()) {
                    return;
                }
            } catch (KafkaException notYet) {
                // 아직 이 producer의 metadata에 topic이 없다 (max.block.ms 1s마다 다시 묻는다)
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException(topic + "의 metadata를 producer가 받지 못했습니다");
    }
}
