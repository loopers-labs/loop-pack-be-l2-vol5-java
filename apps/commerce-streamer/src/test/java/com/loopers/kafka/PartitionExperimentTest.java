package com.loopers.kafka;

import com.loopers.testcontainers.KafkaTestContainersConfig;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PartitionExperimentTest {

    private static final int PARTITIONS = 2;
    private static final Duration SETTLE_WITHIN = Duration.ofSeconds(60);
    private static final int STABLE_ROUNDS = 10;

    private final String topic = "exp-partition-" + UUID.randomUUID();
    private final String group = "exp-partition-" + UUID.randomUUID();
    private final List<KafkaConsumer<String, String>> consumers = new ArrayList<>();

    @AfterEach
    void closeConsumers() {
        consumers.forEach(consumer -> consumer.close(Duration.ofSeconds(5)));
    }

    private KafkaConsumer<String, String> join() {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, group,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
            new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(topic));
        consumers.add(consumer);
        return consumer;
    }

    private KafkaProducer<String, String> producer() {
        return new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
            ProducerConfig.ACKS_CONFIG, "all"),
            new StringSerializer(), new StringSerializer());
    }

    private List<Set<TopicPartition>> settledAssignments() throws InterruptedException {
        long deadline = System.nanoTime() + SETTLE_WITHIN.toNanos();
        List<Set<TopicPartition>> previous = List.of();
        int sameRounds = 0;
        while (System.nanoTime() < deadline) {
            consumers.forEach(consumer -> consumer.poll(Duration.ofMillis(100)));
            List<Set<TopicPartition>> current = consumers.stream()
                .map(consumer -> (Set<TopicPartition>) new HashSet<>(consumer.assignment()))
                .toList();
            int assigned = current.stream().mapToInt(Set::size).sum();
            sameRounds = current.equals(previous) && assigned == PARTITIONS ? sameRounds + 1 : 0;
            if (sameRounds >= STABLE_ROUNDS) {
                return current;
            }
            previous = current;
        }
        throw new AssertionError(SETTLE_WITHIN + " 안에 배정이 안정되지 않았다: " + previous);
    }

    @DisplayName("EXP-002: 같은 group 의 consumer 가 1, 2, 3 개일 때 broker 가 partition 2 개를 둘 다, 하나씩, 그리고 하나는 빈손으로 나눈다")
    @Test
    void assignsPartitionsAcrossGroupMembers() throws Exception {
        KafkaTestContainersConfig.createTopic(topic, PARTITIONS, Map.of());

        join();
        List<Set<TopicPartition>> one = settledAssignments();
        join();
        List<Set<TopicPartition>> two = settledAssignments();
        join();
        List<Set<TopicPartition>> three = settledAssignments();

        System.out.printf("EXP-002 consumer 1 개: %s%nEXP-002 consumer 2 개: %s%nEXP-002 consumer 3 개: %s%n",
            one, two, three);
        assertThat(one).extracting(Set::size).containsExactly(2);
        assertThat(two).extracting(Set::size).containsExactlyInAnyOrder(1, 1);
        assertThat(three).extracting(Set::size).containsExactlyInAnyOrder(1, 1, 0);
    }

    @DisplayName("EXP-003: 두 partition 에 명시해 보낸 record 를 모두 읽고, 같은 key 는 producer 가 같은 partition 을 고른다")
    @Test
    void mapsSameKeyToSamePartition() throws Exception {
        KafkaTestContainersConfig.createTopic(topic, PARTITIONS, Map.of());
        try (KafkaProducer<String, String> producer = producer()) {
            RecordMetadata toFirst = producer.send(new ProducerRecord<>(topic, 0, "p0", "first")).get(10, TimeUnit.SECONDS);
            RecordMetadata toSecond = producer.send(new ProducerRecord<>(topic, 1, "p1", "second")).get(10, TimeUnit.SECONDS);
            RecordMetadata firstOf42 = producer.send(new ProducerRecord<>(topic, "42", "a")).get(10, TimeUnit.SECONDS);
            RecordMetadata secondOf42 = producer.send(new ProducerRecord<>(topic, "42", "b")).get(10, TimeUnit.SECONDS);

            KafkaConsumer<String, String> reader = join();
            List<ConsumerRecord<String, String>> read = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (read.size() < 4 && System.nanoTime() < deadline) {
                reader.poll(Duration.ofMillis(200)).forEach(read::add);
            }

            System.out.printf("EXP-003 명시: p0=%s-%d@%d, p1=%s-%d@%d%nEXP-003 key 42: %s-%d@%d, %s-%d@%d%n",
                toFirst.topic(), toFirst.partition(), toFirst.offset(),
                toSecond.topic(), toSecond.partition(), toSecond.offset(),
                firstOf42.topic(), firstOf42.partition(), firstOf42.offset(),
                secondOf42.topic(), secondOf42.partition(), secondOf42.offset());
            assertThat(read).extracting(ConsumerRecord::value).contains("first", "second");
            assertThat(read).extracting(ConsumerRecord::partition).contains(0, 1);
            assertThat(secondOf42.partition()).isEqualTo(firstOf42.partition());
            assertThat(secondOf42.offset()).isGreaterThan(firstOf42.offset());
        }
    }
}
