package com.loopers.infrastructure.kafka;

import com.loopers.support.kafka.GroupMember;
import com.loopers.support.kafka.KafkaLab;
import com.loopers.testcontainers.KafkaBrokerFixture;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * partition 2개짜리 격리 topic에서 같은 group의 consumer를 1 → 2 → 3개로 늘리며 실제 배정을 관찰한다 (W6 과제 5절).
 * 배정은 member가 계속 poll하는 상태에서 broker에 물어 본 값이다. consumer 객체 수를 세거나 sleep으로 기다린 것이 아니다.
 * 실험 record는 업무 topic(order-events)과 분리한 topic에만 보내므로 구매 활동을 만들지 않는다.
 *
 * <p>이 실험은 단일 broker다. replication factor 1이라 {@code acks=all}이 지키려는 복제 보장(리더 장애 뒤의 record 보존)은 확인하지 못한다.
 */
class PartitionAssignmentKafkaDeliveryTest {

    private static final Duration STABLE_TIMEOUT = Duration.ofSeconds(90);

    /** group이 안정(STABLE)이고 기대한 member 수가 모두 배정을 받을 때까지 기다려, broker가 본 배정을 돌려준다. */
    private static Map<String, Set<Integer>> awaitAssignments(String group, String topic, int members) {
        Map<String, Set<Integer>>[] stable = new Map[1];
        await().atMost(STABLE_TIMEOUT).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Map<String, Set<Integer>> assignments = KafkaLab.assignmentsOf(group, topic);
            assertThat(assignments).hasSize(members);
            Set<Integer> union = new TreeSet<>();
            int total = 0;
            for (Set<Integer> partitions : assignments.values()) {
                union.addAll(partitions);
                total += partitions.size();
            }
            assertThat(union).as("두 partition이 모두 누군가에게 배정").containsExactly(0, 1);
            assertThat(total).as("각 partition은 한 member에게만 배정").isEqualTo(2);
            stable[0] = assignments;
        });
        return stable[0];
    }

    @DisplayName("같은 group의 consumer를 1 → 2 → 3개로 늘리면, 각 partition은 한 member에게만 배정되고 3개일 때는 배정 없이 남는 member가 있다.")
    @Test
    void assignmentChangesAsConsumersJoin() {
        // arrange
        String topic = KafkaBrokerFixture.createTopic("assignment-lab", 2);
        String group = KafkaBrokerFixture.uniqueName("assignment-group");
        List<GroupMember> members = new ArrayList<>();
        Map<Integer, Map<String, Set<Integer>>> table = new TreeMap<>();

        try {
            // act & assert: 1개
            members.add(new GroupMember(group, "member-1", topic));
            table.put(1, awaitAssignments(group, topic, 1));
            assertThat(table.get(1).get("member-1")).containsExactly(0, 1);

            // act & assert: 2개
            members.add(new GroupMember(group, "member-2", topic));
            table.put(2, awaitAssignments(group, topic, 2));
            assertThat(table.get(2).values()).allSatisfy(partitions -> assertThat(partitions).hasSize(1));

            // act & assert: 3개
            members.add(new GroupMember(group, "member-3", topic));
            table.put(3, awaitAssignments(group, topic, 3));
            List<Integer> sizes = table.get(3).values().stream().map(Set::size).sorted().toList();
            assertThat(sizes).as("partition이 2개라 3개 중 적어도 하나는 배정이 없다").containsExactly(0, 1, 1);

            // assert: member가 자기 consumer에서 본 배정도 broker의 답과 같다
            for (GroupMember member : members) {
                assertThat(member.assignedPartitions()).isEqualTo(table.get(3).get(member.clientId()));
            }
        } finally {
            members.forEach(GroupMember::close);
            printTable(topic, group, table);
        }
    }

    @DisplayName("명시적으로 두 partition에 보낸 record는, 그 partition을 배정받은 member가 정확히 한 번 읽는다.")
    @Test
    void recordsOnEachPartitionAreReadByTheAssignedMember() throws Exception {
        // arrange
        String topic = KafkaBrokerFixture.createTopic("assignment-lab", 2);
        String group = KafkaBrokerFixture.uniqueName("assignment-group");

        try (GroupMember first = new GroupMember(group, "member-1", topic);
             GroupMember second = new GroupMember(group, "member-2", topic);
             KafkaProducer<String, String> producer = KafkaLab.stringProducer()) {
            Map<String, Set<Integer>> assignments = awaitAssignments(group, topic, 2);

            // act: partition을 명시해 보낸다
            producer.send(new ProducerRecord<>(topic, 0, "lab-key-0", "to-partition-0")).get(30, TimeUnit.SECONDS);
            producer.send(new ProducerRecord<>(topic, 1, "lab-key-1", "to-partition-1")).get(30, TimeUnit.SECONDS);

            // assert
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(first.received().size() + second.received().size()).isEqualTo(2));
            for (GroupMember member : List.of(first, second)) {
                for (GroupMember.Received record : member.received()) {
                    assertThat(assignments.get(member.clientId())).as("읽은 member는 그 partition의 담당자").contains(record.partition());
                    assertThat(record.value()).isEqualTo("to-partition-" + record.partition());
                }
            }
            Set<String> values = new HashSet<>();
            first.received().forEach(r -> values.add(r.value()));
            second.received().forEach(r -> values.add(r.value()));
            assertThat(values).containsExactlyInAnyOrder("to-partition-0", "to-partition-1");
        }
    }

    @DisplayName("partitioner가 고르게 두면, 같은 key는 고정된 partition 수에서 항상 같은 partition으로 간다.")
    @Test
    void sameKeyGoesToSamePartition() throws Exception {
        // arrange
        String topic = KafkaBrokerFixture.createTopic("key-lab", 2);
        List<String> keys = List.of("42", "43", "44", "45");
        Map<String, Set<Integer>> partitionsByKey = new TreeMap<>();

        // act: 서로 다른 producer 두 개가 같은 key를 여러 번 보내고, 실제 전송 metadata의 partition을 모은다
        for (int run = 0; run < 2; run++) {
            try (KafkaProducer<String, String> producer = KafkaLab.stringProducer()) {
                for (int i = 0; i < 3; i++) {
                    for (String key : keys) {
                        RecordMetadata metadata = producer.send(new ProducerRecord<>(topic, key, "v")).get(30, TimeUnit.SECONDS);
                        partitionsByKey.computeIfAbsent(key, k -> new TreeSet<>()).add(metadata.partition());
                    }
                }
            }
        }

        // assert: 같은 key는 한 partition뿐이다. 서로 다른 key가 다른 partition으로 간다고는 단정하지 않는다
        for (String key : keys) {
            assertThat(partitionsByKey.get(key)).as("key=%s의 partition", key).hasSize(1);
        }
        System.out.println("[key → partition] " + partitionsByKey);
    }

    private static void printTable(String topic, String group, Map<Integer, Map<String, Set<Integer>>> table) {
        StringBuilder out = new StringBuilder("[배정표] topic=").append(topic).append(" (partition 2개), group=").append(group).append('\n');
        table.forEach((count, assignments) -> out.append("  consumer ").append(count).append("개: ").append(assignments).append('\n'));
        System.out.print(out);
    }
}
