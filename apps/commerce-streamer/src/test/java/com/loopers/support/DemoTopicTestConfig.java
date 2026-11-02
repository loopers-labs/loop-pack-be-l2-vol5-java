package com.loopers.support;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 테스트 broker 는 자동 topic 생성을 끄므로 demo listener 가 구독할 topic 을 compose init 처럼 미리 만듦 (6주차 D-95).
 * KafkaAdmin 이 listener 컨테이너 시작 전에 NewTopic bean 을 만듦. 모든 테스트 컨텍스트에 필요해 scan 대상으로 둠
 */
@Configuration
public class DemoTopicTestConfig {

    @Bean
    public NewTopic demoTopic(@Value("${demo-kafka.test.topic-name}") String name) {
        return new NewTopic(name, 1, (short) 1);
    }
}
