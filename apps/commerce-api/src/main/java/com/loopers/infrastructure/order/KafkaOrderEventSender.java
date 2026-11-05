package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderEventSender;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Outbox 의 key·payload 문자열을 order-events 로 보낸다. 재시도는 producer 가 한다 (kafka.yml). 여기서는 최종 결과를 order.event.send{result} 로 센다.
 * 상태 저장·로그는 relay 가 한다.
 */
@Component
public class KafkaOrderEventSender implements OrderEventSender {
    static final String METRIC = "order.event.send";

    private final KafkaTemplate<String, String> orderEventKafkaTemplate;
    private final MeterRegistry meterRegistry;
    private final String topic;

    public KafkaOrderEventSender(
        KafkaTemplate<String, String> orderEventKafkaTemplate,
        MeterRegistry meterRegistry,
        @Value("${kafka.topics.order-events.name}") String topic
    ) {
        this.orderEventKafkaTemplate = orderEventKafkaTemplate;
        this.meterRegistry = meterRegistry;
        this.topic = topic;
    }

    @Override
    public CompletableFuture<Sent> send(String messageKey, String payload) {
        return orderEventKafkaTemplate.send(topic, messageKey, payload)
            .thenApply(result -> {
                RecordMetadata metadata = result.getRecordMetadata();
                return new Sent(metadata.topic(), metadata.partition(), metadata.offset());
            })
            .whenComplete((sent, e) -> meterRegistry.counter(METRIC, "result", e == null ? "success" : "failure").increment());
    }
}
