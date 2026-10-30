package com.loopers.infrastructure.purchase;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Transport probes only: no Spring context, database, OrderConfirmed event or business topic. */
@Testcontainers
@Timeout(90)
class KafkaGroupAssignmentIntegrationTest {
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration API_TIMEOUT = Duration.ofSeconds(5);
    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
        .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0");

    @Test
    void twoPartitionsAreSharedByOneThenTwoThenThreePollingMembersAndBothAreReadAtEveryStage() throws Exception {
        String topic = "w6-assignment-probe-" + UUID.randomUUID();
        String group = "w6-assignment-group-" + UUID.randomUUID();
        AdminClient admin = admin();
        List<PollingMember> members = new ArrayList<>();
        ConcurrentLinkedQueue<Receipt> receipts = new ConcurrentLinkedQueue<>();
        try {
            createTopic(admin, topic);
            KafkaProducer<String, String> producer = producer();
            try {
                for (int count = 1; count <= 3; count++) {
                    PollingMember member = new PollingMember("consumer-" + count, topic, group, receipts);
                    members.add(member);
                    member.start();
                    Map<String, Set<TopicPartition>> assigned = stableAssignment(admin, topic, group, members);
                    List<Integer> sizes = assigned.values().stream().map(Set::size).sorted().toList();
                    assertThat(sizes).containsExactlyElementsOf(switch (count) {
                        case 1 -> List.of(2);
                        case 2 -> List.of(1, 1);
                        default -> List.of(0, 1, 1);
                    });
                    List<Receipt> expected = sendProbes(producer, topic, count, assigned);
                    awaitReceiptsAndCommits(admin, group, members, receipts, expected);
                    System.out.printf("assignment_verified topic=%s group=%s consumers=%d assignments=%s received=%s%n",
                        topic, group, count, display(assigned), expected);
                }
                assertHealthy(members);
                assertThat(receipts).hasSize(6);
            } finally {
                try {
                    stopAndJoin(members);
                } finally {
                    producer.close(CLOSE_TIMEOUT);
                }
            }
        } finally {
            admin.close(CLOSE_TIMEOUT);
        }
    }

    @Test
    void identicalSerializedKeysUseTheSamePartitionAtAFixedPartitionCount() throws Exception {
        String topic = "w6-key-probe-" + UUID.randomUUID();
        AdminClient admin = admin();
        try {
            createTopic(admin, topic);
            KafkaProducer<String, String> producer = producer();
            try {
                Map<String, List<RecordMetadata>> byKey = new TreeMap<>();
                for (String key : List.of("42", "43", "42", "43", "42", "43")) {
                    // No explicit partition: the real producer selects it from the serialized key.
                    RecordMetadata sent = producer.send(new ProducerRecord<>(topic, key, "key-probe-" + key))
                        .get(10, TimeUnit.SECONDS);
                    byKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(sent);
                }
                for (var entry : byKey.entrySet()) {
                    assertThat(entry.getValue()).hasSize(3);
                    assertThat(entry.getValue()).extracting(RecordMetadata::topic).containsOnly(topic);
                    assertThat(entry.getValue()).extracting(RecordMetadata::partition).containsOnly(entry.getValue().getFirst().partition());
                    assertThat(entry.getValue()).extracting(RecordMetadata::offset).doesNotHaveDuplicates();
                    System.out.printf("key_mapping_verified topic=%s key=%s partition=%d offsets=%s%n", topic, entry.getKey(),
                        entry.getValue().getFirst().partition(), entry.getValue().stream().map(RecordMetadata::offset).toList());
                }
                // Different keys may share a partition. This is not a load-balancing guarantee.
            } finally {
                producer.close(CLOSE_TIMEOUT);
            }
        } finally {
            admin.close(CLOSE_TIMEOUT);
        }
    }

