package com.loopers.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 주문 확정 이벤트 topic (W6 plan.md D-8, ADR-W6-04). broker의 auto.create.topics가 꺼져 있으므로 발행하는 쪽이 만든다.
 * 이미 있으면 KafkaAdmin이 그대로 둔다(partition 수만 늘릴 수 있고 줄이지 않는다).
 */
@Configuration
public class OrderEventsTopicConfig {

    public static final int PARTITIONS = 3;
    public static final short REPLICATION_FACTOR = 3;
    public static final String MIN_INSYNC_REPLICAS = "2";

    @Bean
    NewTopic orderEventsTopic(@Value("${kafka.topics.order-events}") String topic) {
        return TopicBuilder.name(topic)
            .partitions(PARTITIONS)
            .replicas(REPLICATION_FACTOR)
            .config("min.insync.replicas", MIN_INSYNC_REPLICAS)
            .build();
    }
}
