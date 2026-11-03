package com.loopers.testcontainers;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 과제 5절 partition·consumer group 실험 (📄 p.5~6). 실제 broker(fixture)에 partition 2개인 격리 topic 을 만들고
 * 같은 group 의 consumer 를 1→2→3개로 늘리며 안정된 배정을 관찰한다. 실험 record 는 업무 topic 과 분리돼 가짜 구매 활동을 만들지 않는다.
 * 여러 consumer 를 한 스레드에서 번갈아 poll 해 rebalance 를 끝낸다. 배정 전에 잠만 자거나 객체 수만 세지 않는다.
 * acks=all 로 보내지만 단일 broker(복제 1)라 복제 장애 보장은 이 실험으로 확인하지 못한다.
 */
class PartitionAssignmentTest {

    private static final String TOPIC = "partition-test-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String GROUP = "partition-group-" + UUID.randomUUID().toString().substring(0, 8);
    private static final TopicPartition P0 = new TopicPartition(TOPIC, 0);
    private static final TopicPartition P1 = new TopicPartition(TOPIC, 1);
    private static final Duration DEADLINE = Duration.ofSeconds(30);

    private final List<KafkaConsumer<String, String>> consumers = new ArrayList<>();

    @BeforeAll
    static void createTopic() throws Exception {
        KafkaTestContainersConfig.createTopic(TOPIC, 2);
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers()))) {
            int partitions = admin.describeTopics(List.of(TOPIC)).allTopicNames().get(10, TimeUnit.SECONDS).get(TOPIC).partitions().size();
            assertThat(partitions).isEqualTo(2);
        }
    }

    @AfterEach
    void closeConsumers() {
        consumers.forEach(consumer -> consumer.close(Duration.ofSeconds(5)));
        consumers.clear();
    }

    @DisplayName("같은 group 의 consumer 를 1→2→3 으로 늘리면 각 partition 은 한 member 에게만 배정되고, 3개일 때 하나는 배정 없이 남는다. 두 partition 에 보낸 record 는 담당 consumer 가 읽는다.")
    @Test
    void assignsEachPartitionToOneMember_asConsumersJoin() throws Exception {
        Map<String, String> assignmentTable = new LinkedHashMap<>();

        // 1개 - 혼자서 두 partition 을 모두 맡는다
        KafkaConsumer<String, String> c1 = join("c1");
        pollAllUntil(() -> c1.assignment().equals(Set.of(P0, P1)));
        assignmentTable.put("consumer 1개", describeAssignments());

        // 2개 - 하나씩 나눠 갖는다
        KafkaConsumer<String, String> c2 = join("c2");
        pollAllUntil(() -> c1.assignment().size() == 1 && c2.assignment().size() == 1 && union().equals(Set.of(P0, P1)));
        assignmentTable.put("consumer 2개", describeAssignments());

        // 3개 - partition 이 2개뿐이라 적어도 하나는 배정 없이 남는다
        KafkaConsumer<String, String> c3 = join("c3");
        pollAllUntil(() -> union().equals(Set.of(P0, P1)) && eachPartitionOwnedByExactlyOne() && consumers.stream().anyMatch(c -> c.assignment().isEmpty()));
        assignmentTable.put("consumer 3개", describeAssignments());
        assignmentTable.forEach((stage, table) -> System.out.println("[배정표] " + stage + " → " + table));

        // 두 partition 에 명시적으로 record 를 보내고, 각 partition 의 담당 consumer 가 읽는지 본다
        RecordMetadata toP0 = send(new ProducerRecord<>(TOPIC, 0, "p0-key", "to-partition-0"));
        RecordMetadata toP1 = send(new ProducerRecord<>(TOPIC, 1, "p1-key", "to-partition-1"));
        assertThat(toP0.partition()).isZero();
        assertThat(toP1.partition()).isEqualTo(1);
        Map<TopicPartition, String> readerOf = new LinkedHashMap<>();
        pollAllUntil(records -> {
            for (ConsumerRecord<String, String> record : records.keySet()) {
                readerOf.put(new TopicPartition(record.topic(), record.partition()), records.get(record));
            }
            return readerOf.containsKey(P0) && readerOf.containsKey(P1);
        });
        assertThat(readerOf.get(P0)).isEqualTo(ownerOf(P0));
        assertThat(readerOf.get(P1)).isEqualTo(ownerOf(P1));
        assertThat(readerOf.get(P0)).isNotEqualTo(readerOf.get(P1));
    }

    @DisplayName("partition 수가 고정이면 producer 가 고른 partition 은 같은 key 마다 같다 (다른 key 가 다른 partition 으로 간다고는 단언하지 않는다).")
    @Test
    void sameKeyGoesToSamePartition() throws Exception {
        Set<Integer> partitionsOfKey42 = new TreeSet<>();
        for (int i = 0; i < 10; i++) {
            partitionsOfKey42.add(send(new ProducerRecord<>(TOPIC, "42", "attempt-" + i)).partition());
        }
        Set<Integer> partitionsOfKey43 = new TreeSet<>();
        for (int i = 0; i < 10; i++) {
            partitionsOfKey43.add(send(new ProducerRecord<>(TOPIC, "43", "attempt-" + i)).partition());
        }

        assertThat(partitionsOfKey42).hasSize(1);
        assertThat(partitionsOfKey43).hasSize(1);
        System.out.println("[key 매핑] key=42 → partition " + partitionsOfKey42 + ", key=43 → partition " + partitionsOfKey43);
    }

    private KafkaConsumer<String, String> join(String clientId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP);
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(TOPIC));
        consumers.add(consumer);
        return consumer;
    }

    /** 모든 consumer 를 번갈아 poll 하면서 조건이 참이 될 때까지 유한하게 기다린다. 받은 record 는 버린다. */
    private void pollAllUntil(BooleanSupplier condition) {
        pollAllUntil(records -> condition.getAsBoolean());
    }

    /** 번갈아 poll 하며 각 record 를 어느 consumer 가 받았는지(client.id)와 함께 조건에 넘긴다. */
    private void pollAllUntil(java.util.function.Predicate<Map<ConsumerRecord<String, String>, String>> condition) {
        long deadline = System.currentTimeMillis() + DEADLINE.toMillis();
        Map<ConsumerRecord<String, String>, String> received = new LinkedHashMap<>();
        while (System.currentTimeMillis() < deadline) {
            for (KafkaConsumer<String, String> consumer : consumers) {
                String clientId = clientIdOf(consumer);
                consumer.poll(Duration.ofMillis(200)).forEach(record -> received.put(record, clientId));
            }
            if (condition.test(received)) {
                return;
            }
        }
        throw new AssertionError("기한 안에 조건을 만족하지 못했다. 배정표: " + describeAssignments());
    }

    private String clientIdOf(KafkaConsumer<String, String> consumer) {
        return "c" + (consumers.indexOf(consumer) + 1);
    }

    private Set<TopicPartition> union() {
        Set<TopicPartition> all = new HashSet<>();
        consumers.forEach(consumer -> all.addAll(consumer.assignment()));
        return all;
    }

    private boolean eachPartitionOwnedByExactlyOne() {
        return List.of(P0, P1).stream()
            .allMatch(partition -> consumers.stream().filter(consumer -> consumer.assignment().contains(partition)).count() == 1);
    }

    private String ownerOf(TopicPartition partition) {
        return consumers.stream().filter(consumer -> consumer.assignment().contains(partition)).map(this::clientIdOf).findFirst().orElse("없음");
    }

    private String describeAssignments() {
        StringBuilder table = new StringBuilder();
        for (KafkaConsumer<String, String> consumer : consumers) {
            table.append(clientIdOf(consumer)).append('=')
                .append(new TreeSet<>(consumer.assignment().stream().map(TopicPartition::partition).toList()))
                .append(' ');
        }
        return table.toString().trim();
    }

    private static RecordMetadata send(ProducerRecord<String, String> record) throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all"); // 단일 broker(복제 1)라 acks=1 과 같게 동작한다
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            return producer.send(record).get(10, TimeUnit.SECONDS);
        }
    }
}
