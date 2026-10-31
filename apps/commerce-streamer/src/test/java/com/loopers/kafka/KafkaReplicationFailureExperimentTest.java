package com.loopers.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.testcontainers.KafkaReplicatedTestCluster;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaReplicationFailureExperimentTest {
    @Test
    @Timeout(240)
    void acksAllSurvivesOneBrokerLossRejectsIsrOneAndRecoversWithoutLosingRecords() throws Exception {
        String topic = "replication-failure-" + UUID.randomUUID();
        String group = "replicated-offsets-" + UUID.randomUUID();
        TopicPartition partition = new TopicPartition(topic, 0);
        List<Map<String, Object>> phases = new ArrayList<>();
        try (var cluster = new KafkaReplicatedTestCluster();
             var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                 cluster.bootstrapServers()));
             var producer = producer(cluster.bootstrapServers())) {
            assertBrokerSettings(admin);
            admin.createTopics(List.of(new NewTopic(topic, Map.of(
                0, List.of(1, 2, 3), 1, List.of(2, 3, 1), 2, List.of(3, 1, 2)))
                .configs(Map.of("min.insync.replicas", "2")))).all().get(15, TimeUnit.SECONDS);
            await(() -> allReplicated(admin, topic));
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
            assertThat(admin.describeConfigs(List.of(resource)).all().get(10, TimeUnit.SECONDS)
                .get(resource).get("min.insync.replicas").value()).isEqualTo("2");
            var initial = info(admin, topic);
            int originalLeader = initial.leader().id();
            phases.add(snapshot("healthy", initial));
            send(producer, topic, "before-failure", 0);
            commitFirstRecord(cluster.bootstrapServers(), topic, group);
            await(() -> admin.describeTopics(List.of("__consumer_offsets")).allTopicNames()
                .get(10, TimeUnit.SECONDS).get("__consumer_offsets").partitions().stream()
                .allMatch(p -> p.replicas().size() == 3 && p.isr().size() == 3));

            int follower = initial.isr().stream().mapToInt(node -> node.id())
                .filter(id -> id != originalLeader).findFirst().orElseThrow();
            cluster.stopBroker(follower);
            await(() -> info(admin, topic).isr().size() == 2);
            phases.add(snapshot("one-follower-down", info(admin, topic)));
            send(producer, topic, "one-follower-down", 1);
            cluster.startBroker(follower);
            awaitReplication(cluster, admin, topic, "follower restart");

            cluster.stopBroker(originalLeader);
            await(() -> info(admin, topic).isr().size() == 2
                && info(admin, topic).leader().id() != originalLeader);
            var failover = info(admin, topic);
            assertThat(failover.leader().id()).isNotEqualTo(originalLeader);
            phases.add(snapshot("leader-failed-over", failover));
            send(producer, topic, "leader-failed-over", 2);

            int secondStopped = failover.isr().stream().mapToInt(node -> node.id())
                .filter(id -> id != failover.leader().id()).findFirst().orElseThrow();
            cluster.stopBroker(secondStopped);
            await(() -> info(admin, topic).isr().size() == 1);
            assertThat(cluster.controllerIsRunning()).isTrue();
            phases.add(snapshot("two-data-brokers-down-controller-alive", info(admin, topic)));
            long beforeRejectedSend = endOffset(admin, partition);
            assertThatThrownBy(() -> producer.send(new ProducerRecord<>(topic, 0, "order", "must-be-rejected"))
                .get(15, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(NotEnoughReplicasException.class);
            assertThat(endOffset(admin, partition)).isEqualTo(beforeRejectedSend);

            cluster.startBroker(originalLeader);
            await(() -> info(admin, topic).isr().size() == 2);
            phases.add(snapshot("restored-isr-two", info(admin, topic)));
            // 소진된 producer의 sequence 복구 정책과 broker 복구를 분리한다. 새 client도 3개 주소를 쓴다.
            try (var recoveredProducer = producer(cluster.bootstrapServers())) {
                send(recoveredProducer, topic, "after-recovery", 3);
            }
            cluster.startBroker(secondStopped);
            awaitReplication(cluster, admin, topic, "full recovery");
            phases.add(snapshot("fully-restored", info(admin, topic)));

            List<String> values = readPartition(cluster.bootstrapServers(), partition);
            assertThat(values).containsExactly("before-failure", "one-follower-down", "leader-failed-over",
                "after-recovery");
            assertThat(endOffset(admin, partition)).isEqualTo(4);
            assertThat(admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS).get(partition).offset()).isEqualTo(1);
            System.out.println("REPLICATION_FAILURE_EXPERIMENT " + new ObjectMapper().writeValueAsString(
                Map.of("dataBrokers", 3, "separateControllers", 1, "partitions", 3,
                    "replicationFactor", 3, "minIsr", 2, "acks", "all", "phases", phases,
                    "acceptedRecords", values, "rejectedRecordAppended", false,
                    "offsetTopicReplicationAndRetainedCommit", "RF3, committed=1")));
        }
    }

    private KafkaProducer<String, String> producer(String bootstrap) {
        return new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.ACKS_CONFIG, "all", ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
            ProducerConfig.RETRIES_CONFIG, 3, ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 100,
            ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2_000,
            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000,
            ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000));
    }

    private void assertBrokerSettings(AdminClient admin) throws Exception {
        List<ConfigResource> resources = List.of(1, 2, 3).stream()
            .map(id -> new ConfigResource(ConfigResource.Type.BROKER, id.toString())).toList();
        admin.describeConfigs(resources).all().get(10, TimeUnit.SECONDS).values().forEach(config -> {
            assertThat(config.get("auto.create.topics.enable").value()).isEqualTo("false");
            assertThat(config.get("offsets.topic.replication.factor").value()).isEqualTo("3");
        });
    }

    private void commitFirstRecord(String bootstrap, String topic, String group) throws Exception {
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap, ConsumerConfig.GROUP_ID_CONFIG, group,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"))) {
            consumer.subscribe(List.of(topic));
            List<String> values = new ArrayList<>();
            await(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> values.add(record.value()));
                return !values.isEmpty();
            });
            assertThat(values).containsExactly("before-failure");
            consumer.commitSync();
        }
    }

    private void send(KafkaProducer<String, String> producer, String topic, String value, long offset)
        throws Exception {
        assertThat(producer.send(new ProducerRecord<>(topic, 0, "order", value))
            .get(15, TimeUnit.SECONDS).offset()).isEqualTo(offset);
    }

    private TopicPartitionInfo info(AdminClient admin, String topic) throws Exception {
        return admin.describeTopics(List.of(topic)).allTopicNames().get(10, TimeUnit.SECONDS)
            .get(topic).partitions().getFirst();
    }

    private boolean allReplicated(AdminClient admin, String topic) throws Exception {
        var partitions = admin.describeTopics(List.of(topic)).allTopicNames().get(10, TimeUnit.SECONDS)
            .get(topic).partitions();
        return partitions.size() == 3 && partitions.stream()
            .allMatch(partition -> partition.replicas().size() == 3 && partition.isr().size() == 3);
    }

    private void awaitReplication(KafkaReplicatedTestCluster cluster, AdminClient admin, String topic,
                                  String phase) throws Exception {
        try {
            await(() -> allReplicated(admin, topic));
        } catch (IllegalStateException failure) {
            throw new IllegalStateException(phase + ": " + cluster.diagnostics(), failure);
        }
    }

    private Map<String, Object> snapshot(String phase, TopicPartitionInfo info) {
        return Map.of("phase", phase, "leader", info.leader().id(),
            "isr", info.isr().stream().map(node -> node.id()).sorted().toList());
    }

    private long endOffset(AdminClient admin, TopicPartition partition) throws Exception {
        return admin.listOffsets(Map.of(partition, OffsetSpec.latest())).all().get(10, TimeUnit.SECONDS)
            .get(partition).offset();
    }

    private List<String> readPartition(String bootstrap, TopicPartition partition) throws Exception {
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
            ConsumerConfig.GROUP_ID_CONFIG, "replication-inspection-" + UUID.randomUUID(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"))) {
            consumer.assign(List.of(partition));
            List<String> result = new ArrayList<>();
            await(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> result.add(record.value()));
                return result.size() >= 4;
            });
            return result;
        }
    }

    private void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.call()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("expected replicated Kafka state was not observed");
    }
}
