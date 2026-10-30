package com.loopers.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 구매 활동 listener 설정(commerce.order-activity, 소비 설계 6절). 기본값은 application.yml 에 두고,
 * listener 의 topic·group·autoStartup placeholder 와 같은 키를 읽는다. 테스트는 실행별 topic·group 과 autoStartup=false 를 주입한다.
 */
@ConfigurationProperties("commerce.order-activity")
public record OrderActivityKafkaProperties(String topic, String groupId, boolean autoStartup) {

    public OrderActivityKafkaProperties {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("commerce.order-activity.topic 필수");
        }
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("commerce.order-activity.group-id 필수");
        }
    }
}
