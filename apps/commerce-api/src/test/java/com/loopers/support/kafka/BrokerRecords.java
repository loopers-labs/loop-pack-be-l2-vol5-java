package com.loopers.support.kafka;

import com.loopers.testcontainers.KafkaBrokerFixture;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 테스트 broker의 topic에 실제로 들어 있는 record를 처음부터 끝까지 읽는다. 발행기의 반환값이나 로그가 아니라 broker가 가진 것을 본다.
 * group offset을 commit하지 않고(자동 commit 끔, group 없이 assign) 읽으므로 다른 consumer에 영향을 주지 않는다.
 */
public final class BrokerRecords {

    public record Record(int partition, long offset, String key, String value) {
    }

    private static final Duration DEADLINE = Duration.ofSeconds(30);

    private BrokerRecords() {
    }

    public static List<Record> readAll(String topic) {
        Map<String, Object> props = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaBrokerFixture.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false
        );
        List<Record> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> partitions = new ArrayList<>();
            for (PartitionInfo info : consumer.partitionsFor(topic)) {
                partitions.add(new TopicPartition(topic, info.partition()));
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);

            long deadline = System.nanoTime() + DEADLINE.toNanos();
            while (!reachedEnd(consumer, partitions, end)) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("시한 안에 topic 끝까지 읽지 못했습니다: " + topic);
                }
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    records.add(new Record(record.partition(), record.offset(), record.key(), record.value()));
                }
            }
        }
        return records;
    }

    private static boolean reachedEnd(KafkaConsumer<String, String> consumer, List<TopicPartition> partitions, Map<TopicPartition, Long> end) {
        for (TopicPartition partition : partitions) {
            if (consumer.position(partition) < end.get(partition)) {
                return false;
            }
        }
        return true;
    }
}
