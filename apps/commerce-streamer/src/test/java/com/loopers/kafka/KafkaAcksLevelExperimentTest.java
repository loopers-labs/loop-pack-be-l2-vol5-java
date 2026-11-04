package com.loopers.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.testcontainers.KafkaReplicatedTestCluster;
import com.loopers.testcontainers.KafkaTestBroker;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.ElectionType;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * acks 0·1이 무엇을 확인하지 않는지 실제 broker에서 본다. 장애는 컨테이너 정지로 만든 통제된 조건이다.
 * <p>
 * 실행: {@code ./gradlew :apps:commerce-streamer:experimentTest --tests '*KafkaAcksLevelExperimentTest'}
 */
@Tag("experiment")
class KafkaAcksLevelExperimentTest {
    private static final int LEADER = 1;
    private static final int FOLLOWER = 2;
    private static final int WRITES = 20;

    @Test
    @Timeout(60)
    void acksZeroReportsSuccessEvenWhenTheBrokerRejectsTheRecord() throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        try (var broker = new KafkaTestBroker();
             var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers()))) {
            for (String acks : List.of("0", "1")) {
                String topic = "acks-" + acks + "-" + UUID.randomUUID();
                admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1)
                    .configs(Map.of("max.message.bytes", "100")))).all().get(15, TimeUnit.SECONDS);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("acks", acks);
                try (var producer = producer(broker.bootstrapServers(), acks)) {
                    try {
                        RecordMetadata metadata = producer.send(new ProducerRecord<>(topic, 0, "order", "x".repeat(1_000)))
                            .get(10, TimeUnit.SECONDS);
                        result.put("reportedSuccess", true);
                        result.put("reportedOffset", metadata.offset());
                    } catch (ExecutionException failure) {
                        result.put("reportedSuccess", false);
                        result.put("failure", failure.getCause().getClass().getSimpleName());
                    }
                }
                result.put("storedRecords", endOffset(admin, new TopicPartition(topic, 0)));
                results.add(result);
            }
        }
        System.out.println("ACKS_ZERO_EXPERIMENT " + new ObjectMapper().writeValueAsString(results));

        assertThat(results.get(0).get("reportedSuccess")).isEqualTo(true);
        assertThat(results.get(0).get("storedRecords")).isEqualTo(0L);
        assertThat(results.get(1).get("reportedSuccess")).isEqualTo(false);
        assertThat(results.get(1).get("storedRecords")).isEqualTo(0L);
    }

    @Test
    @Timeout(300)
    void acksOneIgnoresMinInsyncReplicasAndLosesAcknowledgedRecords() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        try (var cluster = new KafkaReplicatedTestCluster();
             var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers()))) {
            String topic = "acks-one-" + UUID.randomUUID();
            TopicPartition partition = new TopicPartition(topic, 0);
            admin.createTopics(List.of(new NewTopic(topic, Map.of(0, List.of(LEADER, FOLLOWER)))
                .configs(Map.of("min.insync.replicas", "2")))).all().get(15, TimeUnit.SECONDS);
            await(() -> isr(admin, topic).equals(List.of(LEADER, FOLLOWER)), "both replicas in sync");

            try (var one = producer(cluster.bootstrapServers(), "1");
                 var all = producer(cluster.bootstrapServers(), "all")) {
                one.send(new ProducerRecord<>(topic, 0, "order", "before")).get(15, TimeUnit.SECONDS);
                all.partitionsFor(topic);

                cluster.stopBroker(FOLLOWER);
                await(() -> isr(admin, topic).equals(List.of(LEADER)), "ISR shrinks to the leader");

                int acknowledged = 0;
                for (int i = 1; i <= WRITES; i++) {
                    one.send(new ProducerRecord<>(topic, 0, "order", "alone-" + i)).get(15, TimeUnit.SECONDS);
                    acknowledged++;
                }
                result.put("minInsyncReplicas", 2);
                result.put("acksOneAcknowledged", acknowledged);
                try {
                    all.send(new ProducerRecord<>(topic, 0, "order", "all-alone")).get(15, TimeUnit.SECONDS);
                    result.put("acksAllAcknowledged", true);
                } catch (ExecutionException failure) {
                    result.put("acksAllAcknowledged", false);
                    result.put("acksAllFailure", failure.getCause().getClass().getSimpleName());
                }
                result.put("leaderEndOffset", endOffset(admin, partition));
            }

            cluster.stopBroker(LEADER);
            cluster.startBroker(FOLLOWER);
            await(() -> brokerRegistered(admin, FOLLOWER), "lagging follower is back");
            admin.electLeaders(ElectionType.UNCLEAN, Set.of(partition)).partitions().get(15, TimeUnit.SECONDS);
            await(() -> leader(admin, topic) == FOLLOWER, "unclean leader elected");
            List<String> remaining = read(cluster.bootstrapServers(), partition, endOffset(admin, partition));
            result.put("recordsAfterUncleanElection", remaining);
            result.put("acknowledgedButLost", WRITES - remaining.stream().filter(value -> value.startsWith("alone-")).count());
        }
        System.out.println("ACKS_ONE_EXPERIMENT " + new ObjectMapper().writeValueAsString(result));

        assertThat(result.get("acksOneAcknowledged")).isEqualTo(WRITES);
        assertThat(result.get("acksAllAcknowledged")).isEqualTo(false);
        assertThat(result.get("acknowledgedButLost")).isEqualTo((long) WRITES);
    }

    private KafkaProducer<String, String> producer(String bootstrap, String acks) {
        return new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.ACKS_CONFIG, acks,
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "all".equals(acks),
            ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2_000,
            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5_000,
            ProducerConfig.LINGER_MS_CONFIG, 0,
            ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000));
    }

    private TopicPartitionInfo info(AdminClient admin, String topic) throws Exception {
        TopicDescription description = admin.describeTopics(List.of(topic)).allTopicNames()
            .get(10, TimeUnit.SECONDS).get(topic);
        return description.partitions().getFirst();
    }

    private List<Integer> isr(AdminClient admin, String topic) throws Exception {
        return info(admin, topic).isr().stream().map(node -> node.id()).sorted().toList();
    }

    private int leader(AdminClient admin, String topic) throws Exception {
        var leader = info(admin, topic).leader();
        return leader == null ? -1 : leader.id();
    }

    private boolean brokerRegistered(AdminClient admin, int id) throws Exception {
        return admin.describeCluster().nodes().get(10, TimeUnit.SECONDS).stream().anyMatch(node -> node.id() == id);
    }

    private long endOffset(AdminClient admin, TopicPartition partition) throws Exception {
        return admin.listOffsets(Map.of(partition, OffsetSpec.latest())).all().get(10, TimeUnit.SECONDS)
            .get(partition).offset();
    }

    private List<String> read(String bootstrap, TopicPartition partition, long endOffset) throws Exception {
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            List<String> values = new ArrayList<>();
            await(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> values.add(record.value()));
                return consumer.position(partition) >= endOffset;
            }, "read up to end offset " + endOffset);
            return values;
        }
    }

    private void await(Callable<Boolean> condition, String what) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (condition.call()) {
                    return;
                }
            } catch (ExecutionException | java.util.concurrent.TimeoutException transientMetadata) {
                // 브로커가 바뀌는 동안 metadata 조회가 잠깐 실패할 수 있다.
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("timed out waiting: " + what);
    }
}
