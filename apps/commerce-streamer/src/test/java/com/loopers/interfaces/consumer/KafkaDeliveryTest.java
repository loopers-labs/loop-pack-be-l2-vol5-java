package com.loopers.interfaces.consumer;

import com.loopers.application.activity.PurchaseActivityFacade;
import com.loopers.domain.activity.ProductPurchaseActivityModel;
import com.loopers.infrastructure.activity.ProcessedOrderConfirmationJpaRepository;
import com.loopers.infrastructure.activity.ProductPurchaseActivityJpaRepository;
import com.loopers.support.KafkaTestSupport;
import com.loopers.support.OrderConfirmedJson;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static com.loopers.support.KafkaTestSupport.await;
import static com.loopers.support.KafkaTestSupport.committedOffset;
import static com.loopers.support.KafkaTestSupport.createTopic;
import static com.loopers.support.KafkaTestSupport.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 실제 broker 로 Kafka 의 분산·진행 위치를 관찰한다. 단일 broker(Testcontainers, replica 1)라 복제·broker 장애는 다루지 않는다.
 * 각 실험은 고유 topic·group 을 만들어 업무 topic(order-events)과 섞지 않는다. 대기는 모두 유한하다(KafkaTestSupport.TIMEOUT).
 * 실행: ./gradlew :apps:commerce-streamer:test --tests '*KafkaDeliveryTest' (Docker 필요).
 */
@SpringBootTest(properties = "kafka.consumers.purchase-activity.auto-startup=false")
class KafkaDeliveryTest {
    private static final Logger log = LoggerFactory.getLogger(KafkaDeliveryTest.class);

    @Autowired
    private OrderConfirmedMessageParser parser;
    @Autowired
    private PurchaseActivityFacade purchaseActivityFacade;
    @Autowired
    private ProcessedOrderConfirmationJpaRepository processedRepository;
    @Autowired
    private ProductPurchaseActivityJpaRepository activityRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final List<GroupMember> members = new ArrayList<>();

