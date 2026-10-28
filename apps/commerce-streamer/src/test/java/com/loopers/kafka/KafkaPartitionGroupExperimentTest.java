package com.loopers.kafka;

import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.DescribeConsumerGroupsOptions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(KafkaTestContainersConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KafkaPartitionGroupExperimentTest {
    private static final Logger log = LoggerFactory.getLogger(KafkaPartitionGroupExperimentTest.class);
    private static final Duration DEADLINE = Duration.ofSeconds(30);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(100);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(2);

    @Autowired
    private Environment environment;

    private final List<Member> members = new ArrayList<>();
    private final List<String> topics = new ArrayList<>();
    private final List<String> groups = new ArrayList<>();
    private final Collection<Delivery> deliveries = new ConcurrentLinkedQueue<>();
    private String bootstrapServers;
    private KafkaProducer<String, String> producer;
    private AdminClient admin;

    @BeforeAll
    void connectToFixture() {
        bootstrapServers = environment.getRequiredProperty("spring.kafka.bootstrap-servers");
        producer = new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.ACKS_CONFIG, "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true));
        admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
        log.info("W6_PARTITION_FIXTURE bootstrapServers={}, image=apache/kafka:3.8.1, brokerOwner=test-fixture-jvm",
            bootstrapServers);
    }

    @AfterEach
    void cleanOwnedResources() throws InterruptedException {
        List<Throwable> failures = new ArrayList<>();
        members.forEach(Member::requestStop);
        for (Member member : members) {
            try {
                member.awaitStop();
            } catch (AssertionError e) {
                failures.add(e);
            }
        }
        for (String group : groups) {
            try {
                admin.deleteConsumerGroups(List.of(group)).all().get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof GroupIdNotFoundException)) {
                    failures.add(e);
                }
            } catch (Exception e) {
                failures.add(e);
            }
        }
        try {
            if (!topics.isEmpty()) {
                admin.deleteTopics(topics).all().get(10, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            failures.add(e);
        } finally {
            members.clear();
            groups.clear();
            topics.clear();
            deliveries.clear();
        }
        if (!failures.isEmpty()) {
            AssertionError error = new AssertionError("Kafka 실험 자원 정리 실패", failures.getFirst());
            failures.stream().skip(1).forEach(error::addSuppressed);
            throw error;
        }
    }

    @AfterAll
    void closeClients() {
        try {
            if (producer != null) {
                producer.close(Duration.ofSeconds(5));
            }
        } finally {
            if (admin != null) {
                admin.close(CLOSE_TIMEOUT);
            }
        }
    }

    @DisplayName("partition 2개를 같은 group의 실제 consumer 1→2→3개가 분담하고, 각 단계에서 두 partition record를 읽는다.")
    @Test
    void observesAssignmentsAndDeliveryForOneTwoThreeMembers() throws Exception {
        String topic = createTopic("partition-group");
        String group = "partition-group-" + UUID.randomUUID();
        groups.add(group);
        Set<TopicPartition> expected = Set.of(new TopicPartition(topic, 0), new TopicPartition(topic, 1));

        for (int count : List.of(1, 2, 3)) {
            Member member = new Member("consumer-" + count, topic, group);
            members.add(member);
            member.start();

            StableAssignment assignment = awaitStable(group, expected, count);

            assertThat(assignment.partitions()).containsExactlyInAnyOrderElementsOf(expected);
            if (count == 1) {
                assertThat(assignment.members().values()).singleElement()
                    .satisfies(state -> assertThat(state.partitions()).hasSize(2));
            } else if (count == 2) {
                assertThat(assignment.members().values()).allSatisfy(state -> assertThat(state.partitions()).hasSize(1));
            } else {
                assertThat(assignment.members().values().stream().filter(state -> state.partitions().isEmpty()).count())
                    .isPositive();
            }
            log.info("W6_GROUP_ASSIGNMENT group={}, topic={}, memberCount={}, assignment={}",
                group, topic, count, assignment.members());

            Map<Position, String> targets = new HashMap<>();
            for (int partition : List.of(0, 1)) {
                String value = "members-" + count + "-partition-" + partition;
                RecordMetadata metadata = producer.send(new ProducerRecord<>(topic, partition, value, value))
                    .get(10, TimeUnit.SECONDS);
                targets.put(Position.from(metadata), value);
            }
            List<Delivery> received = awaitDeliveries(targets.keySet());
            for (Delivery delivery : received) {
                MemberState owner = assignment.members().get(delivery.clientId());
                assertThat(owner).isNotNull();
                assertThat(owner.memberId()).isEqualTo(delivery.memberId());
                assertThat(owner.partitions()).contains(delivery.position().topicPartition());
                assertThat(delivery.key()).isEqualTo(targets.get(delivery.position()));
                assertThat(delivery.value()).isEqualTo(targets.get(delivery.position()));
                log.info("W6_PARTITION_RECORD members={}, topic={}, partition={}, offset={}, consumer={}, memberId={}, value={}",
                    count, topic, delivery.position().partition(), delivery.position().offset(),
                    delivery.clientId(), delivery.memberId(), delivery.value());
            }
        }
    }

    @DisplayName("partition을 지정하지 않은 producer는 고정된 partition 수에서 같은 key를 같은 partition으로 보낸다.")
    @Test
    void mapsRepeatedKeysUsingActualProducerMetadata() throws Exception {
        String topic = createTopic("partition-key");

        for (String key : List.of("42", "43", "44")) {
            List<RecordMetadata> sent = new ArrayList<>();
            for (int attempt = 0; attempt < 4; attempt++) {
                sent.add(producer.send(new ProducerRecord<>(topic, key, key + "-" + attempt))
                    .get(10, TimeUnit.SECONDS));
            }

            assertThat(sent).allSatisfy(metadata -> {
                assertThat(metadata.topic()).isEqualTo(topic);
                assertThat(metadata.partition()).isIn(0, 1);
                assertThat(metadata.offset()).isNotNegative();
            });
            assertThat(sent.stream().map(RecordMetadata::partition).distinct().toList()).hasSize(1);
            log.info("W6_KEY_MAPPING topic={}, key={}, partition={}, offsets={}", topic, key,
                sent.getFirst().partition(), sent.stream().map(RecordMetadata::offset).toList());
        }
    }

    private String createTopic(String purpose) throws Exception {
        String topic = purpose + "-experiment-" + UUID.randomUUID();
        admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1))).all().get(10, TimeUnit.SECONDS);
        topics.add(topic);
        var description = admin.describeTopics(List.of(topic)).allTopicNames().get(10, TimeUnit.SECONDS).get(topic);
        assertThat(description.partitions()).hasSize(2);
        assertThat(description.partitions()).allSatisfy(partition -> assertThat(partition.replicas()).hasSize(1));
        assertThat(admin.describeCluster().nodes().get(10, TimeUnit.SECONDS)).hasSize(1);
        return topic;
    }

    private StableAssignment awaitStable(String group, Set<TopicPartition> expected, int count) throws Exception {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        StableAssignment previous = null;
        String last = "group 준비 중";
        while (System.nanoTime() < deadline) {
            checkMembers();
            ConsumerGroupDescription description = describeGroup(group);
            if (description != null) {
                last = description.toString();
                StableAssignment current = match(description, expected, count);
                if (current != null && current.equals(previous)) {
                    return current;
                }
                previous = current;
            } else {
                previous = null;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("안정된 배정 대기 실패: expectedMembers=" + count + ", broker=" + last
            + ", local=" + members.stream().map(member -> member.state.get()).toList());
    }

    private ConsumerGroupDescription describeGroup(String group) throws Exception {
        try {
            return admin.describeConsumerGroups(List.of(group), new DescribeConsumerGroupsOptions().timeoutMs(2_000))
                .describedGroups().get(group).get(3, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof GroupIdNotFoundException || e.getCause() instanceof RetriableException) {
                return null;
            }
            throw e;
        }
    }

    private StableAssignment match(ConsumerGroupDescription description, Set<TopicPartition> expected, int count) {
        if (description.state() != ConsumerGroupState.STABLE || description.members().size() != count) {
            return null;
        }
        Map<String, MemberState> observed = new TreeMap<>();
        for (Member member : members) {
            MemberState local = member.state.get();
            var remote = description.members().stream().filter(value -> value.clientId().equals(member.clientId))
                .findFirst();
            if (remote.isEmpty() || local.generation() < 0 || !local.memberId().equals(remote.get().consumerId())
                || !local.partitions().equals(remote.get().assignment().topicPartitions())) {
                return null;
            }
            observed.put(member.clientId, local);
        }
        StableAssignment snapshot = new StableAssignment(Map.copyOf(observed));
        if (observed.size() != count || observed.values().stream().map(MemberState::generation).distinct().count() != 1
            || snapshot.partitions().size() != expected.size() || !Set.copyOf(snapshot.partitions()).equals(expected)) {
            return null;
        }
        return snapshot;
    }

    private List<Delivery> awaitDeliveries(Set<Position> expected) throws InterruptedException {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            checkMembers();
            List<Delivery> received = deliveries.stream().filter(value -> expected.contains(value.position())).toList();
            if (received.size() == expected.size()) {
                assertThat(received).extracting(Delivery::position).containsExactlyInAnyOrderElementsOf(expected);
                return received;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("두 partition의 record 수신 대기 실패: expected=" + expected + ", received=" + deliveries);
    }

    private void checkMembers() {
        members.forEach(member -> member.checkFailure());
    }

    private final class Member {
        private final String clientId;
        private final String topic;
        private final String group;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicReference<MemberState> state = new AtomicReference<>(new MemberState("", -1, Set.of()));
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread thread;
        private volatile KafkaConsumer<String, String> consumer;

        private Member(String clientId, String topic, String group) {
            this.clientId = clientId;
            this.topic = topic;
            this.group = group;
            thread = new Thread(this::poll, "partition-probe-" + clientId);
            thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        private void poll() {
            try {
                consumer = new KafkaConsumer<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                    ConsumerConfig.GROUP_ID_CONFIG, group,
                    ConsumerConfig.CLIENT_ID_CONFIG, clientId,
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                    ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false));
                consumer.subscribe(List.of(topic));
                while (running.get()) {
                    var records = consumer.poll(POLL_TIMEOUT);
                    ConsumerGroupMetadata metadata = consumer.groupMetadata();
                    state.set(new MemberState(metadata.memberId(), metadata.generationId(), Set.copyOf(consumer.assignment())));
                    if (!records.isEmpty()) {
                        List<Delivery> batch = new ArrayList<>();
                        records.forEach(record -> batch.add(new Delivery(clientId, metadata.memberId(),
                            new Position(record.topic(), record.partition(), record.offset()), record.key(), record.value())));
                        consumer.commitSync(Duration.ofSeconds(5));
                        deliveries.addAll(batch);
                    }
                }
            } catch (WakeupException e) {
                if (running.get()) {
                    failure.set(e);
                }
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                if (consumer != null) {
                    try {
                        consumer.close(CLOSE_TIMEOUT);
                    } catch (RuntimeException e) {
                        failure.compareAndSet(null, e);
                    }
                }
            }
        }

        void requestStop() {
            running.set(false);
            KafkaConsumer<String, String> current = consumer;
            if (current != null) {
                current.wakeup();
            }
        }

        void awaitStop() throws InterruptedException {
            thread.join(5_000L);
            assertThat(thread.isAlive()).as("consumer 전용 스레드 종료: %s", clientId).isFalse();
            checkFailure();
        }

        void checkFailure() {
            Throwable error = failure.get();
            if (error != null) {
                throw new AssertionError("consumer 실행 실패: " + clientId, error);
            }
        }
    }

    private record MemberState(String memberId, int generation, Set<TopicPartition> partitions) {}

    private record StableAssignment(Map<String, MemberState> members) {
        List<TopicPartition> partitions() {
            return members.values().stream().flatMap(value -> value.partitions().stream()).toList();
        }
    }

    private record Position(String topic, int partition, long offset) {
        static Position from(RecordMetadata metadata) {
            return new Position(metadata.topic(), metadata.partition(), metadata.offset());
        }

        TopicPartition topicPartition() {
            return new TopicPartition(topic, partition);
        }
    }

    private record Delivery(String clientId, String memberId, Position position, String key, String value) {}
}
