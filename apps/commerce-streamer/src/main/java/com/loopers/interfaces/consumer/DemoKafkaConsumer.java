package com.loopers.interfaces.consumer;

import com.loopers.confg.kafka.KafkaConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

/** 기본은 등록된다. 종단 검증의 자식 실행처럼 demo topic 을 구독하면 안 되는 실행만 demo-kafka.enabled=false 로 끈다. */
@ConditionalOnProperty(prefix = "demo-kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
@Component
public class DemoKafkaConsumer {
    @KafkaListener(
        topics = {"${demo-kafka.test.topic-name}"},
        containerFactory = KafkaConfig.BATCH_LISTENER
    )
    public void demoListener(
        List<ConsumerRecord<Object,Object>> messages,
        Acknowledgment acknowledgment
    ){
        System.out.println(messages);
        acknowledgment.acknowledge();
    }
}
