package com.loopers.kafka;

import com.loopers.testcontainers.KafkaTestContainersConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static com.loopers.kafka.KafkaLearningSupport.consumer;
import static com.loopers.kafka.KafkaLearningSupport.consumerConfig;
import static com.loopers.kafka.KafkaLearningSupport.producer;
import static com.loopers.kafka.test.KafkaTestSupport.POLL_TIMEOUT;
import static com.loopers.kafka.test.KafkaTestSupport.admin;
import static com.loopers.kafka.test.KafkaTestSupport.awaitUntil;
import static com.loopers.kafka.test.KafkaTestSupport.closeAll;
import static com.loopers.kafka.test.KafkaTestSupport.createTopic;
import static com.loopers.kafka.test.KafkaTestSupport.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(KafkaTestContainersConfig.class)
class KafkaAssignmentLearningTest {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @DisplayName("같은 group의 consumer를 1개→2개→3개로 늘리면 partition 2개가 매번 정확히 한 member에게 배정되고 3개일 때 한 명은 빈 배정이다")
    @Test
    void assignmentFollowsGroupMembers() throws Exception {
        String topic = uniqueName("learning-assignment");
        String groupId = uniqueName("learning-assignment-group");
        Set<TopicPartition> partitions = Set.of(new TopicPartition(topic, 0), new TopicPartition(topic, 1));
        Map<String, KafkaConsumer<String, String>> consumers = new LinkedHashMap<String, KafkaConsumer<String, String>>();
        Map<String, List<ConsumerRecord<String, String>>> received = new LinkedHashMap<String, List<ConsumerRecord<String, String>>>();
        Admin admin = admin(bootstrapServers);
        KafkaProducer<String, String> producer = producer(bootstrapServers);
        try {
            createTopic(admin, topic, 2);

            for (int memberCount = 1; memberCount <= 3; memberCount++) {
                String clientId = "consumer-" + memberCount;
                KafkaConsumer<String, String> consumer = consumer(consumerConfig(bootstrapServers, groupId, clientId, "earliest"));
                consumer.subscribe(List.of(topic));
                consumers.put(clientId, consumer);

                int expectedMembers = memberCount;
                awaitUntil(
                    "consumer " + expectedMembers + "개 배정 안정",
                    () -> {
                        pollAll(consumers, received);
                        return isSettled(admin, groupId, consumers, expectedMembers, partitions);
                    }
                );

                Map<String, Set<TopicPartition>> assignments = assignmentsOf(consumers);
                printAssignments(memberCount, assignments);
                assertEachPartitionHasOneOwner(assignments, partitions);
                if (memberCount == 3) {
                    assertThat(assignments.values()).filteredOn(Set::isEmpty).hasSize(1);
                }
                if (memberCount == 2) {
                    assertOwnersReadTheirPartitions(producer, topic, consumers, received, assignments);
                }
            }
        } finally {
            List<AutoCloseable> closeables = new ArrayList<AutoCloseable>(consumers.values());
            closeables.add(producer);
            closeables.add(admin);
            closeAll(closeables);
        }
    }

    @DisplayName("partition을 지정하지 않고 같은 key로 여러 번 보내면 send 결과의 partition이 모두 같다")
    @Test
    void sameKeyIsSentToSamePartition() throws Exception {
        String topic = uniqueName("learning-key");
        String key = "1001";
        Admin admin = admin(bootstrapServers);
        KafkaProducer<String, String> producer = producer(bootstrapServers);
        try {
            createTopic(admin, topic, 2);

            List<Integer> sentPartitions = new ArrayList<Integer>();
            for (int sequence = 1; sequence <= 5; sequence++) {
                RecordMetadata metadata = producer.send(new ProducerRecord<>(topic, key, "event-" + sequence))
                    .get(10, TimeUnit.SECONDS);
                sentPartitions.add(metadata.partition());
            }

            System.out.println("[key 매핑] topic=" + topic + " key=" + key + " → partition " + sentPartitions);
            assertThat(new HashSet<Integer>(sentPartitions)).hasSize(1);
        } finally {
            closeAll(List.of(producer, admin));
        }
    }

