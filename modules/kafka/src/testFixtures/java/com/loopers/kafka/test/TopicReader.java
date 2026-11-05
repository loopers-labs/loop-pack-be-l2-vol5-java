package com.loopers.kafka.test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;

import static com.loopers.kafka.test.KafkaTestSupport.DEADLINE;
import static com.loopers.kafka.test.KafkaTestSupport.POLL_TIMEOUT;

// 단일 partition 테스트 topic을 고유 group consumer로 읽는다. 다른 테스트가 남긴 record와 섞이지 않게
// 장면 시작 전 끝 위치(mark)부터 읽는다. 마감 시간을 넘기면 그때까지 읽은 record 요약을 실패 메시지에 담는다.
public final class TopicReader implements AutoCloseable {
    private final TopicPartition partition;
    private final KafkaConsumer<String, String> consumer;
    private final Function<ConsumerRecord<String, String>, String> summary;

    public TopicReader(String bootstrapServers, String topic) {
        this(bootstrapServers, topic, record -> "offset=" + record.offset() + ",key=" + record.key());
    }

    public TopicReader(String bootstrapServers, String topic, Function<ConsumerRecord<String, String>, String> summary) {
        KafkaTestSupport.createTopicIfAbsent(bootstrapServers, topic, 1);
        this.partition = new TopicPartition(topic, 0);
        this.consumer = new KafkaConsumer<>(KafkaTestSupport.consumerConfig(bootstrapServers,
            KafkaTestSupport.uniqueName("topic-reader")));
        this.summary = summary;
        consumer.assign(List.of(partition));
    }

    public long mark() {
        return consumer.endOffsets(List.of(partition)).get(partition);
    }

    public List<ConsumerRecord<String, String>> readUntil(long from, Predicate<List<ConsumerRecord<String, String>>> done,
        String description) {
        consumer.seek(partition, from);
        List<ConsumerRecord<String, String>> records = new ArrayList<ConsumerRecord<String, String>>();
        Instant deadline = Instant.now().plus(DEADLINE);
        while (Instant.now().isBefore(deadline)) {
            consumer.poll(POLL_TIMEOUT).forEach(records::add);
            if (done.test(records)) {
                return records;
            }
        }
        throw new AssertionError(DEADLINE.toSeconds() + "초 안에 도달하지 못함: " + description + ", 읽은 record="
            + records.stream().map(summary).toList());
    }

    // 호출 시점의 끝 위치까지 읽는다. 보내는 쪽이 broker 확인을 받은 뒤라면 그 record가 모두 들어 있다.
    public List<ConsumerRecord<String, String>> readThrough(long from) {
        long end = mark();
        return readUntil(from, found -> found.size() >= end - from, "끝 위치 " + end + "까지 읽기");
    }

    @Override
    public void close() {
        consumer.close();
    }
}
