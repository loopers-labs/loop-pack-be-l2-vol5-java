package com.loopers.kafka;

import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaPartitionAssignmentTest {
    @Test
    void 파티션_둘을_같은_그룹의_Consumer_하나_둘_셋이_나누어_맡는다() throws Exception {
        // Arrange
        try (var lab = new AssignmentLab()) {
            assertThat(lab.members).isEmpty();

            // Act & Assert
            lab.addMember();
            assertThat(lab.awaitPartitionCountsInStableGroup(1)).containsExactly(2);
            lab.sendAndReadFromBothPartitions("one");

            lab.addMember();
            assertThat(lab.awaitPartitionCountsInStableGroup(2)).containsExactly(1, 1);
            lab.sendAndReadFromBothPartitions("two");

            lab.addMember();
            assertThat(lab.awaitPartitionCountsInStableGroup(3)).containsExactly(0, 1, 1);
            lab.sendAndReadFromBothPartitions("three");
        }
    }

    @Test
    void 같은_key는_고정된_파티션_개수에서_같은_파티션에_순서대로_기록된다() throws Exception {
        // Arrange
        try (var lab = new AssignmentLab()) {
            var sent = new ArrayList<RecordMetadata>();

            // Act
            for (String value : List.of("created", "paid", "shipped")) {
                sent.add(lab.producer.send(new ProducerRecord<>(lab.topic, "42", value))
                        .get(10, TimeUnit.SECONDS));
            }

            // Assert
            assertThat(sent).extracting(RecordMetadata::partition).containsOnly(sent.getFirst().partition());
            assertThat(sent).extracting(RecordMetadata::offset).containsExactly(0L, 1L, 2L);
            System.out.printf("KEY_MAPPING key=42 partition=%d offsets=%s bootstrap=%s%n",
                    sent.getFirst().partition(), sent.stream().map(RecordMetadata::offset).toList(),
                    KafkaTestContainersConfig.bootstrapServers());
        }
    }

    private static final class AssignmentLab implements AutoCloseable {
        private final String topic = "w6-assignment-" + UUID.randomUUID();
        private final String group = "w6-assignment-" + UUID.randomUUID();
        private final AdminClient admin;
        private final KafkaProducer<String, String> producer;
        private final List<KafkaConsumer<String, String>> members = new ArrayList<>();

        private AssignmentLab() throws Exception {
            String bootstrap = KafkaTestContainersConfig.bootstrapServers();
            admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap));
            admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1))).all().get(10, TimeUnit.SECONDS);
            producer = new KafkaProducer<>(Map.of(
                    "bootstrap.servers", bootstrap, "acks", "all", "enable.idempotence", true,
                    "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class));
        }

        private void addMember() {
            var properties = new java.util.HashMap<String, Object>();
            properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers());
            properties.put(ConsumerConfig.GROUP_ID_CONFIG, group);
            properties.put(ConsumerConfig.CLIENT_ID_CONFIG, "member-" + members.size());
            properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            properties.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10_000);
            properties.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 1_000);
            properties.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 30_000);
            var member = new KafkaConsumer<String, String>(properties);
            member.subscribe(List.of(topic));
            members.add(member);
        }

        private List<Integer> awaitPartitionCountsInStableGroup(int expectedMembers) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            int stableRounds = 0;
            while (System.nanoTime() < deadline) {
                pollAll();
                var brokerGroup = describeGroup();
                var sizes = members.stream().map(member -> member.assignment().size()).sorted().toList();
                var assigned = members.stream().flatMap(member -> member.assignment().stream()).toList();
                var localMemberIds = members.stream().map(member -> member.groupMetadata().memberId())
                        .collect(Collectors.toSet());
                boolean membershipMatches = brokerGroup != null && brokerGroup.state() == ConsumerGroupState.STABLE
                        && brokerGroup.members().size() == expectedMembers
                        && brokerGroup.members().stream().map(member -> member.consumerId())
                                .collect(Collectors.toSet()).equals(localMemberIds);
                boolean assignmentsMatch = membershipMatches && brokerGroup.members().stream().allMatch(brokerMember ->
                        members.stream().anyMatch(member ->
                                member.groupMetadata().memberId().equals(brokerMember.consumerId())
                                        && member.assignment().equals(brokerMember.assignment().topicPartitions())));
                boolean matches = assignmentsMatch && assigned.size() == 2
                        && new HashSet<>(assigned).equals(Set.of(new TopicPartition(topic, 0), new TopicPartition(topic, 1)));
                stableRounds = matches ? stableRounds + 1 : 0;
                if (stableRounds == 3) {
                    System.out.printf("ASSIGNMENT members=%d brokerMembers=%d state=%s partitions=%s bootstrap=%s%n",
                            members.size(), brokerGroup.members().size(), brokerGroup.state(),
                            members.stream().map(KafkaConsumer::assignment).toList(),
                            KafkaTestContainersConfig.bootstrapServers());
                    return sizes;
                }
            }
            throw new AssertionError("Stable assignment not reached: "
                    + members.stream().map(KafkaConsumer::assignment).toList());
        }

        private ConsumerGroupDescription describeGroup() throws Exception {
            try {
                return admin.describeConsumerGroups(List.of(group)).all().get(3, TimeUnit.SECONDS).get(group);
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof GroupIdNotFoundException) {
                    return null;
                }
                throw failure;
            }
        }

        private void sendAndReadFromBothPartitions(String stage) throws Exception {
            Set<String> expected = Set.of(stage + "-p0", stage + "-p1");
            for (int partition = 0; partition < 2; partition++) {
                producer.send(new ProducerRecord<>(topic, partition, "lab", stage + "-p" + partition))
                        .get(10, TimeUnit.SECONDS);
            }
            var received = new HashSet<String>();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (!received.containsAll(expected) && System.nanoTime() < deadline) {
                received.addAll(pollAll());
            }
            assertThat(received).containsAll(expected);
        }

        private List<String> pollAll() {
            var received = new ArrayList<String>();
            for (var member : members) {
                var records = member.poll(Duration.ofMillis(100));
                records.forEach(record -> {
                    assertThat(member.assignment()).contains(new TopicPartition(record.topic(), record.partition()));
                    received.add(record.value());
                });
                if (!records.isEmpty()) {
                    member.commitSync(Duration.ofSeconds(5));
                }
            }
            return received;
        }

        @Override
        public void close() throws Exception {
            for (var member : members) {
                member.close(Duration.ofSeconds(3));
            }
            producer.close(Duration.ofSeconds(3));
            try {
                admin.deleteTopics(List.of(topic)).all().get(10, TimeUnit.SECONDS);
            } finally {
                admin.close(Duration.ofSeconds(3));
            }
        }
    }
}
