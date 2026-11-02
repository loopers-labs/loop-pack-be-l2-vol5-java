package com.loopers.kafka;

import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.testcontainers.KafkaCluster;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E-5 (W6 plan.md D-8, ADR-W6-04) — broker 3대 · RF 3 · min.insync.replicas 2 · acks=all에서
 * 1대 장애는 쓰기가 성공하고 유실이 없으며, 2대 장애는 쓰기가 성공하지 않는다.
 * <p>
 * broker를 멈추므로 공유 클러스터가 아니라 이 테스트만의 클러스터를 따로 띄운다. producer 설정은 앱의 문자열 template 설정을 그대로 쓰고,
 * 실패가 빨리 드러나도록 시간 상한(delivery·request timeout)만 줄인다 — 기본 delivery.timeout.ms 120s를 기다리지 않기 위해서다.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BrokerFailureReplicationTest {

    private static final String TOPIC = "w6-replication";
    private static final String STRICT_TOPIC = "w6-replication-min-isr-3";

    @Autowired
    @Qualifier(KafkaConfig.STRING_TEMPLATE)
    private KafkaTemplate<String, String> appTemplate;

    private KafkaCluster cluster;
    private KafkaProducer<String, String> producer;
    private final List<RecordMetadata> acknowledged = new ArrayList<>();

    @BeforeAll
    void startOwnCluster() throws Exception {
        cluster = KafkaCluster.start();
        try (Admin admin = admin()) {
            admin.createTopics(List.of(
                new NewTopic(TOPIC, 1, (short) 3).configs(Map.of("min.insync.replicas", "2")),
                new NewTopic(STRICT_TOPIC, 1, (short) 3).configs(Map.of("min.insync.replicas", "3"))
            )).all().get(30, TimeUnit.SECONDS);
        }
        Map<String, Object> props = new HashMap<>(appTemplate.getProducerFactory().getConfigurationProperties());
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers());
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15_000);
        // 방금 만든 topic은 metadata가 퍼지기 전이라 앱 설정(max.block.ms 1s)으로는 첫 send가 막힐 수 있다.
        // 이 테스트는 복제 보장을 보므로 metadata 대기만 늘린다 (앱의 topic은 기동 때 만들어져 있다).
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 15_000);
        producer = new KafkaProducer<>(props);
        awaitLeader(TOPIC);
        awaitLeader(STRICT_TOPIC);
    }

    @AfterAll
    void stopOwnCluster() {
        if (producer != null) {
            producer.close(Duration.ofSeconds(1));
        }
        if (cluster != null) {
            cluster.close();
        }
    }

    @DisplayName("3대 정상 — acks=all 쓰기가 성공한다")
    @Test
    @Order(1)
    void allBrokersUp() throws Exception {
        acknowledged.add(send(TOPIC, "before-failure"));

        assertThat(acknowledged).hasSize(1);
    }

    @DisplayName("leader broker 1대 정지 — 새 leader로 넘어가 쓰기가 성공하고, 정지 전에 성공한 record도 남아 있다")
    @Test
    @Order(2)
    void oneBrokerDown() throws Exception {
        int leader = leaderOf(TOPIC);
        cluster.stopBroker(leader);

        acknowledged.add(send(TOPIC, "after-one-down"));

        assertThat(leaderOf(TOPIC)).isNotEqualTo(leader);
        assertThat(readAll(TOPIC)).containsExactly("before-failure", "after-one-down");
    }

    @DisplayName("1대 정지 + min.insync.replicas 3 — ISR 2 < 3이라 broker가 NotEnoughReplicas로 거절한다 (min.insync의 동작)")
    @Test
    @Order(3)
    void strictTopicRejectsWhenIsrBelowMinimum() {
        assertThatThrownBy(() -> send(STRICT_TOPIC, "strict"))
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(NotEnoughReplicasException.class);
    }

    @DisplayName("2대 정지 — ISR 1 < min.insync 2라 broker가 NotEnoughReplicas로 거절한다. 유실 대신 멈춘다")
    @Test
    @Order(4)
    void twoBrokersDown() throws Exception {
        int second = leaderOf(TOPIC);
        cluster.stopBroker(second);

        // 정상 종료(controlled shutdown)는 떠나기 전에 ISR에서 빠지므로, 남은 leader의 ISR은 자기 하나다.
        // 이때 controller quorum(2/3)도 잃어 metadata를 바꿀 수 없지만, 쓰기를 거절하는 판단은 남은 leader가 한다.
        assertThatThrownBy(() -> send(TOPIC, "after-two-down"))
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(NotEnoughReplicasException.class);
    }

    private RecordMetadata send(String topic, String value) throws Exception {
        return producer.send(new ProducerRecord<>(topic, "key", value)).get(30, TimeUnit.SECONDS);
    }

    private void awaitLeader(String topic) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try (Admin admin = admin()) {
                TopicDescription description = admin.describeTopics(List.of(topic)).allTopicNames().get(10, TimeUnit.SECONDS).get(topic);
                if (description.partitions().get(0).leader() != null && description.partitions().get(0).isr().size() == 3) {
                    return;
                }
            } catch (ExecutionException ignored) {
                // 아직 metadata가 없다
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException(topic + "의 leader가 준비되지 않았습니다");
    }

    private int leaderOf(String topic) throws Exception {
        try (Admin admin = admin()) {
            TopicDescription description = admin.describeTopics(List.of(topic)).allTopicNames().get(30, TimeUnit.SECONDS).get(topic);
            return description.partitions().get(0).leader().id();
        }
    }

    private List<String> readAll(String topic) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            TopicPartition partition = new TopicPartition(topic, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            List<String> values = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 15_000;
            while (values.size() < acknowledged.size() && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    values.add(record.value());
                }
            }
            return values;
        }
    }

    private Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers()));
    }
}
