package com.loopers.experiment;

import com.loopers.support.KafkaProbe;
import com.loopers.testcontainers.KafkaTestContainers;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 실험 A. partition 배정 — 같은 group의 consumer를 1 → 2 → 3개로 늘리며 실제 assignment를 본다
 * (docs/week6/design.md 9번, docs/week6/decisions.md D-21 A).
 *
 * <p>Spring 없이 저수준 {@link KafkaConsumer}로 공용 Testcontainers broker에 붙는다. topic은 partition 2개인 이 실험만의 격리
 * topic이라 업무 topic에 가짜 구매 활동을 만들지 않는다.
 *
 * <ul>
 *   <li>매 단계 모든 consumer가 poll을 계속하며, 실제 {@code assignment()}가 목표 조건에 닿을 때까지 상한 있게 기다린다.
 *       consumer 객체 수를 세거나 잠만 자지 않는다. 목표 조건에는 "모든 consumer가 같은 generation에 들어와 있다"를 함께 건다 —
 *       3개째가 join하기 전의 배정(c1=[p0], c2=[p1], c3=[])도 "하나는 비어 있다"를 만족하므로, rebalance가 실제로 끝났는지를
 *       generation으로 확인한다.</li>
 *   <li>KafkaConsumer는 스레드 안전하지 않다. 한 스레드에서 돌아가며 짧게 poll한다(Awaitility도 같은 스레드에서 조건을 평가한다).</li>
 *   <li>누가 어느 partition을 갖는지는 assignor가 정하므로 assert하지 않는다. 배정표는 출력으로 남긴다(PR용).</li>
 *   <li>key 매핑은 같은 key → 같은 partition만 확인한다. 다른 key가 다른 partition으로 간다고는 assert하지 않는다.</li>
 * </ul>
 */
class PartitionAssignmentExperimentTest {

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofMillis(100);
    private static final Duration CLOSE = Duration.ofSeconds(5);

    private final String topic = KafkaProbe.unique("experiment-assignment");
    private final String group = KafkaProbe.unique("experiment-assignment-group");
    private final List<KafkaConsumer<String, byte[]>> consumers = new ArrayList<>();
    private final List<ConsumerRecord<String, byte[]>> received = new ArrayList<>();
    private final Map<ConsumerRecord<String, byte[]>, Integer> receivedBy = new HashMap<>();

    @AfterEach
    void closeConsumers() {
        consumers.forEach(consumer -> consumer.close(CLOSE));
    }

    private KafkaConsumer<String, byte[]> join() {
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainers.bootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, group,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
        consumer.subscribe(List.of(topic));
        consumers.add(consumer);
        return consumer;
    }

    /** 모든 consumer를 한 바퀴 짧게 poll한다. 받은 기록은 누가 받았는지와 함께 모은다. */
    private void pollAll() {
        for (int i = 0; i < consumers.size(); i++) {
            for (ConsumerRecord<String, byte[]> record : consumers.get(i).poll(POLL)) {
                received.add(record);
                receivedBy.put(record, i);
            }
        }
    }

    private Set<Integer> partitionsOf(KafkaConsumer<String, byte[]> consumer) {
        return consumer.assignment().stream().map(TopicPartition::partition).collect(Collectors.toSet());
    }

