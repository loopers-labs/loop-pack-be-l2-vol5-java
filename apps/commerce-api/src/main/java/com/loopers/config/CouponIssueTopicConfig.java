package com.loopers.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 쿠폰 발급 요청 topic (D-8). 발행하는 쪽(api)이 만든다 — order-events와 같은 내구성(RF 3, min.insync 2).
 * key = userId라 partition에 고르게 퍼지고, streamer의 consumer 2개가 나눠 처리한다.
 */
@Configuration
public class CouponIssueTopicConfig {

    @Bean
    NewTopic couponIssueTopic(@Value("${kafka.topics.coupon-issue}") String topic) {
        return TopicBuilder.name(topic)
            .partitions(OrderEventsTopicConfig.PARTITIONS)
            .replicas(OrderEventsTopicConfig.REPLICATION_FACTOR)
            .config("min.insync.replicas", OrderEventsTopicConfig.MIN_INSYNC_REPLICAS)
            .build();
    }
}
