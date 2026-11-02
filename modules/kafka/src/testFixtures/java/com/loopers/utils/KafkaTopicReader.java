package com.loopers.utils;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 테스트에서 topic의 record를 group 없이 직접 읽는다 (assign + seekToBeginning). committed offset을 남기지 않으므로
 * 애플리케이션 consumer group의 위치에 영향을 주지 않는다.
 */
public final class KafkaTopicReader {

    private KafkaTopicReader() {}

    /**
     * topic 전체를 처음부터 읽어 조건에 맞는 record를 모은다. 기대 개수에 이르거나 시간이 다하면 돌려준다.
     */
    public static List<ConsumerRecord<String, String>> read(
        String bootstrapServers, String topic, Predicate<ConsumerRecord<String, String>> filter, int expected, Duration timeout
    ) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
            List<TopicPartition> partitions = new ArrayList<>();
            for (PartitionInfo info : consumer.partitionsFor(topic, Duration.ofSeconds(10))) {
                partitions.add(new TopicPartition(topic, info.partition()));
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            List<ConsumerRecord<String, String>> found = new ArrayList<>();
            long deadline = System.nanoTime() + timeout.toNanos();
            while (found.size() < expected && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    if (filter.test(record)) {
                        found.add(record);
                    }
                }
            }
            return found;
        }
    }

    /**
     * api·streamer 테스트가 함께 쓰는 OrderConfirmed v1 견본 (W6 D-9). 두 쪽이 같은 계약을 보고 있는지 이 파일이 묶는다.
     */
    public static String orderConfirmedFixture() {
        try (InputStream in = KafkaTopicReader.class.getResourceAsStream("/fixtures/order-confirmed-v1.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
