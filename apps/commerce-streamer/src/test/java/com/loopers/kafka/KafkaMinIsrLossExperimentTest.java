package com.loopers.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.testcontainers.KafkaReplicatedTestCluster;
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
 * acks=all이어도 ISR이 리더 하나로 줄면 그 리더만 가진 record를 잃을 수 있는지, min.insync.replicas가 그걸 막는지 본다.
 * <p>
 * 같은 장면을 min.insync.replicas 1과 2로 반복한다. topic은 replica 2개(broker 1=리더, 2=follower)이고 broker 3은 쓰지 않는다.
 * follower를 멈춰 ISR을 리더 하나로 줄인 뒤 20건을 쓰고, 리더를 멈추고 뒤처진 follower를 되살려 unclean 선출로 리더로 세운다.
 * 그 뒤 옛 리더를 되살려 확인받은 record가 돌아오는지 본다. 장애는 컨테이너 정지로 만든 통제된 조건이다.
 * 실행: {@code ./gradlew :apps:commerce-streamer:experimentTest --tests '*KafkaMinIsrLossExperimentTest'}
 */
@Tag("experiment")
class KafkaMinIsrLossExperimentTest {
    private static final int LEADER = 1;
    private static final int FOLLOWER = 2;
    private static final int WRITES_WHILE_ALONE = 20;

    @Test
    @Timeout(420)
    void acknowledgedRecordsAreLostWithMinIsrOneButRejectedUpFrontWithMinIsrTwo() throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        try (var cluster = new KafkaReplicatedTestCluster();
             var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers()))) {
            Map<String, Object> minIsrOne = run(cluster, admin, 1);
            assertThat(minIsrOne.get("acknowledgedWhileAlone")).isEqualTo(WRITES_WHILE_ALONE);
            assertThat(minIsrOne.get("rejectedWhileAlone")).isEqualTo(0);
            assertThat(minIsrOne.get("autoElectedWhileFollowerBehind")).isEqualTo(false);
            assertThat(minIsrOne.get("recordsAfterUncleanElection")).isEqualTo(List.of("before"));
            assertThat(minIsrOne.get("recordsAfterOldLeaderReturned")).isEqualTo(List.of("before", "after-recovery"));
            assertThat(minIsrOne.get("acknowledgedButLost")).isEqualTo(WRITES_WHILE_ALONE);
            results.add(minIsrOne);

            Map<String, Object> minIsrTwo = run(cluster, admin, 2);
            assertThat(minIsrTwo.get("acknowledgedWhileAlone")).isEqualTo(0);
            assertThat(minIsrTwo.get("rejectedWhileAlone")).isEqualTo(WRITES_WHILE_ALONE);
            assertThat(minIsrTwo.get("recordsAfterOldLeaderReturned")).isEqualTo(List.of("before", "after-recovery"));
            assertThat(minIsrTwo.get("acknowledgedButLost")).isEqualTo(0);
            results.add(minIsrTwo);
        }
        System.out.println("MIN_ISR_LOSS_EXPERIMENT " + new ObjectMapper().writeValueAsString(results));
    }

    private Map<String, Object> run(KafkaReplicatedTestCluster cluster, AdminClient admin, int minIsr) throws Exception {
        String topic = "min-isr-" + minIsr + "-" + UUID.randomUUID();
        TopicPartition partition = new TopicPartition(topic, 0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("minInsyncReplicas", minIsr);

        admin.createTopics(List.of(new NewTopic(topic, Map.of(0, List.of(LEADER, FOLLOWER)))
            .configs(Map.of("min.insync.replicas", Integer.toString(minIsr))))).all().get(15, TimeUnit.SECONDS);
        await(() -> isr(admin, topic).equals(List.of(LEADER, FOLLOWER)), "both replicas in sync");

        try (var producer = producer(cluster.bootstrapServers())) {
            assertThat(producer.send(new ProducerRecord<>(topic, 0, "order", "before")).get(15, TimeUnit.SECONDS)
                .offset()).isZero();

            // 1. follower를 멈춰 ISR을 리더 하나로 줄인다 (replica.lag.time.max.ms=5000).
            cluster.stopBroker(FOLLOWER);
            await(() -> isr(admin, topic).equals(List.of(LEADER)), "ISR shrinks to the leader");
            result.put("isrWhileFollowerDown", isr(admin, topic));

            // 2. 리더 혼자일 때 acks=all로 20건을 쓴다.
            int acknowledged = 0;
            int rejected = 0;
            for (int i = 1; i <= WRITES_WHILE_ALONE; i++) {
                try {
                    producer.send(new ProducerRecord<>(topic, 0, "order", "alone-" + i)).get(15, TimeUnit.SECONDS);
                    acknowledged++;
                } catch (ExecutionException failure) {
                    rejected++;
                    result.putIfAbsent("rejectionCause", failure.getCause().getClass().getSimpleName());
                }
            }
            result.put("acknowledgedWhileAlone", acknowledged);
            result.put("rejectedWhileAlone", rejected);
            result.put("leaderEndOffsetWhileAlone", endOffset(admin, partition));
        }

        // 3. 리더를 멈추고 뒤처진 follower를 되살린다. ISR 밖이라 저절로 리더가 되는지 본다.
        cluster.stopBroker(LEADER);
        cluster.startBroker(FOLLOWER);
        await(() -> brokerRegistered(admin, FOLLOWER), "lagging follower is back");
        Thread.sleep(10_000);
        result.put("autoElectedWhileFollowerBehind", leader(admin, topic) == FOLLOWER);

        // 4. Admin API로 unclean 선출을 요청해 뒤처진 follower를 리더로 세운다.
        admin.electLeaders(ElectionType.UNCLEAN, Set.of(partition)).partitions().get(15, TimeUnit.SECONDS);
        await(() -> leader(admin, topic) == FOLLOWER, "unclean leader elected");
        result.put("recordsAfterUncleanElection", read(cluster.bootstrapServers(), partition, endOffset(admin, partition)));

        // 5. 옛 리더를 되살린다. 옛 리더는 새 리더의 로그에 맞춰 자기 로그를 자르고 ISR에 다시 들어온다.
        //    (min.insync=2에서는 ISR이 2개가 돼야 쓸 수 있으므로, 쓰기는 그 뒤에 한다.)
        cluster.startBroker(LEADER);
        await(() -> isr(admin, topic).equals(List.of(LEADER, FOLLOWER)), "old leader rejoins ISR");
        try (var producer = producer(cluster.bootstrapServers())) {
            long offset = producer.send(new ProducerRecord<>(topic, 0, "order", "after-recovery"))
                .get(15, TimeUnit.SECONDS).offset();
            result.put("offsetOfFirstRecordAfterRecovery", offset);
        }
        List<String> finalRecords = read(cluster.bootstrapServers(), partition, endOffset(admin, partition));
        result.put("recordsAfterOldLeaderReturned", finalRecords);
        result.put("acknowledgedButLost", (int) result.get("acknowledgedWhileAlone")
            - (int) finalRecords.stream().filter(value -> value.startsWith("alone-")).count());

        // 다음 반복을 위해 원래 리더로 되돌린다.
        admin.electLeaders(ElectionType.PREFERRED, Set.of(partition)).partitions().get(15, TimeUnit.SECONDS);
        await(() -> leader(admin, topic) == LEADER, "preferred leader restored");
        return result;
    }

    private KafkaProducer<String, String> producer(String bootstrap) {
        return new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.ACKS_CONFIG, "all", ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
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
