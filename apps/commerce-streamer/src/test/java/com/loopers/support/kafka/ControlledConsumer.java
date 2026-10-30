package com.loopers.support.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 테스트가 poll·commit 을 직접 지휘하는 consumer. commit 호출 횟수를 세고, 닫을 때 commit 하지 않는다. */
public final class ControlledConsumer implements AutoCloseable {

    private static final Duration DEADLINE = Duration.ofSeconds(60);

    private final KafkaConsumer<String, byte[]> consumer;
    private final ConsumerConfig config;
    private final String topic;
    private int commitCalls;

    ControlledConsumer(ExperimentTopic topic, String clientId, String offsetReset) {
        Map<String, Object> props = topic.consumerProps(clientId, offsetReset);
        this.consumer = new KafkaConsumer<>(props);
        this.config = new ConsumerConfig(props);
        this.topic = topic.name();
    }

    /** committed 위치부터 읽는다. seek 하지 않는다. */
    public void subscribe() {
        consumer.subscribe(List.of(topic));
    }

    /** 배정되는 순간(읽기 전에) 시작 위치 offset 을 commit 하고 그 위치로 이동한다. */
    public void subscribeStartingAt(TopicPartition partition, long offset) {
        consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                if (partitions.contains(partition)) {
                    commit(partition, offset);
                    consumer.seek(partition, offset);
                }
            }
        });
    }

    public void commit(TopicPartition partition, long offset) {
        commitCalls++;
        consumer.commitSync(Map.of(partition, new OffsetAndMetadata(offset)));
    }

    public int commitCalls() {
        return commitCalls;
    }

    /** Kafka 가 실제로 읽는 {@code enable.auto.commit} 값. 켜져 있으면 닫을 때 몰래 commit 될 수 있다. */
    public boolean autoCommitEnabled() {
        return config.getBoolean(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG);
    }

    /** record 가 처음 도착할 때까지 poll 해 정확히 한 건을 돌려준다. 없거나 여러 건이면 실패한다. */
    public ConsumerRecord<String, byte[]> pollSingle() {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
            consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            if (records.size() == 1) {
                return records.get(0);
            }
            if (records.size() > 1) {
                throw new AssertionError("한 건을 기대했지만 " + records.size() + "건을 받았습니다.");
            }
        }
        throw new AssertionError("제한 시간 안에 record 를 받지 못했습니다.");
    }

    @Override
    public void close() {
        consumer.close(Duration.ofSeconds(5));
    }
}