    @AfterEach
    void tearDown() {
        members.forEach(GroupMember::close);
        databaseCleanUp.truncateAllTables();
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Map<String, Object> consumerProps(String groupId, Class<?> valueDeserializer) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestSupport.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, valueDeserializer);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return props;
    }

    /**
     * 같은 group 의 member 하나. 자기 스레드에서 poll 을 계속 불러 실제로 group 에 참여한다 (객체만 만들어서는 배정되지 않는다).
     * 배정은 rebalance listener 가 알려 준 최신 값이다. offset 은 commit 하지 않는다.
     */
    private static final class GroupMember {
        private final String name;
        private final AtomicReference<Set<TopicPartition>> assignment = new AtomicReference<>();
        private final List<ConsumerRecord<String, String>> received = new CopyOnWriteArrayList<>();
        private final Thread thread;
        private volatile boolean running = true;

        GroupMember(String name, String topic, String groupId) {
            this.name = name;
            this.thread = new Thread(() -> {
                KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps(groupId, StringDeserializer.class));
                try {
                    consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
                        @Override
                        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                            assignment.set(null);
                        }

                        @Override
                        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                            assignment.set(Set.copyOf(partitions));
                        }
                    });
                    while (running) {
                        consumer.poll(Duration.ofMillis(100)).forEach(received::add);
                    }
                } finally {
                    consumer.close(Duration.ofSeconds(5));
                }
            }, name);
            this.thread.start();
        }

        Set<TopicPartition> assignment() {
            return assignment.get();
        }

        void close() {
            running = false;
            try {
                thread.join(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 모든 member 가 배정을 받았고, 각 partition 이 정확히 한 member 에게 있는 안정 상태인지. */
    private boolean stablyAssigned(List<GroupMember> group, Set<TopicPartition> partitions) {
        List<Set<TopicPartition>> assignments = group.stream().map(GroupMember::assignment).toList();
        if (assignments.stream().anyMatch(a -> a == null)) {
            return false;
        }
        int total = assignments.stream().mapToInt(Set::size).sum();
        Set<TopicPartition> union = assignments.stream().flatMap(Set::stream).collect(Collectors.toSet());
        return total == partitions.size() && union.equals(partitions);
    }

    private Map<String, List<Integer>> table(List<GroupMember> group) {
        Map<String, List<Integer>> table = new LinkedHashMap<>();
        group.forEach(member -> table.put(member.name,
            member.assignment().stream().map(TopicPartition::partition).sorted().toList()));
        return table;
    }

    @DisplayName("partition 2개 topic, 같은 group 의 consumer 1→2→3: 각 partition 은 한 member 에게만, 3개일 때 하나는 배정 없음. 두 partition 에 보낸 record 는 담당 member 가 읽는다.")
    @Test
    void assignmentWithinGroup() {
        String topic = unique("w6-assignment");
        String group = unique("w6-assignment-group");
        createTopic(topic, 2);
        Set<TopicPartition> partitions = Set.of(new TopicPartition(topic, 0), new TopicPartition(topic, 1));

        members.add(new GroupMember("c1", topic, group));
        await("consumer 1개 안정 배정", () -> stablyAssigned(members, partitions));
        Map<String, List<Integer>> one = table(members);
        assertThat(one.get("c1")).containsExactly(0, 1);

        members.add(new GroupMember("c2", topic, group));
        await("consumer 2개 안정 배정", () -> stablyAssigned(members, partitions));
        Map<String, List<Integer>> two = table(members);
        assertThat(two.values()).allSatisfy(assigned -> assertThat(assigned).hasSize(1));

        RecordMetadata toP0 = send(topic, 0, "p0-record", "to partition 0");
        RecordMetadata toP1 = send(topic, 1, "p1-record", "to partition 1");
        for (GroupMember member : members) {
            int owned = member.assignment().iterator().next().partition();
            RecordMetadata expected = owned == 0 ? toP0 : toP1;
            await(member.name + " 가 partition " + owned + " record 를 읽음", () -> member.received.stream()
                .anyMatch(r -> r.partition() == expected.partition() && r.offset() == expected.offset()));
            assertThat(member.received).allMatch(r -> r.partition() == owned);
        }

        members.add(new GroupMember("c3", topic, group));
        await("consumer 3개 안정 배정", () -> stablyAssigned(members, partitions));
        Map<String, List<Integer>> three = table(members);
        assertThat(three.values()).filteredOn(List::isEmpty).hasSizeGreaterThanOrEqualTo(1);

        log.info("[W6 관찰: 배정] topic={} (partition 2), group={} / 1개: {} / 2개: {} / 3개: {}", topic, group, one, two, three);
    }

    @DisplayName("key 를 주고 partition 은 producer 가 고르게 하면, 고정 partition 수에서 같은 key 는 매번 같은 partition 에 기록된다.")
    @Test
    void sameKeyGoesToSamePartition() {
        String topic = unique("w6-key");
        createTopic(topic, 2);
        Map<String, Set<Integer>> partitionsByKey = new LinkedHashMap<>();

        for (int round = 0; round < 3; round++) {
            for (String key : List.of("42", "43", "44", "45")) {
                RecordMetadata sent = send(topic, null, key, "round " + round);
                partitionsByKey.computeIfAbsent(key, k -> new HashSet<>()).add(sent.partition());
            }
        }

        assertThat(partitionsByKey.values()).allSatisfy(partitions -> assertThat(partitions).hasSize(1));
        log.info("[W6 관찰: key → partition] topic={} (partition 2), 3회 전송: {}", topic, partitionsByKey);
    }

    @DisplayName("[재전달] DB 효과를 commit 한 뒤 offset 을 commit 하지 않고 종료하면, 같은 group 의 새 consumer 가 같은 record(topic·partition·offset n)를 다시 받는다. 구매 효과는 그대로, committed offset 은 n → n → n+1.")
    @Test
    void redeliveryAfterDbCommitWithoutOffsetCommit() {
        String topic = unique("w6-redelivery");
        String group = unique("w6-redelivery-group");
        createTopic(topic, 1);
        RecordMetadata target = send(topic, null, "70", OrderConfirmedJson.of(70, 7, 2, 8, 1));
        TopicPartition tp = new TopicPartition(target.topic(), target.partition());
        long n = target.offset();
        List<String> deliveries = new ArrayList<>();
        List<Long> committedHistory = new ArrayList<>();

        Map<String, Object> props = consumerProps(group, ByteArrayDeserializer.class);
        assertThat(new ConsumerConfig(props).getBoolean(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isFalse();

        // 첫 consumer: 구독·배정 뒤 시작 위치 n 을 commit (테스트 준비), n 에서 읽어 실제 handler 로 DB commit. 완료 offset n+1 은 commit 하지 않는다.
        KafkaConsumer<String, byte[]> first = new KafkaConsumer<>(props);
        try {
            first.subscribe(List.of(topic));
            pollUntil(first, () -> !first.assignment().isEmpty());
            first.commitSync(Map.of(tp, new OffsetAndMetadata(n)));
            first.seek(tp, n);
            committedHistory.add(committedOffset(group, tp));

            ConsumerRecord<String, byte[]> record = pollRecordAt(first, tp, n);
            deliveries.add(record.topic() + "-" + record.partition() + "@" + record.offset());
            PurchaseActivityFacade.Result result = purchaseActivityFacade.consume(parser.parse(record.key(), record.value()));
            assertThat(result).isEqualTo(PurchaseActivityFacade.Result.RECORDED);
            assertThat(activityRepository.findByOrderIdOrderByProductIdAsc(70L)).hasSize(2);
            assertThat(first.position(tp)).isEqualTo(n + 1);
            committedHistory.add(committedOffset(group, tp));
        } finally {
            // auto commit false, 요청해 둔 commit·종료 시 commit 하는 callback 없음: close 가 n+1 을 commit 하지 않는다.
            first.close(Duration.ofSeconds(5));
        }
        assertThat(committedOffset(group, tp)).isEqualTo(n);

        // 새 consumer, 같은 group: seek·offset reset 없이 committed offset 에서 다시 읽는다.
        try (KafkaConsumer<String, byte[]> second = new KafkaConsumer<>(props)) {
            second.subscribe(List.of(topic));
            ConsumerRecord<String, byte[]> record = pollRecordAt(second, tp, n);
            deliveries.add(record.topic() + "-" + record.partition() + "@" + record.offset());
            PurchaseActivityFacade.Result result = purchaseActivityFacade.consume(parser.parse(record.key(), record.value()));
            assertThat(result).isEqualTo(PurchaseActivityFacade.Result.ALREADY_PROCESSED);
            second.commitSync(Map.of(tp, new OffsetAndMetadata(record.offset() + 1)));
            committedHistory.add(committedOffset(group, tp));
        }

        assertThat(deliveries).hasSize(2).containsOnly(target.topic() + "-" + target.partition() + "@" + n);
        assertThat(committedHistory).containsExactly(n, n, n + 1);
        assertThat(activityRepository.findByOrderIdOrderByProductIdAsc(70L))
            .extracting(ProductPurchaseActivityModel::getProductId, ProductPurchaseActivityModel::getQuantity)
            .containsExactly(tuple(7L, 2), tuple(8L, 1));
        assertThat(processedRepository.count()).isEqualTo(1);

        log.info("[W6 관찰: 재전달] target={}-{}@{}, 전달={}, committed offset 이력={}, 활동(order 70)={}, 완료 기록={}건",
            target.topic(), target.partition(), n, deliveries, committedHistory,
            activityRepository.findByOrderIdOrderByProductIdAsc(70L).stream().map(a -> a.getProductId() + "x" + a.getQuantity()).toList(),
            processedRepository.count());
    }

    private static void pollUntil(KafkaConsumer<String, byte[]> consumer, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(KafkaTestSupport.TIMEOUT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("시간 안에 배정되지 않음");
            }
            consumer.poll(Duration.ofMillis(100));
        }
    }

    /** 배정·poll 을 거쳐 tp 의 offset 위치 record 를 받는다. 다른 위치가 먼저 오면 실패 (재전달이 아님). */
    private static ConsumerRecord<String, byte[]> pollRecordAt(KafkaConsumer<String, byte[]> consumer, TopicPartition tp, long offset) {
        Instant deadline = Instant.now().plus(KafkaTestSupport.TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(100)).records(tp)) {
                assertThat(record.offset()).isEqualTo(offset);
                return record;
            }
        }
        throw new AssertionError("record 를 받지 못함 [" + tp + "@" + offset + "]");
    }
}
