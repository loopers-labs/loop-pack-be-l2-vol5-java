package com.loopers.kafka;

import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.support.KafkaTestSupport;
import com.loopers.support.StreamerIntegrationTestSupport;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E-1 (W6 plan.md D-8, C-4) — partition 2개인 실험 topic에 같은 group의 consumer를 1 → 2 → 3개로 늘리며 실제 배정을 본다.
 * 각 partition은 group 안에서 한 member에게만 배정되고, 3개면 적어도 하나는 배정 없이 남는다 — consumer를 늘려도 처리 담당자는 partition 수를 넘지 않는다.
 * <p>
 * 배정은 consumer가 실제로 group에 참여해 poll하는 동안 일어난다. 각 member는 자기 스레드에서 poll하고, rebalance listener가 받은 배정을 기록한다.
 * 실험 record는 업무 topic과 분리한다 — 가짜 구매 활동을 만들지 않는다.
 */
class PartitionAssignmentExperimentTest extends StreamerIntegrationTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @Autowired
    @Qualifier(KafkaConfig.STRING_TEMPLATE)
    private KafkaTemplate<String, String> kafkaTemplate;

    @DisplayName("C-4 E-1 consumer 1개는 partition 0·1을 모두, 2개는 하나씩, 3개면 하나가 배정 없이 남는다. 명시한 partition의 record는 그 partition의 담당자가 읽는다.")
    @Test
    void assignmentAsMembersGrow() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String topic = "w6-assign-" + suffix;
        String group = "w6-assign-group-" + suffix;
        KafkaTestSupport.ensureTopic(topic, 2);
        List<Member> members = new ArrayList<>();
        List<String> table = new ArrayList<>();
        try {
            members.add(Member.start("m1", topic, group));
            await().atMost(WAIT).until(() -> stable(members, 2));
            assertThat(members.get(0).assigned()).containsExactly(0, 1);
            table.add("consumer 1개: " + describe(members));

            members.add(Member.start("m2", topic, group));
            await().atMost(WAIT).until(() -> stable(members, 2) && members.stream().allMatch(m -> m.assigned().size() == 1));
            table.add("consumer 2개: " + describe(members));

            members.add(Member.start("m3", topic, group));
            await().atMost(WAIT).until(() -> stable(members, 2) && members.stream().filter(m -> m.assigned().isEmpty()).count() == 1);
            table.add("consumer 3개: " + describe(members));

            // 명시한 두 partition에 실험 record를 보내고, 각 partition의 담당자가 읽는지 본다
            for (int partition = 0; partition < 2; partition++) {
                kafkaTemplate.send(new ProducerRecord<>(topic, partition, "probe", "probe-p" + partition)).get(10, TimeUnit.SECONDS);
            }
            for (int partition = 0; partition < 2; partition++) {
                int p = partition;
                Member owner = members.stream().filter(m -> m.assigned().contains(p)).findFirst().orElseThrow();
                await().atMost(WAIT).until(() -> owner.received().contains("probe-p" + p));
                assertThat(members.stream().filter(m -> m != owner).noneMatch(m -> m.received().contains("probe-p" + p))).isTrue();
            }
            Member idle = members.stream().filter(m -> m.assigned().isEmpty()).findFirst().orElseThrow();
            assertThat(idle.received()).isEmpty();
            table.add("배정 없는 member " + idle.name + "가 읽은 record: " + idle.received().size() + "건");
        } finally {
            members.forEach(Member::stop);
        }
        table.forEach(row -> System.out.println("[E-1] " + row));
    }

    @DisplayName("C-4 E-1 partition 수가 고정이면 같은 key(order-42)는 producer가 고른 같은 partition으로 간다 — 다른 key가 반드시 다른 partition으로 간다고 보지는 않는다.")
    @Test
    void sameKeyGoesToSamePartition() throws Exception {
        String topic = "w6-keymap-" + UUID.randomUUID().toString().substring(0, 8);
        KafkaTestSupport.ensureTopic(topic, 2);

        List<Integer> sameKey = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            RecordMetadata metadata = kafkaTemplate.send(new ProducerRecord<>(topic, "order-42", "v" + i)).get(10, TimeUnit.SECONDS).getRecordMetadata();
            sameKey.add(metadata.partition());
        }
        Map<String, Integer> others = new java.util.LinkedHashMap<>();
        for (String key : List.of("order-1", "order-2", "order-3", "order-4")) {
            others.put(key, kafkaTemplate.send(new ProducerRecord<>(topic, key, "x")).get(10, TimeUnit.SECONDS).getRecordMetadata().partition());
        }

        assertThat(Set.copyOf(sameKey)).hasSize(1);
        System.out.println("[E-1] key → partition: order-42 × 3 = " + sameKey + " | 다른 key = " + others);
    }

    private static boolean stable(List<Member> members, int partitions) {
        List<Integer> owned = members.stream().flatMap(m -> m.assigned().stream()).toList();
        return owned.size() == partitions && Set.copyOf(owned).size() == partitions;
    }

    private static String describe(List<Member> members) {
        return members.stream().map(m -> m.name + "=" + m.assigned()).collect(Collectors.joining(", "));
    }

    /** 자기 스레드에서 poll하는 group member 하나. KafkaConsumer는 스레드 안전하지 않아 다른 스레드에서는 wakeup만 부른다. */
    private static final class Member {

        private final String name;
        private final KafkaConsumer<String, String> consumer;
        private final Set<Integer> assigned = ConcurrentHashMap.newKeySet();
        private final List<String> received = new CopyOnWriteArrayList<>();
        private final Thread thread;

        private Member(String name, String topic, String group) {
            this.name = name;
            this.consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.CLIENT_ID_CONFIG, "w6-assign-" + name,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
            this.thread = new Thread(() -> run(topic), "w6-assign-" + name);
        }

        static Member start(String name, String topic, String group) {
            Member member = new Member(name, topic, group);
            member.thread.start();
            return member;
        }

        private void run(String topic) {
            try {
                consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
                    @Override
                    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                        partitions.forEach(tp -> assigned.remove(tp.partition()));
                    }

                    @Override
                    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                        partitions.forEach(tp -> assigned.add(tp.partition()));
                    }

                    @Override
                    public void onPartitionsLost(Collection<TopicPartition> partitions) {
                        onPartitionsRevoked(partitions);
                    }
                });
                while (true) {
                    for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(100))) {
                        received.add(record.value());
                    }
                }
            } catch (WakeupException ignored) {
                // stop
            } finally {
                consumer.close(Duration.ofSeconds(5));
            }
        }

        Set<Integer> assigned() {
            return new TreeSet<>(assigned);
        }

        List<String> received() {
            return received;
        }

        void stop() {
            consumer.wakeup();
            try {
                thread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
