package com.loopers.support.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
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
import java.util.UUID;

/**
 * partition 1개짜리 topic 을 읽는 테스트용 reader. 열린 시점의 끝부터 읽으므로 같은 topic 을 쓴 앞선 테스트의 record 가
 * 섞이지 않는다. group 에 속하지 않고 offset 을 commit 하지 않는다.
 */
public class TopicReader implements AutoCloseable {

    private static final Duration POLL = Duration.ofMillis(200);

    private final KafkaConsumer<String, byte[]> consumer;
    private final TopicPartition partition;
    private final long startOffset;

    public TopicReader(String bootstrapServers, String topic) {
        createTopic(bootstrapServers, topic);
        this.consumer = new KafkaConsumer<>(Map.<String, Object>of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG, "topic-reader-" + UUID.randomUUID(),
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class
        ));
        this.partition = new TopicPartition(topic, 0);
        consumer.assign(List.of(partition));
        this.startOffset = consumer.endOffsets(List.of(partition)).get(partition);
        consumer.seek(partition, startOffset);
    }

    private static void createTopic(String bootstrapServers, String topic) {
        try (AdminClient admin = AdminClient.create(Map.<String, Object>of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            if (!admin.listTopics().names().get().contains(topic)) {
                admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
            }
        } catch (Exception e) {
            throw new IllegalStateException("topic 을 만들지 못했습니다: " + topic, e);
        }
    }

    /** 기대한 개수가 모일 때까지 deadline 안에서 읽는다. 모자라면 읽은 만큼만 돌려준다. */
    public List<ConsumerRecord<String, byte[]>> awaitRecords(int expected, Duration timeout) {
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (records.size() < expected && System.nanoTime() < deadline) {
            consumer.poll(POLL).forEach(records::add);
        }
        return records;
    }

    /** reader 가 열린 뒤 topic 에 쓰인 record 수. */
    public long recordsSinceOpen() {
        return consumer.endOffsets(List.of(partition)).get(partition) - startOffset;
    }

    @Override
    public void close() {
        consumer.close(Duration.ofSeconds(2));
    }
}
