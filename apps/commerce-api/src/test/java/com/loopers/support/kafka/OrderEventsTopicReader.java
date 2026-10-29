package com.loopers.support.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 주문 이벤트 topic 에서 "행동 전후 사이에 생긴 record"만 읽음. 같은 topic 을 여러 테스트가 쓰므로 offset 으로 구간을 나눔.
 * send 는 비동기라 기준을 읽기 전과 끝을 읽기 전에 flush 해 대기 없이 판정함
 */
@Component
public class OrderEventsTopicReader {

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final String topic;

    public OrderEventsTopicReader(KafkaTemplate<Object, Object> kafkaTemplate, @Value("${order-event.topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public Map<TopicPartition, Long> endOffsets() {
        kafkaTemplate.flush();
        try (KafkaConsumer<String, String> consumer = consumer()) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                .map(info -> new TopicPartition(topic, info.partition()))
                .toList();
            return consumer.endOffsets(partitions);
        }
    }

    /** from 이후 지금 끝 offset 까지 쌓인 record. 끝까지 못 읽으면 실패시켜 무한히 기다리지 않음 */
    public List<ConsumerRecord<String, String>> recordsSince(Map<TopicPartition, Long> from) {
        kafkaTemplate.flush();
        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.assign(from.keySet());
            from.forEach(consumer::seek);
            Map<TopicPartition, Long> end = consumer.endOffsets(from.keySet());
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            Instant deadline = Instant.now().plusSeconds(10);
            while (end.entrySet().stream().anyMatch(e -> consumer.position(e.getKey()) < e.getValue())) {
                if (Instant.now().isAfter(deadline)) {
                    throw new AssertionError("끝 offset 까지 읽지 못함: " + end);
                }
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        }
    }

    private static KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getProperty("spring.kafka.bootstrap-servers"),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false
        ));
    }
}
