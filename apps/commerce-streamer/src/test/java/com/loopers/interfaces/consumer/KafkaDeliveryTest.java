package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.facade.PurchaseActivityFacade;
import com.loopers.domain.purchase.enums.PurchaseRecordResult;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.KafkaTopics;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 6주차 설계 7.1 · 7.3 의 실제 broker 실험. 배정 · offset 은 broker 가 알려 준 값으로만 판정하고,
 * 소비자 객체 수나 poll position 으로 성공을 주장하지 않음. 대기는 모두 유한함
 */
@SpringBootTest
class KafkaDeliveryTest {

    private static final Logger log = LoggerFactory.getLogger(KafkaDeliveryTest.class);

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private KafkaTopics kafkaTopics;

    @Autowired
    private OrderConfirmedParser parser;

    @Autowired
    private PurchaseActivityFacade purchaseActivityFacade;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final List<AssignmentWatcher> watchers = new ArrayList<>();

    @AfterEach
    void tearDown() throws InterruptedException {
        for (AssignmentWatcher watcher : watchers) {
            watcher.stop();
        }
        kafkaTopics.deleteCreated();
        databaseCleanUp.truncateAllTables();
    }

    private static String bootstrap() {
        return System.getProperty("spring.kafka.bootstrap-servers");
    }

