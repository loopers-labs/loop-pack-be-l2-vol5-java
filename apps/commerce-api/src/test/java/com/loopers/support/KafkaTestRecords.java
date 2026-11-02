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

    private static KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false
        ));
    }
}
