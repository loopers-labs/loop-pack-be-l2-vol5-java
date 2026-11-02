package com.loopers.support;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** compose init 처럼 업무 topic 과 DLT 를 미리 만듦. DLT 는 같은 partition 번호로 받으므로 수를 맞춤 (6주차 D-89, D-95) */
@Configuration
public class OrderEventsTopicTestConfig {

    @Bean
    public NewTopic orderEventsTopic(@Value("${order-event.topic}") String name) {
        return new NewTopic(name, 3, (short) 1);
    }

    @Bean
    public NewTopic orderEventsDltTopic(@Value("${order-event.topic}") String name) {
        return new NewTopic(name + ".DLT", 3, (short) 1);
    }
}
