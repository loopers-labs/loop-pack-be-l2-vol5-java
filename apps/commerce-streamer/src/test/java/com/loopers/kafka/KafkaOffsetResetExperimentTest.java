package com.loopers.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.testcontainers.KafkaTestBroker;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 격리 Kafka에서 reset의 첫 시작 위치를 비교한다. 업무 설정·DB·Spring listener는 사용하지 않는다. */
class KafkaOffsetResetExperimentTest {
    private static KafkaTestBroker broker;

    @BeforeAll
    static void startBroker() {
        broker = new KafkaTestBroker();
    }

    @AfterAll
    static void stopBroker() {
        if (broker != null) {
            broker.close();
        }
    }

    @Test
    void newGroupsDifferForExistingRecordsAndBothReadLaterRecords() throws Exception {
        String topic = createTopic();
        TopicPartition partition = new TopicPartition(topic, 0);
        try (var producer = producer();
             var earliest = consumer("earliest-" + UUID.randomUUID(), "earliest");
             var latest = consumer("latest-" + UUID.randomUUID(), "latest")) {
            send(producer, topic, 0, "before-1", "before-2", "before-3");

            List<ConsumerRecord<String, String>> earliestBefore = initialize(earliest, partition);
            readUntil(earliest, earliestBefore, 3);
            assertRead(earliestBefore, 0, "before-1", "before-2", "before-3");

            List<ConsumerRecord<String, String>> latestBefore = initialize(latest, partition);
            long latestStart = latest.position(partition, Duration.ofSeconds(10));
            latest.poll(Duration.ofMillis(500)).forEach(latestBefore::add);
            assertThat(latestBefore).isEmpty();
            assertThat(latestStart).isEqualTo(3L);
            assertThat(earliest.committed(Set.of(partition)).get(partition)).isNull();
            assertThat(latest.committed(Set.of(partition)).get(partition)).isNull();

            // latest의 배정·reset 완료 뒤에 새 메시지를 보낸다.
            send(producer, topic, 3, "after-4", "after-5");

            List<ConsumerRecord<String, String>> earliestAfter = new ArrayList<>();
            List<ConsumerRecord<String, String>> latestAfter = new ArrayList<>();
            readUntil(earliest, earliestAfter, 2);
            readUntil(latest, latestAfter, 2);
            assertRead(earliestAfter, 3, "after-4", "after-5");
            assertRead(latestAfter, 3, "after-4", "after-5");
            assertThat(earliest.endOffsets(List.of(partition)).get(partition)).isEqualTo(5L);

            report(Map.of(
                "scenario", "new-groups-with-no-committed-offset",
                "earliestExisting", observed(earliestBefore),
                "latestExisting", observed(latestBefore),
                "latestStartPositionBeforeNewMessages", latestStart,
                "earliestNew", observed(earliestAfter),
                "latestNew", observed(latestAfter),
                "recordsStillInKafka", 5
            ));
        }
    }

    @Test
    void validCommittedOffsetTakesPrecedenceForBothResetPolicies() throws Exception {
        String topic = createTopic();
        String group = "resume-" + UUID.randomUUID();
        TopicPartition partition = new TopicPartition(topic, 0);
        try (var producer = producer()) {
            send(producer, topic, 0, "message-1", "message-2", "message-3", "message-4", "message-5");
        }
        // 비교 조건: 같은 group의 다음 읽을 위치를 3으로 저장한다. 이후 reader는 commit하지 않는다.
        try (var seed = consumer(group, "earliest")) {
            List<ConsumerRecord<String, String>> records = initialize(seed, partition);
            readUntil(seed, records, 5);
            seed.commitSync(Map.of(partition, new OffsetAndMetadata(3L)));
            assertThat(seed.committed(Set.of(partition)).get(partition).offset()).isEqualTo(3L);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", "same-group-with-valid-committed-offset");
        result.put("committedOffset", 3);
        for (String policy : List.of("earliest", "latest")) {
            try (var resumed = consumer(group, policy)) {
                List<ConsumerRecord<String, String>> records = initialize(resumed, partition);
                readUntil(resumed, records, 2);
                assertRead(records, 3, "message-4", "message-5");
                assertThat(resumed.committed(Set.of(partition)).get(partition).offset()).isEqualTo(3L);
                result.put(policy, observed(records));
            }
        }
        report(result);
    }

    private String createTopic() throws Exception {
        String topic = "week6-offset-reset-" + UUID.randomUUID();
        try (AdminClient admin = AdminClient.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
        return topic;
    }

    private KafkaProducer<String, String> producer() {
        return new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        ));
    }

    private KafkaConsumer<String, String> consumer(String group, String policy) {
        return new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, group,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, policy
        ));
    }

    private List<ConsumerRecord<String, String>> initialize(KafkaConsumer<String, String> consumer,
                                                           TopicPartition partition) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        consumer.subscribe(List.of(partition.topic()));
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (consumer.assignment().isEmpty() && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(200)).forEach(records::add);
        }
        assertThat(consumer.assignment()).containsExactly(partition);
        consumer.position(partition, Duration.ofSeconds(10));
        return records;
    }

    private void readUntil(KafkaConsumer<String, String> consumer, List<ConsumerRecord<String, String>> records,
                           int count) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (records.size() < count && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(200)).forEach(records::add);
        }
        assertThat(records).hasSize(count);
    }

    private void send(KafkaProducer<String, String> producer, String topic, long firstOffset, String... values)
        throws Exception {
        for (int index = 0; index < values.length; index++) {
            var metadata = producer.send(new ProducerRecord<>(topic, 0, "lab", values[index]))
                .get(10, TimeUnit.SECONDS);
            assertThat(metadata.offset()).isEqualTo(firstOffset + index);
        }
    }

    private void assertRead(List<ConsumerRecord<String, String>> records, long firstOffset, String... values) {
        assertThat(records).extracting(ConsumerRecord::value).containsExactly(values);
        assertThat(records).extracting(ConsumerRecord::offset).containsExactlyElementsOf(
            LongStream.range(firstOffset, firstOffset + values.length).boxed().toList());
    }

    private List<Map<String, Object>> observed(List<ConsumerRecord<String, String>> records) {
        return records.stream().map(record -> Map.<String, Object>of(
            "offset", record.offset(), "value", record.value())).toList();
    }

    private void report(Map<String, Object> result) throws Exception {
        System.out.println("OFFSET_RESET_EXPERIMENT " + new ObjectMapper().writeValueAsString(result));
    }
}
