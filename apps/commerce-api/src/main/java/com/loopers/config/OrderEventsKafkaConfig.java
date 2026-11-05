package com.loopers.config;

import com.loopers.confg.kafka.KafkaConfig;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

// 주문 확정 이벤트 전용 producer(설계 27절). factory를 Bean으로 두어 컨텍스트 종료 때 Spring이 producer를 닫게 한다.
@Configuration
public class OrderEventsKafkaConfig {
    @Bean
    ProducerFactory<String, String> orderEventsProducerFactory(KafkaProperties kafkaProperties,
        @Value("${commerce.kafka.producer.max-block}") Duration maxBlock) {
        Map<String, Object> props = KafkaConfig.stringProducerProperties(kafkaProperties);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlock.toMillis());
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    KafkaTemplate<String, String> orderEventsKafkaTemplate(ProducerFactory<String, String> orderEventsProducerFactory) {
        return new KafkaTemplate<>(orderEventsProducerFactory);
    }

    // 로컬 단일 broker 기준 복제 1. partition 수는 나중에 늘리면 key 배치가 바뀌므로 처음부터 정한다([임시]).
    @Bean
    NewTopic orderEventsTopic(@Value("${commerce.kafka.topics.order-events}") String topic,
        @Value("${commerce.kafka.topics.order-events-partitions}") int partitions) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }

    @Bean
    NewTopic orderEventRepublishRequestsTopic(@Value("${commerce.kafka.topics.order-event-republish-requests}") String topic,
        @Value("${commerce.kafka.topics.order-event-republish-requests-partitions}") int partitions) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }
}
