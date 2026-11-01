package com.loopers.interfaces.consumer;

import com.loopers.testsupport.LocalKafkaTestSupport;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * C9: 업무 topic 과 분리한 실험 topic(partition 2)에서 같은 group 의 consumer 를 1→2→3 으로 늘리며 broker 가 본 안정된 배정을 관찰한다.
 * 업무 listener·DB 와 무관하며 실험 record 는 구매 활동을 만들지 않는다. 배정은 AdminClient 의 member 배정과 각 consumer 의
 * 실제 assignment 가 일치할 때만 안정으로 본다. partitioner 공식을 다시 계산하지 않고 producer metadata 를 비교한다.
 */
@DisplayName("C9: 같은 group 의 consumer 를 1→2→3 으로 늘리면 각 partition 은 한 member 에만 배정되고 3명이면 idle member 가 생긴다.")
class PartitionGroupAssignmentKafkaTest {

    private static final Duration DEADLINE = Duration.ofSeconds(30);

    private LocalKafkaTestSupport kafka;
    private KafkaProducer<String, String> producer;
    private final List<Member> members = new ArrayList<>();
    private String topic;
    private String group;

    @BeforeEach
    void setUp() {
        kafka = LocalKafkaTestSupport.connect();
        topic = kafka.createTopic("assignment-experiment");
        group = kafka.ownGroup("assignment-experiment");
        producer = new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers(),
            ProducerConfig.ACKS_CONFIG, "all",
            ProducerConfig.CLIENT_ID_CONFIG, "assignment-experiment-producer-" + kafka.runId()),
            new StringSerializer(), new StringSerializer());
    }

    /** consumer·producer 를 먼저 닫고 group 비활성 확인 뒤 소유 group·topic 을 지운다. 한 단계 실패에도 나머지를 시도한다. */
    @AfterEach
    void tearDown() {
        List<RuntimeException> failures = new ArrayList<>();
        for (Member member : members) {
            try {
                member.stop();
            } catch (RuntimeException failure) {
                failures.add(failure);
            }
        }
        try {
            producer.close(Duration.ofSeconds(10));
        } catch (RuntimeException failure) {
            failures.add(failure);
        }
        try {
            kafka.close();
        } catch (RuntimeException failure) {
            failures.add(failure);
        }
        if (!failures.isEmpty()) {
            RuntimeException first = failures.get(0);
            failures.stream().skip(1).forEach(first::addSuppressed);
            throw first;
        }
    }

    /** 자기 스레드에서만 consumer 를 쓰는 group member. 매 poll 뒤 실제 assignment 와 받은 record 를 공개한다. */
    private final class Member {
        private final KafkaConsumer<String, String> consumer;
        private final String clientId;
        private final Thread thread;
        private final ConcurrentLinkedQueue<ConsumerRecord<String, String>> received = new ConcurrentLinkedQueue<>();
        private volatile Set<TopicPartition> assignment = Set.of();
        private volatile boolean running = true;

        private Member() {
            Map<String, Object> config = kafka.consumerConfig(group);
            this.clientId = (String) config.get(ConsumerConfig.CLIENT_ID_CONFIG);
            this.consumer = new KafkaConsumer<>(config);
            this.thread = new Thread(this::run, "assignment-" + clientId);
            this.thread.start();
        }

        private void run() {
            try {
                consumer.subscribe(List.of(topic));
                while (running) {
                    consumer.poll(Duration.ofMillis(100)).forEach(received::add);
                    assignment = Set.copyOf(consumer.assignment());
                }
            } finally {
                consumer.close(Duration.ofSeconds(10));
            }
        }

        private void stop() {
            running = false;
            try {
                thread.join(DEADLINE.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            if (thread.isAlive()) {
                throw new IllegalStateException("consumer 스레드가 끝나지 않았습니다: " + clientId);
            }
        }
    }

    private static Set<Integer> partitionsOf(Set<TopicPartition> partitions) {
        return partitions.stream().map(TopicPartition::partition).collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    /**
     * broker 의 group 이 STABLE 이고 member 수가 맞으며, broker 가 각 member 에 준 배정과 그 consumer 의 실제 assignment 가 같고,
     * 두 partition 이 겹침 없이 모두 배정될 때까지 기다린다. 결과는 clientId → partition 표다.
     */
    private Map<String, Set<Integer>> awaitStableAssignment(int expectedMembers) {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerGroupDescription description = kafka.describeGroup(group);
            if (description.state() == ConsumerGroupState.STABLE && description.members().size() == expectedMembers) {
                Map<String, Set<Integer>> table = new TreeMap<>();
                Set<TopicPartition> union = new HashSet<>();
                boolean consistent = true;
                int assignedTotal = 0;
                for (MemberDescription member : description.members()) {
                    Set<TopicPartition> brokerView = member.assignment().topicPartitions();
                    Member local = members.stream().filter(m -> m.clientId.equals(member.clientId())).findFirst().orElse(null);
                    consistent &= local != null && local.assignment.equals(brokerView);
                    table.put(member.clientId(), partitionsOf(brokerView));
                    union.addAll(brokerView);
                    assignedTotal += brokerView.size();
                }
                if (consistent && union.size() == LocalKafkaTestSupport.PARTITIONS && assignedTotal == LocalKafkaTestSupport.PARTITIONS) {
                    return table;
                }
            }
            sleep();
        }
        throw new AssertionError("member " + expectedMembers + "명의 안정된 배정을 deadline 안에 관찰하지 못했습니다.");
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private RecordMetadata send(ProducerRecord<String, String> record) throws Exception {
        return producer.send(record).get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 해당 record 를 받은 member 의 clientId. 정확히 한 member 만 받아야 한다. */
    private String awaitReceiver(String key) {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            List<String> receivers = members.stream()
                .filter(member -> member.received.stream().anyMatch(record -> key.equals(record.key())))
                .map(member -> member.clientId).toList();
            if (!receivers.isEmpty()) {
                assertThat(receivers).as("한 member 만 받아야 한다: " + key).hasSize(1);
                return receivers.get(0);
            }
            sleep();
        }
        throw new AssertionError("record 를 deadline 안에 받지 못했습니다: " + key);
    }

    @DisplayName("1→2→3 member 배정표, 명시 partition record 의 수신 member, 같은 key 의 실제 전송 partition")
    @Test
    void assignmentAcrossMemberCounts() throws Exception {
        members.add(new Member());
        Map<String, Set<Integer>> one = awaitStableAssignment(1);
        members.add(new Member());
        Map<String, Set<Integer>> two = awaitStableAssignment(2);
        members.add(new Member());
        Map<String, Set<Integer>> three = awaitStableAssignment(3);

        RecordMetadata toZero = send(new ProducerRecord<>(topic, 0, "explicit-p0", "experiment"));
        RecordMetadata toOne = send(new ProducerRecord<>(topic, 1, "explicit-p1", "experiment"));
        String receiverZero = awaitReceiver("explicit-p0");
        String receiverOne = awaitReceiver("explicit-p1");
        List<Integer> keyA = new ArrayList<>();
        List<Integer> keyB = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            keyA.add(send(new ProducerRecord<>(topic, "order-1001", "experiment-" + i)).partition());
            keyB.add(send(new ProducerRecord<>(topic, "order-2002", "experiment-" + i)).partition());
        }
        System.out.println("[C9 ASSIGNMENT] topic=" + topic + " group=" + group);
        System.out.println("[C9 ASSIGNMENT] members=1 " + one);
        System.out.println("[C9 ASSIGNMENT] members=2 " + two);
        System.out.println("[C9 ASSIGNMENT] members=3 " + three);
        System.out.println("[C9 EXPLICIT] p0=" + toZero.partition() + "@" + toZero.offset() + "->" + receiverZero
            + " p1=" + toOne.partition() + "@" + toOne.offset() + "->" + receiverOne);
        System.out.println("[C9 KEY] order-1001=" + keyA + " order-2002=" + keyB);

        assertAll(
            () -> assertThat(one.values()).containsExactly(Set.of(0, 1)),
            () -> assertThat(two.values()).allSatisfy(partitions -> assertThat(partitions).hasSize(1)),
            () -> assertThat(three.values().stream().filter(Set::isEmpty).count()).isGreaterThanOrEqualTo(1),
            () -> assertThat(toZero.partition()).isZero(),
            () -> assertThat(toOne.partition()).isEqualTo(1),
            () -> assertThat(three.get(receiverZero)).contains(0),
            () -> assertThat(three.get(receiverOne)).contains(1),
            () -> assertThat(Set.copyOf(keyA)).hasSize(1),
            () -> assertThat(Set.copyOf(keyB)).hasSize(1)
        );
    }
}