    /** 모든 consumer가 previousGeneration보다 새로운 같은 generation에 있고, 실제 배정이 target을 만족할 때까지 poll한다. */
    private int awaitAssignment(int previousGeneration, Predicate<List<Set<Integer>>> target) {
        await().atMost(WAIT).pollInSameThread().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(1))
            .until(() -> {
                pollAll();
                Set<Integer> generations = consumers.stream()
                    .map(consumer -> consumer.groupMetadata().generationId())
                    .collect(Collectors.toSet());
                return generations.size() == 1 && generations.iterator().next() > previousGeneration
                    && target.test(assignments());
            });
        return consumers.get(0).groupMetadata().generationId();
    }

    private List<Set<Integer>> assignments() {
        return consumers.stream().map(this::partitionsOf).toList();
    }

    private void printTable() {
        String table = IntStream.range(0, consumers.size())
            .mapToObj(i -> "c" + (i + 1) + "=" + partitionsOf(consumers.get(i)).stream().sorted()
                .map(p -> "p" + p).collect(Collectors.joining(", ", "[", "]")))
            .collect(Collectors.joining(", "));
        System.out.printf("[A] consumers=%d → %s (generation=%d)%n",
            consumers.size(), table, consumers.get(0).groupMetadata().generationId());
    }

    @DisplayName("A: 같은 group의 consumer가 1개면 partition 둘을 다 맡고, 2개면 하나씩, 3개면 하나는 빈손이다. "
        + "한 partition은 한 consumer만 맡는다.")
    @Test
    void assignmentFollowsConsumerCount() {
        // arrange
        KafkaTestContainers.createTopic(topic);
        Set<Integer> all = Set.of(0, 1);

        // act 1 — consumer 1개
        join();
        int generation1 = awaitAssignment(-1, a -> a.get(0).equals(all));
        printTable();

        // assert 1
        assertThat(partitionsOf(consumers.get(0))).isEqualTo(all);

        // act 2 — consumer 2개: rebalance 뒤 하나씩. 두 partition에 명시적으로 보낸 기록을 각자 읽는다
        join();
        int generation2 = awaitAssignment(generation1, a -> a.get(0).size() == 1 && a.get(1).size() == 1 && union(a).equals(all));
        printTable();
        try (KafkaProbe probe = new KafkaProbe(topic, group)) {
            RecordMetadata toP0 = probe.send(0, "p0", "to-p0".getBytes(StandardCharsets.UTF_8));
            RecordMetadata toP1 = probe.send(1, "p1", "to-p1".getBytes(StandardCharsets.UTF_8));
            await().atMost(WAIT).pollInSameThread().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(1))
                .until(() -> {
                    pollAll();
                    return received.stream().map(ConsumerRecord::partition).collect(Collectors.toSet()).equals(all);
                });
            for (ConsumerRecord<String, byte[]> record : received) {
                System.out.printf("[A] consumers=2 → p%d@%d(%s)를 c%d가 읽음%n",
                    record.partition(), record.offset(), new String(record.value(), StandardCharsets.UTF_8),
                    receivedBy.get(record) + 1);
            }

            // assert 2
            assertAll(
                () -> assertThat(toP0.partition()).isEqualTo(0),
                () -> assertThat(toP1.partition()).isEqualTo(1),
                () -> assertThat(partitionsOf(consumers.get(0))).hasSize(1),
                () -> assertThat(partitionsOf(consumers.get(1))).hasSize(1),
                () -> assertThat(union(assignments())).isEqualTo(all),
                () -> assertThat(received).hasSize(2),
                () -> assertThat(received).allSatisfy(record ->
                    assertThat(partitionsOf(consumers.get(receivedBy.get(record)))).containsExactly(record.partition()))
            );
        }

        // act 3 — consumer 3개: 적어도 하나는 배정이 없다
        join();
        awaitAssignment(generation2, a -> a.stream().anyMatch(Set::isEmpty) && union(a).equals(all) && total(a) == all.size());
        printTable();

        // assert 3
        List<Set<Integer>> assignments = assignments();
        assertAll(
            () -> assertThat(assignments).anyMatch(Set::isEmpty),
            () -> assertThat(union(assignments)).isEqualTo(all),
            () -> assertThat(total(assignments)).as("한 partition을 두 consumer가 맡지 않는다").isEqualTo(all.size())
        );
    }

    @DisplayName("A: producer가 partition을 고르게 두면, 같은 key로 여러 번 보낸 기록은 모두 같은 partition에 간다.")
    @Test
    void sameKeyGoesToSamePartition() {
        // arrange
        KafkaTestContainers.createTopic(topic);
        String key = String.valueOf(KafkaProbe.nextId());

        // act
        List<Integer> partitions = new ArrayList<>();
        try (KafkaProbe probe = new KafkaProbe(topic, group)) {
            for (int i = 0; i < 5; i++) {
                partitions.add(probe.send(key, "same-key-" + i).partition());
            }
        }
        System.out.printf("[A] key=%s를 5번 보냄 → partition %s%n", key, partitions);

        // assert
        assertThat(new HashSet<>(partitions)).hasSize(1);
    }

    private static Set<Integer> union(List<Set<Integer>> assignments) {
        return assignments.stream().flatMap(Set::stream).collect(Collectors.toSet());
    }

    private static int total(List<Set<Integer>> assignments) {
        return assignments.stream().mapToInt(Set::size).sum();
    }
}
