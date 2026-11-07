package com.loopers.support;

import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 테스트 broker 의 record 를 직접 읽는다. group 없이 partition 을 assign 하므로 offset 을 commit 하지 않고, 다른 consumer group 에 영향이 없다.
 * 대기는 모두 유한하다.
 */
public final class KafkaTestRecords {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private KafkaTestRecords() {
    }

    /** topic·partition·offset 의 record 하나. 없으면 TIMEOUT 뒤 실패. */
    public static ConsumerRecord<String, String> readAt(String topic, int partition, long offset) {
        try (KafkaConsumer<String, String> consumer = consumer()) {
            TopicPartition tp = new TopicPartition(topic, partition);
            consumer.assign(List.of(tp));
            consumer.seek(tp, offset);
            Instant deadline = Instant.now().plus(TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    if (record.offset() == offset) {
                        return record;
                    }
                }
            }
            throw new AssertionError("record 없음 [topic = " + topic + ", partition = " + partition + ", offset = " + offset + "]");
        }
    }

    /** partition 별 log end offset (다음에 기록될 위치). */
    public static Map<TopicPartition, Long> endOffsets(String topic) {
        try (KafkaConsumer<String, String> consumer = consumer()) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic, TIMEOUT).stream()
                .map(PartitionInfo::partition)
                .map(partition -> new TopicPartition(topic, partition))
                .toList();
            return consumer.endOffsets(partitions, TIMEOUT);
        }
    }

    /** topic 의 지금 기록된 record 전부 (각 partition 의 처음부터 log end 까지). partition·offset 순. */
    public static List<ConsumerRecord<String, String>> readAll(String topic) {
        Map<TopicPartition, Long> end = endOffsets(topic);
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.assign(end.keySet());
            consumer.seekToBeginning(end.keySet());
            Instant deadline = Instant.now().plus(TIMEOUT);
            while (end.entrySet().stream().anyMatch(e -> consumer.position(e.getKey()) < e.getValue())) {
                if (Instant.now().isAfter(deadline)) {
                    throw new AssertionError("record 를 끝까지 읽지 못함 [topic = " + topic + ", end = " + end + "]");
                }
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    if (record.offset() < end.get(new TopicPartition(record.topic(), record.partition()))) {
                        records.add(record);
                    }
                }
            }
        }
        records.sort((a, b) -> a.partition() != b.partition() ? Integer.compare(a.partition(), b.partition()) : Long.compare(a.offset(), b.offset()));
        return records;
    }

    private static KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false
        ));
    }
}
