package com.loopers.support.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 테스트 broker 는 자동 topic 생성을 끄므로 compose init 처럼 미리 만듦 (8주차 D-136) */
@Configuration
public class CouponIssueTopicTestConfig {

    @Bean
    public NewTopic couponIssueTopic(@Value("${coupon-issue.topic}") String name) {
        return new NewTopic(name, 3, (short) 1);
    }
}