    private void pollAll(
        Map<String, KafkaConsumer<String, String>> consumers,
        Map<String, List<ConsumerRecord<String, String>>> received
    ) {
        for (Map.Entry<String, KafkaConsumer<String, String>> entry : consumers.entrySet()) {
            ConsumerRecords<String, String> records = entry.getValue().poll(POLL_TIMEOUT);
            for (ConsumerRecord<String, String> record : records) {
                received.computeIfAbsent(entry.getKey(), clientId -> new ArrayList<>()).add(record);
            }
        }
    }

    // consumer 객체 수가 아니라 broker가 본 group(안정 상태·member 수·member별 배정)과 각 consumer의 실제 배정이 일치할 때만 끝난 것으로 본다.
    private boolean isSettled(
        Admin admin,
        String groupId,
        Map<String, KafkaConsumer<String, String>> consumers,
        int expectedMembers,
        Set<TopicPartition> partitions
    ) {
        ConsumerGroupDescription group;
        try {
            group = admin.describeConsumerGroups(List.of(groupId))
                .describedGroups()
                .get(groupId)
                .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("consumer group 조회 실패: " + groupId, e);
        }
        if (group.state() != ConsumerGroupState.STABLE || group.members().size() != expectedMembers) {
            return false;
        }
        for (MemberDescription member : group.members()) {
            KafkaConsumer<String, String> consumer = consumers.get(member.clientId());
            if (consumer == null || !consumer.assignment().equals(member.assignment().topicPartitions())) {
                return false;
            }
        }
        Map<String, Set<TopicPartition>> assignments = assignmentsOf(consumers);
        int assignedCount = assignments.values().stream().mapToInt(Set::size).sum();
        Set<TopicPartition> assigned = new HashSet<TopicPartition>();
        assignments.values().forEach(assigned::addAll);
        return assignedCount == partitions.size() && assigned.equals(partitions);
    }

    private Map<String, Set<TopicPartition>> assignmentsOf(Map<String, KafkaConsumer<String, String>> consumers) {
        Map<String, Set<TopicPartition>> assignments = new LinkedHashMap<String, Set<TopicPartition>>();
        consumers.forEach((clientId, consumer) -> assignments.put(clientId, Set.copyOf(consumer.assignment())));
        return assignments;
    }

    private void printAssignments(int memberCount, Map<String, Set<TopicPartition>> assignments) {
        System.out.println("[배정표] consumer " + memberCount + "개");
        assignments.forEach((clientId, assigned) -> System.out.println(
            "  " + clientId + " → " + assigned.stream().map(TopicPartition::partition).sorted().toList()
        ));
    }

    private void assertEachPartitionHasOneOwner(
        Map<String, Set<TopicPartition>> assignments,
        Set<TopicPartition> partitions
    ) {
        for (TopicPartition partition : partitions) {
            assertThat(assignments.values()).filteredOn(assigned -> assigned.contains(partition)).hasSize(1);
        }
    }

    private void assertOwnersReadTheirPartitions(
        KafkaProducer<String, String> producer,
        String topic,
        Map<String, KafkaConsumer<String, String>> consumers,
        Map<String, List<ConsumerRecord<String, String>>> received,
        Map<String, Set<TopicPartition>> assignments
    ) throws Exception {
        received.clear();
        for (int partition = 0; partition < 2; partition++) {
            producer.send(new ProducerRecord<>(topic, partition, null, "to-partition-" + partition)).get(10, TimeUnit.SECONDS);
        }

        awaitUntil(
            "두 partition의 record 수신",
            () -> {
                pollAll(consumers, received);
                return received.values().stream().flatMap(List::stream).map(ConsumerRecord::partition).distinct().count() == 2;
            }
        );

        received.forEach((clientId, records) -> {
            for (ConsumerRecord<String, String> record : records) {
                System.out.println("  " + clientId + " 수신 ← partition " + record.partition() + " offset " + record.offset());
                assertThat(assignments.get(clientId)).contains(new TopicPartition(record.topic(), record.partition()));
            }
        });
    }
}
