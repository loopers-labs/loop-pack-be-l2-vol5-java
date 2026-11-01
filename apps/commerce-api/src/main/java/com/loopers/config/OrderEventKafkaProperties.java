package com.loopers.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

/**
 * 주문 확정 이벤트 발행 설정(commerce.order-events). producerProperties 는 공통 spring.kafka producer 설정 위에 덮어쓰는
 * 이 발행 전용 값이며 공통 consumer 에는 적용하지 않는다. 테스트는 topic 만 실행별 이름으로 바꾼다.
 */
@ConfigurationProperties("commerce.order-events")
public record OrderEventKafkaProperties(
    @DefaultValue("order-events") String topic,
    Map<String, String> producerProperties,
    @DefaultValue("15") int physicalCloseTimeoutSeconds
) {

    public OrderEventKafkaProperties {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("commerce.order-events.topic 필수");
        }
        if (physicalCloseTimeoutSeconds < 0) {
            throw new IllegalArgumentException("commerce.order-events.physical-close-timeout-seconds 는 0 이상");
        }
        producerProperties = producerProperties == null ? Map.of() : Map.copyOf(producerProperties);
    }
}
