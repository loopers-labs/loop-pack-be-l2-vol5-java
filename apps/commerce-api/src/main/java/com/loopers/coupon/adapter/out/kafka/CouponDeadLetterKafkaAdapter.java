package com.loopers.coupon.adapter.out.kafka;

import com.loopers.coupon.application.port.out.CouponDeadLetterPort;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * group 없이 assign + seekToBeginning으로 읽는다 — committed offset을 남기지 않아 몇 번 읽어도 같다.
 * 다시 보내도 consumer가 UNIQUE로 거르므로(CPN-08) 어디까지 재처리했는지 따로 기록하지 않는다.
 */
@Component
public class CouponDeadLetterKafkaAdapter implements CouponDeadLetterPort {

    private static final Duration POLL = Duration.ofMillis(500);

    private final KafkaProperties kafkaProperties;
    private final String deadLetterTopic;

    public CouponDeadLetterKafkaAdapter(KafkaProperties kafkaProperties, @Value("${kafka.topics.coupon-issue}") String topic) {
        this.kafkaProperties = kafkaProperties;
        this.deadLetterTopic = topic + ".DLT";
    }

    @Override
    public List<DeadLetter> readAll() {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties());
        config.remove(ConsumerConfig.GROUP_ID_CONFIG);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            List<TopicPartition> partitions = new ArrayList<>();
            List<PartitionInfo> infos = consumer.partitionsFor(deadLetterTopic, Duration.ofSeconds(10));
            if (infos == null || infos.isEmpty()) {
                return List.of();
            }
            infos.forEach(info -> partitions.add(new TopicPartition(deadLetterTopic, info.partition())));
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            List<DeadLetter> letters = new ArrayList<>();
            while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
                for (ConsumerRecord<String, String> record : consumer.poll(POLL)) {
                    letters.add(new DeadLetter(record.key(), record.value()));
                }
            }
            return letters;
        }
    }
}
