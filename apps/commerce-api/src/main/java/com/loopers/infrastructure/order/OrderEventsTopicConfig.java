package com.loopers.infrastructure.order;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * order-events topic. 이벤트를 쓰는 commerce-api 가 소유한다. 기동 시 KafkaAdmin 이 없으면 만들고, 있으면 그대로 둔다.
 * broker 자동 생성(partition 1)에 맡기지 않고 partition 수를 정해 둔다.
 */
@Configuration
public class OrderEventsTopicConfig {

    @Bean
    public NewTopic orderEventsTopic(
        @Value("${kafka.topics.order-events.name}") String name,
        @Value("${kafka.topics.order-events.partitions}") int partitions,
        @Value("${kafka.topics.order-events.replicas}") int replicas
    ) {
        return TopicBuilder.name(name).partitions(partitions).replicas(replicas).build();
    }
}
