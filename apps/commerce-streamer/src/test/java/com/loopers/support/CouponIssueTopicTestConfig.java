package com.loopers.support;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** compose init 처럼 접수 topic 과 DLT 를 미리 만듦. DLT 는 같은 partition 번호로 받으므로 수를 맞춤 (8주차 D-136) */
@Configuration
public class CouponIssueTopicTestConfig {

    @Bean
    public NewTopic couponIssueTopic(@Value("${coupon-issue.topic}") String name) {
        return new NewTopic(name, 3, (short) 1);
    }

    @Bean
    public NewTopic couponIssueDltTopic(@Value("${coupon-issue.topic}") String name) {
        return new NewTopic(name + ".DLT", 3, (short) 1);
    }
}
