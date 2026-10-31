package com.loopers.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.testcontainers.KafkaTestBroker;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 subscribe·poll로 같은 group의 멤버를 1→2→3개 늘려 안정된 partition 배정을 관찰한다.
 * 멤버가 2개일 때 두 partition에 record를 하나씩 명시적으로 보내, 각 partition을 배정받은 멤버가 읽는지도 확인한다.
 * 실험 record는 격리 topic에만 쓴다.
 */
class KafkaConsumerCountExperimentTest {
    @Test
    void twoPartitionsAreSharedByOneTwoAndThreeConsumers() throws Exception {
        String topic = "week6-consumer-count-" + UUID.randomUUID();
        String group = "consumer-count-" + UUID.randomUUID();
        List<Map<String, Object>> phases = new ArrayList<>();
        try (KafkaTestBroker broker = new KafkaTestBroker();
             AdminClient admin = AdminClient.create(Map.of(
                 AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1))).all().get(10, TimeUnit.SECONDS);

            try (GroupMember first = new GroupMember("C1", topic, group, broker.bootstrapServers())) {
                Map<String, List<Integer>> one = awaitStable(admin, group, topic, first);
                assertThat(one.get("C1")).containsExactly(0, 1);
                phases.add(Map.of("consumerCount", 1, "assignments", one));

                try (GroupMember second = new GroupMember("C2", topic, group, broker.bootstrapServers())) {
                    Map<String, List<Integer>> two = awaitStable(admin, group, topic, first, second);
                    assertThat(two.values()).allSatisfy(partitions -> assertThat(partitions).hasSize(1));
                    phases.add(Map.of("consumerCount", 2, "assignments", two));

                    try (var producer = new KafkaProducer<String, String>(Map.of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers(),
                        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
                        for (int partition = 0; partition < 2; partition++) {
                            var sent = producer.send(new ProducerRecord<>(topic, partition, "probe", "to-p" + partition))
                                .get(10, TimeUnit.SECONDS);
                            assertThat(sent.partition()).isEqualTo(partition);
                        }
                    }
                    Map<String, List<String>> received = awaitReceived(two, first, second);
                    phases.add(Map.of("consumerCount", 2, "receivedByMember", received));

                    try (GroupMember third = new GroupMember("C3", topic, group, broker.bootstrapServers())) {
                        Map<String, List<Integer>> three = awaitStable(admin, group, topic, first, second, third);
                        assertThat(three.values().stream().map(List::size).toList())
                            .containsExactlyInAnyOrder(1, 1, 0);
                        phases.add(Map.of("consumerCount", 3, "assignments", three));
                    }
                }
            }
            System.out.println("CONSUMER_COUNT_EXPERIMENT " + new ObjectMapper().writeValueAsString(
                Map.of("partitionCount", 2, "sameGroup", true, "phases", phases)));
        }
    }

    private Map<String, List<Integer>> awaitStable(AdminClient admin, String group, String topic,
                                                  GroupMember... members) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Map<String, List<Integer>> last = Map.of();
        while (System.nanoTime() < deadline) {
            for (GroupMember member : members) {
                member.verifyHealthy();
            }
            try {
                ConsumerGroupDescription description = admin.describeConsumerGroups(List.of(group))
                    .describedGroups().get(group).get(5, TimeUnit.SECONDS);
                if (description.state() == ConsumerGroupState.STABLE && description.members().size() == members.length) {
                    Map<String, List<Integer>> snapshot = new TreeMap<>();
                    description.members().forEach(member -> snapshot.put(member.clientId(),
                        member.assignment().topicPartitions().stream().filter(partition -> partition.topic().equals(topic))
                            .map(partition -> partition.partition()).sorted().toList()));
                    last = snapshot;
                    List<Integer> all = snapshot.values().stream().flatMap(List::stream).sorted().toList();
                    boolean clientsAgree = true;
                    for (GroupMember member : members) {
                        clientsAgree &= member.partitions.get().equals(snapshot.get(member.name));
                    }
                    if (all.equals(List.of(0, 1)) && clientsAgree) {
                        assertThat(snapshot).hasSize(members.length);
                        return snapshot;
                    }
                }
            } catch (ExecutionException exception) {
                if (!(exception.getCause() instanceof GroupIdNotFoundException)) {
                    throw exception;
                }
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("안정된 group 배정을 확인하지 못했습니다: " + last);
    }

    /** 각 멤버가 배정받은 partition의 probe만, 그리고 정확히 하나씩 읽었는지 기다려 확인한다. */
    private Map<String, List<String>> awaitReceived(Map<String, List<Integer>> assignments, GroupMember... members)
        throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (true) {
            Map<String, List<String>> received = new TreeMap<>();
            boolean done = true;
            for (GroupMember member : members) {
                member.verifyHealthy();
                List<String> expected = assignments.get(member.name).stream().map(partition -> partition + ":to-p" + partition)
                    .toList();
                received.put(member.name, List.copyOf(member.received));
                done &= member.received.equals(expected);
            }
            if (done) {
                return received;
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("배정된 partition의 probe를 각 멤버가 읽지 못했습니다: " + received);
            }
            Thread.sleep(100);
        }
    }

    private static final class GroupMember implements AutoCloseable {
        private final String name;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicReference<List<Integer>> partitions = new AtomicReference<>(List.of());
        private final List<String> received = new CopyOnWriteArrayList<>();
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final KafkaConsumer<String, String> consumer;
        private final Future<?> task;

        private GroupMember(String name, String topic, String group, String bootstrapServers) {
            this.name = name;
            consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.CLIENT_ID_CONFIG, name,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                // 배정 직후 position이 정해지기 전에 보낸 probe도 놓치지 않도록 처음부터 읽는다. topic은 비어 있다.
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false
            ));
            task = worker.submit(() -> {
                try (consumer) {
                    consumer.subscribe(List.of(topic));
                    while (running.get()) {
                        consumer.poll(Duration.ofMillis(200))
                            .forEach(record -> received.add(record.partition() + ":" + record.value()));
                        partitions.set(consumer.assignment().stream().map(partition -> partition.partition())
                            .sorted().toList());
                    }
                } catch (WakeupException stop) {
                    if (running.get()) {
                        throw stop;
                    }
                }
            });
        }

        private void verifyHealthy() throws Exception {
            if (task.isDone()) {
                task.get();
                throw new IllegalStateException(name + " consumer가 예기치 않게 종료됐습니다.");
            }
        }

        @Override
        public void close() throws Exception {
            running.set(false);
            consumer.wakeup();
            try {
                task.get(15, TimeUnit.SECONDS);
            } finally {
                worker.shutdownNow();
            }
        }
    }
}
