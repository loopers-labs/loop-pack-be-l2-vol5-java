package com.loopers.support.kafka;

import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class TopicRecords {

    private static final Duration READ_DEADLINE = Duration.ofSeconds(10);

    private TopicRecords() {
    }

    public static List<ConsumerRecord<String, byte[]>> all(String topic) {
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
            new StringDeserializer(), new ByteArrayDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                .map(info -> new TopicPartition(topic, info.partition()))
                .toList();
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
            long deadline = System.nanoTime() + READ_DEADLINE.toNanos();
            while (partitions.stream().anyMatch(partition -> consumer.position(partition) < end.get(partition))) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("broker 의 record 를 다 읽지 못했다: end=" + end);
                }
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        }
    }

    public static List<ConsumerRecord<String, byte[]>> during(String topic, Runnable action) {
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
            new StringDeserializer(), new ByteArrayDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                .map(info -> new TopicPartition(topic, info.partition()))
                .toList();
            Map<TopicPartition, Long> before = consumer.endOffsets(partitions);
            action.run();
            Map<TopicPartition, Long> after = consumer.endOffsets(partitions);
            consumer.assign(partitions);
            before.forEach(consumer::seek);
            List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
            long deadline = System.nanoTime() + READ_DEADLINE.toNanos();
            while (partitions.stream().anyMatch(partition -> consumer.position(partition) < after.get(partition))) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("broker 의 record 를 다 읽지 못했다: before=" + before + ", after=" + after);
                }
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        }
    }
}
