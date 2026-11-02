package com.loopers.support.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 테스트 broker 는 자동 topic 생성을 끄므로 compose init 처럼 미리 만듦 (6주차 D-89, D-95).
 * 확정이 일어나는 모든 테스트가 발행하므로 scan 대상으로 둠. 없으면 send 마다 max.block.ms 만큼 막힘
 */
@Configuration
public class OrderEventsTopicTestConfig {

    @Bean
    public NewTopic orderEventsTopic(@Value("${order-event.topic}") String name) {
        return new NewTopic(name, 3, (short) 1);
    }
}