    private static Map<String, Object> consumerConfig(String group) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        // 자동 commit 이 켜지면 poll · close 때 위치가 넘어가 "처리 뒤 미커밋" 상태를 만들 수 없음
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return config;
    }

    private static KafkaProducer<String, byte[]> producer() {
        return new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class
        ));
    }

    private static RecordMetadata send(KafkaProducer<String, byte[]> producer, ProducerRecord<String, byte[]> record) throws Exception {
        return producer.send(record).get(10, TimeUnit.SECONDS);
    }

    private static void waitUntil(String what, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as(what).isBefore(deadline);
            Thread.sleep(100);
        }
    }

    /** 같은 상태가 연속으로 관찰돼야 안정된 배정으로 봄. 시작 직후 한 번의 화면으로 끝났다고 판단하지 않음 */
    private static void waitUntilStable(String what, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        int consecutive = 0;
        while (consecutive < 10) {
            assertThat(Instant.now()).as(what).isBefore(deadline);
            consecutive = condition.getAsBoolean() ? consecutive + 1 : 0;
            Thread.sleep(100);
        }
    }

    /**
     * 자기 스레드에서만 KafkaConsumer 를 다룸(스레드 안전하지 않음). 실제 group 참여는 broker 가 준 assignment 로 확인함
     */
    private static final class AssignmentWatcher implements Runnable {
        private final String name;
        private final KafkaConsumer<String, byte[]> consumer;
        private final Thread thread;
        private volatile Set<TopicPartition> assignment = Set.of();
        private final List<ConsumerRecord<String, byte[]>> received = new CopyOnWriteArrayList<>();

        private AssignmentWatcher(String name, String group, String topic) {
            this.name = name;
            this.consumer = new KafkaConsumer<>(consumerConfig(group));
            this.consumer.subscribe(List.of(topic));
            this.thread = new Thread(this, name);
            this.thread.start();
        }

        @Override
        public void run() {
            try {
                while (true) {
                    consumer.poll(Duration.ofMillis(100)).forEach(received::add);
                    assignment = Set.copyOf(consumer.assignment());
                }
            } catch (WakeupException e) {
                // stop() 이 깨움
            } finally {
                consumer.close(Duration.ofSeconds(5));
            }
        }

        private void stop() throws InterruptedException {
            consumer.wakeup();
            thread.join(TimeUnit.SECONDS.toMillis(10));
        }
    }

    private AssignmentWatcher watch(String name, String group, String topic) {
        AssignmentWatcher watcher = new AssignmentWatcher(name, group, topic);
        watchers.add(watcher);
        return watcher;
    }

    private static String assignmentTable(List<AssignmentWatcher> members) {
        StringBuilder table = new StringBuilder();
        for (AssignmentWatcher member : members) {
            table.append(member.name).append('=').append(member.assignment.stream().map(TopicPartition::partition).sorted().toList()).append(' ');
        }
        return table.toString().trim();
    }

    /** 각 partition 이 정확히 한 member 에게만 배정됐는가 */
    private static boolean eachPartitionOwnedOnce(List<AssignmentWatcher> members, int partitions) {
        List<TopicPartition> owned = members.stream().flatMap(member -> member.assignment.stream()).toList();
        return owned.size() == partitions && new HashSet<>(owned).size() == partitions;
    }

    @DisplayName("partition 2개 · 같은 group 의 consumer 를 늘리면, ")
    @Nested
    class Assignment {
        @DisplayName("1개는 둘 다, 2개는 하나씩 맡고, 3개면 적어도 하나는 배정 없이 남는다. 명시한 두 partition 의 record 는 그 담당자가 읽는다.")
        @Test
        void assignsUpToPartitionCount() throws Exception {
            // arrange
            String topic = kafkaTopics.create("assignment", 2);
            String group = "assignment-" + UUID.randomUUID();
            List<AssignmentWatcher> members = new ArrayList<>();

            // act & assert: 1개
            members.add(watch("c1", group, topic));
            waitUntilStable("consumer 1개 배정", () -> members.get(0).assignment.size() == 2);
            log.info("[7.1 배정] consumer 1개: {}", assignmentTable(members));

            // 2개
            members.add(watch("c2", group, topic));
            waitUntilStable("consumer 2개 배정", () -> eachPartitionOwnedOnce(members, 2)
                && members.stream().allMatch(member -> member.assignment.size() == 1));
            log.info("[7.1 배정] consumer 2개: {}", assignmentTable(members));

            // 3개
            members.add(watch("c3", group, topic));
            waitUntilStable("consumer 3개 배정", () -> eachPartitionOwnedOnce(members, 2)
                && members.stream().anyMatch(member -> member.assignment.isEmpty()));
            log.info("[7.1 배정] consumer 3개: {}", assignmentTable(members));

            // 두 partition 에 명시적으로 보낸 record 를 각 담당자가 읽음
            try (KafkaProducer<String, byte[]> producer = producer()) {
                for (int partition = 0; partition < 2; partition++) {
                    int target = partition;
                    RecordMetadata sent = send(producer, new ProducerRecord<>(topic, partition, "p" + partition,
                        ("partition-" + partition).getBytes(StandardCharsets.UTF_8)));
                    AssignmentWatcher owner = members.stream()
                        .filter(member -> member.assignment.contains(new TopicPartition(topic, target)))
                        .findFirst().orElseThrow();
                    waitUntil("partition " + target + " 담당자가 읽음", () -> owner.received.stream()
                        .anyMatch(record -> record.partition() == target && record.offset() == sent.offset()));
                    log.info("[7.1 배정] partition {} offset {} → {}", target, sent.offset(), owner.name);
                }
            }
        }

        @DisplayName("partition 수가 고정이면 producer 가 고른 partition 은 같은 key 마다 같다. 다른 key 가 다른 partition 이라고는 주장하지 않는다.")
        @Test
        void mapsSameKeyToSamePartition() throws Exception {
            // arrange
            String topic = kafkaTopics.create("key-mapping", 3);
            Set<Integer> partitions = new HashSet<>();

            // act
            try (KafkaProducer<String, byte[]> producer = producer()) {
                for (int i = 0; i < 5; i++) {
                    RecordMetadata sent = send(producer, new ProducerRecord<>(topic, "42", ("v" + i).getBytes(StandardCharsets.UTF_8)));
                    partitions.add(sent.partition());
                }
            }

            // assert
            log.info("[7.1 key] key=42 → partition {}", partitions);
            assertThat(partitions).hasSize(1);
        }
    }

    @DisplayName("DB commit 뒤 offset 을 commit 하지 않고 consumer 를 닫으면, ")
    @Nested
    class Redelivery {
        private long activityCount(long orderId) {
            return transactionTemplate.execute(status -> ((Number) entityManager.createNativeQuery(
                "select count(*) from product_purchase_activity where order_id = " + orderId).getSingleResult()).longValue());
        }

        private static long committed(KafkaConsumer<String, byte[]> consumer, TopicPartition partition) {
            OffsetAndMetadata offset = consumer.committed(Set.of(partition), Duration.ofSeconds(10)).get(partition);
            return offset == null ? -1 : offset.offset();
        }

        /** 같은 group 으로 committed offset 을 읽음. 이 consumer 는 구독하지 않아 group 배정에 끼지 않음 */
        private long committedOf(String group, TopicPartition partition) {
            try (KafkaConsumer<String, byte[]> reader = new KafkaConsumer<>(consumerConfig(group))) {
                return committed(reader, partition);
            }
        }

        private static ConsumerRecord<String, byte[]> pollUntil(KafkaConsumer<String, byte[]> consumer, TopicPartition partition, long offset)
            throws InterruptedException {
            Instant deadline = Instant.now().plus(WAIT);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                    if (record.partition() == partition.partition() && record.offset() == offset) {
                        return record;
                    }
                }
            }
            throw new AssertionError(partition + "@" + offset + " 를 받지 못함");
        }

        @DisplayName("같은 group 의 새 consumer 가 같은 record 를 다시 받고, 구매 효과는 그대로이며 committed offset 은 n → n → n+1 이다.")
        @Test
        void redeliversSameRecord_afterDbCommitWithoutOffsetCommit() throws Exception {
            // arrange: target 을 보내고 broker 가 준 위치를 얻음
            String topic = kafkaTopics.create("redelivery", 3);
            String group = "purchase-activity-redelivery-" + UUID.randomUUID();
            long orderId = 7_001L;
            String value = """
                {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:%d","orderId":%d,
                 "confirmedAt":"2026-09-07T01:00:00.123456Z","items":[{"productId":7,"quantity":2},{"productId":8,"quantity":1}]}
                """.formatted(orderId, orderId);
            RecordMetadata target;
            try (KafkaProducer<String, byte[]> producer = producer()) {
                target = send(producer, new ProducerRecord<>(topic, String.valueOf(orderId), value.getBytes(StandardCharsets.UTF_8)));
            }
            TopicPartition partition = new TopicPartition(topic, target.partition());
            long n = target.offset();

            // 시작 위치: 첫 consumer 가 배정된 뒤 n 을 commit 하고 n 에서 읽음 (재전달이 아니라 준비)
            KafkaConsumer<String, byte[]> first = new KafkaConsumer<>(consumerConfig(group));
            first.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(WAIT);
            while (!first.assignment().contains(partition)) {
                assertThat(Instant.now()).as("첫 consumer 배정").isBefore(deadline);
                first.poll(Duration.ofMillis(100));
            }
            first.commitSync(Map.of(partition, new OffsetAndMetadata(n)));
            first.seek(partition, n);
            long startCommitted = committed(first, partition);

            // 첫 처리: 실제 parser · handler 로 DB commit 까지, offset 은 commit 하지 않음
            ConsumerRecord<String, byte[]> firstDelivery = pollUntil(first, partition, n);
            PurchaseRecordResult firstResult = purchaseActivityFacade.record(parser.parse(firstDelivery.key(), firstDelivery.value()));
            long afterDbCommit = committed(first, partition);
            long activityAfterFirst = activityCount(orderId);

            // 종료: auto commit 꺼짐, 대기 중 commit 요청 · 종료 commit callback 없이 유한하게 닫음
            first.close(Duration.ofSeconds(10));
            long afterClose = committedOf(group, partition);

            // 재시작: 새 consumer, 같은 group, seek 없이 저장된 group offset 에서 읽음
            ConsumerRecord<String, byte[]> secondDelivery;
            PurchaseRecordResult secondResult;
            long afterSecond;
            try (KafkaConsumer<String, byte[]> second = new KafkaConsumer<>(consumerConfig(group))) {
                second.subscribe(List.of(topic));
                secondDelivery = pollUntil(second, partition, n);
                secondResult = purchaseActivityFacade.record(parser.parse(secondDelivery.key(), secondDelivery.value()));
                second.commitSync(Map.of(partition, new OffsetAndMetadata(n + 1)));
                afterSecond = committed(second, partition);
            }

            // assert
            log.info("[7.3 재전달] target={}@{} 전달 1=({}, {}@{}) 전달 2=({}, {}@{}) committed {} → {} → {} → {} 활동 {} → {}",
                partition, n, firstResult, firstDelivery.partition(), firstDelivery.offset(),
                secondResult, secondDelivery.partition(), secondDelivery.offset(),
                startCommitted, afterDbCommit, afterClose, afterSecond, activityAfterFirst, activityCount(orderId));
            assertAll(
                () -> assertThat(List.of(firstDelivery.topic(), secondDelivery.topic())).containsOnly(topic),
                () -> assertThat(List.of(firstDelivery.partition(), secondDelivery.partition())).containsOnly(target.partition()),
                () -> assertThat(List.of(firstDelivery.offset(), secondDelivery.offset())).containsOnly(n),
                () -> assertThat(firstResult).isEqualTo(PurchaseRecordResult.RECORDED),
                () -> assertThat(secondResult).isEqualTo(PurchaseRecordResult.ALREADY_RECORDED),
                () -> assertThat(List.of(startCommitted, afterDbCommit, afterClose, afterSecond)).containsExactly(n, n, n, n + 1),
                () -> assertThat(activityAfterFirst).isEqualTo(2),
                () -> assertThat(activityCount(orderId)).isEqualTo(2)
            );
        }
    }
}
