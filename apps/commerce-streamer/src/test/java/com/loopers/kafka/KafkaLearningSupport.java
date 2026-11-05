package com.loopers.kafka;

import com.loopers.kafka.test.KafkaTestSupport;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;

// 학습 실험 전용 설정. 배정·재전달을 record 하나 단위로 관찰하려고 consumer가 poll마다 한 건만 받게 한다.
// topic·admin·마감 시간 같은 일반 도우미는 KafkaTestSupport를 쓴다.
public final class KafkaLearningSupport {

    private KafkaLearningSupport() {
    }

    public static KafkaProducer<String, String> producer(String bootstrapServers) {
        return new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        ));
    }

    public static Map<String, Object> consumerConfig(
        String bootstrapServers,
        String groupId,
        String clientId,
        String autoOffsetReset
    ) {
        Map<String, Object> config = KafkaTestSupport.consumerConfig(bootstrapServers, groupId);
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        return config;
    }

    public static KafkaConsumer<String, String> consumer(Map<String, Object> config) {
        return new KafkaConsumer<>(config);
    }
}
