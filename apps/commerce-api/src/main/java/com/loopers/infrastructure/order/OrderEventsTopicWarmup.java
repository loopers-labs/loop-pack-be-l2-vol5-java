package com.loopers.infrastructure.order;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.errors.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
class OrderEventsTopicWarmup implements ApplicationRunner {

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final String topic;

    OrderEventsTopicWarmup(KafkaTemplate<String, byte[]> kafkaTemplate,
                           @Value("${kafka.topics.order-events}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int partitions = kafkaTemplate.partitionsFor(topic).size();
            log.info("기동 시 주문 이벤트 topic metadata 를 받았다: topic={}, partitions={}", topic, partitions);
        } catch (TimeoutException e) {
            log.warn("기동 시 주문 이벤트 topic metadata 를 받지 못했다. 첫 발행 때 다시 받는다: topic={}", topic, e);
        }
    }
}