    private Map<String, Set<TopicPartition>> stableAssignment(AdminClient admin, String topic, String group,
                                                             List<PollingMember> members) {
        AtomicReference<String> candidate = new AtomicReference<>();
        AtomicReference<Map<String, Long>> initialPolls = new AtomicReference<>();
        AtomicReference<Map<String, Set<TopicPartition>>> result = new AtomicReference<>();
        AtomicInteger stableSamples = new AtomicInteger();
        await().pollInterval(Duration.ofMillis(100)).atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertHealthy(members);
            try {
                for (PollingMember member : members) {
                    assertThat(member.state.get().memberId()).isNotBlank();
                }
                var description = admin.describeConsumerGroups(List.of(group)).all().get(5, TimeUnit.SECONDS).get(group);
                assertThat(description.state()).isEqualTo(ConsumerGroupState.STABLE);
                assertThat(description.members()).hasSize(members.size());
                Map<String, Set<TopicPartition>> assignments = new TreeMap<>();
                Map<String, Long> polls = new TreeMap<>();
                List<Integer> generations = new ArrayList<>();
                for (PollingMember member : members) {
                    MemberState state = member.state.get();
                    var brokerMember = description.members().stream().filter(item -> item.clientId().equals(member.name))
                        .findFirst().orElseThrow(() -> new AssertionError("Member not yet registered: " + member.name));
                    assertThat(state.memberId()).isEqualTo(brokerMember.consumerId());
                    assertThat(state.assigned()).isEqualTo(brokerMember.assignment().topicPartitions());
                    assignments.put(member.name, state.assigned());
                    polls.put(member.name, state.pollCount());
                    generations.add(state.generation());
                }
                assertThat(generations).allMatch(generation -> generation >= 0).containsOnly(generations.getFirst());
                assertThat(assignments.values().stream().flatMap(Set::stream).toList())
                    .containsExactlyInAnyOrder(new TopicPartition(topic, 0), new TopicPartition(topic, 1));
                String signature = generations.getFirst() + ":" + display(assignments);
                if (!signature.equals(candidate.get())) {
                    candidate.set(signature);
                    initialPolls.set(polls);
                    stableSamples.set(0);
                }
                int samples = stableSamples.incrementAndGet();
                if (samples < 5) {
                    throw new StabilizingAssignment();
                }
                for (PollingMember member : members) {
                    assertThat(polls.get(member.name)).isGreaterThan(initialPolls.get().get(member.name));
                }
                result.set(assignments);
            } catch (StabilizingAssignment waiting) {
                throw waiting;
            } catch (AssertionError mismatch) {
                candidate.set(null);
                stableSamples.set(0);
                throw mismatch;
            }
        });
        return result.get();
    }

    private List<Receipt> sendProbes(KafkaProducer<String, String> producer, String topic, int stage,
                                     Map<String, Set<TopicPartition>> assigned) throws Exception {
        List<Receipt> expected = new ArrayList<>();
        for (int partition = 0; partition < 2; partition++) {
            TopicPartition location = new TopicPartition(topic, partition);
            String owner = assigned.entrySet().stream().filter(entry -> entry.getValue().contains(location))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
            String key = "probe-partition-" + partition;
            String value = "stage-" + stage + "-partition-" + partition;
            var sent = producer.send(new ProducerRecord<>(topic, partition, key, value)).get(10, TimeUnit.SECONDS);
            assertThat(sent.partition()).isEqualTo(partition);
            expected.add(new Receipt(owner, location, sent.offset(), key, value));
        }
        return expected;
    }

    private void awaitReceiptsAndCommits(AdminClient admin, String group, List<PollingMember> members,
                                        ConcurrentLinkedQueue<Receipt> receipts, List<Receipt> expected) {
        Set<String> values = Set.of(expected.get(0).value(), expected.get(1).value());
        await().pollInterval(Duration.ofMillis(100)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertHealthy(members);
            assertThat(receipts.stream().filter(receipt -> values.contains(receipt.value())).toList())
                .containsExactlyInAnyOrderElementsOf(expected);
            var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            for (Receipt receipt : expected) {
                assertThat(offsets).containsKey(receipt.partition());
                assertThat(offsets.get(receipt.partition()).offset()).isEqualTo(receipt.offset() + 1);
            }
        });
    }

    private void createTopic(AdminClient admin, String topic) throws Exception {
        admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1))).all().get(10, TimeUnit.SECONDS);
        var description = admin.describeTopics(List.of(topic)).allTopicNames().get(5, TimeUnit.SECONDS).get(topic);
        assertThat(description.partitions()).hasSize(2);
    }

    private void assertHealthy(List<PollingMember> members) {
        for (PollingMember member : members) {
            if (member.failure.get() != null) {
                throw new IllegalStateException("Consumer worker failed: " + member.name, member.failure.get());
            }
            assertThat(member.thread.isAlive()).as("polling worker %s", member.name).isTrue();
        }
    }

    private void stopAndJoin(List<PollingMember> members) throws InterruptedException {
        members.forEach(PollingMember::requestStop);
        for (PollingMember member : members) {
            member.thread.join(10_000);
        }
        for (PollingMember member : members) {
            assertThat(member.thread.isAlive()).as("worker must terminate: %s", member.name).isFalse();
            assertThat(member.failure.get()).as("worker failure: %s", member.name).isNull();
        }
    }

    private Map<String, List<Integer>> display(Map<String, Set<TopicPartition>> assigned) {
        Map<String, List<Integer>> display = new TreeMap<>();
        assigned.forEach((member, partitions) -> display.put(member, partitions.stream().map(TopicPartition::partition).sorted().toList()));
        return display;
    }

    private AdminClient admin() {
        return AdminClient.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000,
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000));
    }

    private KafkaProducer<String, String> producer() {
        return new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.ACKS_CONFIG, "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
            ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000,
            ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000,
            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000));
    }

    private static final class PollingMember {
        private final String name;
        private final String topic;
        private final String group;
        private final ConcurrentLinkedQueue<Receipt> receipts;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<MemberState> state = new AtomicReference<>(new MemberState("", -1, Set.of(), 0));
        private final Thread thread;
        private volatile KafkaConsumer<String, String> consumer;

        private PollingMember(String name, String topic, String group, ConcurrentLinkedQueue<Receipt> receipts) {
            this.name = name;
            this.topic = topic;
            this.group = group;
            this.receipts = receipts;
            this.thread = new Thread(this::poll, "w6-assignment-" + name);
        }

        private void start() {
            thread.start();
        }

        private void requestStop() {
            running.set(false);
            KafkaConsumer<String, String> current = consumer;
            if (current != null) {
                current.wakeup(); // The only consumer API called outside its owning worker thread.
            }
        }

        private void poll() {
            try {
                Map<String, Object> config = new HashMap<>();
                config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
                config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
                config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
                config.put(ConsumerConfig.GROUP_ID_CONFIG, group);
                config.put(ConsumerConfig.CLIENT_ID_CONFIG, name);
                config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
                config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
                config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
                config.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);
                config.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 100);
                config.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 6_000);
                config.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 1_000);
                config.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5_000);
                consumer = new KafkaConsumer<>(config);
                consumer.subscribe(List.of(topic));
                long polls = 0;
                while (running.get()) {
                    var records = consumer.poll(Duration.ofMillis(100));
                    List<Receipt> batch = new ArrayList<>();
                    records.forEach(record -> batch.add(new Receipt(name, new TopicPartition(record.topic(), record.partition()),
                        record.offset(), record.key(), record.value())));
                    if (!batch.isEmpty()) {
                        consumer.commitSync(API_TIMEOUT);
                        receipts.addAll(batch);
                    }
                    var metadata = consumer.groupMetadata();
                    state.set(new MemberState(metadata.memberId(), metadata.generationId(), Set.copyOf(consumer.assignment()), ++polls));
                }
            } catch (WakeupException stopped) {
                if (running.get()) {
                    failure.compareAndSet(null, stopped);
                }
            } catch (Throwable unexpected) {
                failure.compareAndSet(null, unexpected);
            } finally {
                if (consumer != null) {
                    try {
                        consumer.close(CLOSE_TIMEOUT);
                    } catch (Throwable closeFailure) {
                        failure.compareAndSet(null, closeFailure);
                    }
                }
            }
        }
    }

    private record MemberState(String memberId, int generation, Set<TopicPartition> assigned, long pollCount) { }

    private record Receipt(String member, TopicPartition partition, long offset, String key, String value) { }

    private static final class StabilizingAssignment extends AssertionError { }
}
