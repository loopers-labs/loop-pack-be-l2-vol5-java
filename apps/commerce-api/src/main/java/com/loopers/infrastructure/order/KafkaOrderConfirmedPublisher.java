package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderConfirmedEvent;
import com.loopers.application.order.OrderConfirmedPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * OrderConfirmed 를 order-events 로 비동기 전송한다. key = orderId 문자열 (같은 주문은 같은 partition).
 * broker 확인(acks=all)·전송 재시도는 producer 가 한다 (kafka.yml). 여기서는 최종 결과만 관찰한다:
 * 성공은 topic·partition·offset 을, 최종 실패는 예외를 로그로 남기고 order.confirmed.publish{result} 를 센다.
 */
@Slf4j
@Component
public class KafkaOrderConfirmedPublisher implements OrderConfirmedPublisher {
    static final String METRIC = "order.confirmed.publish";

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;
    private final String topic;

    public KafkaOrderConfirmedPublisher(
        KafkaTemplate<Object, Object> kafkaTemplate,
        MeterRegistry meterRegistry,
        @Value("${kafka.topics.order-events.name}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
        this.topic = topic;
    }

    @Override
    public CompletableFuture<Published> publish(OrderConfirmedEvent event) {
        OrderConfirmedMessage message = OrderConfirmedMessage.from(event);
        return kafkaTemplate.send(topic, String.valueOf(event.orderId()), message)
            .thenApply(result -> {
                RecordMetadata metadata = result.getRecordMetadata();
                return new Published(metadata.topic(), metadata.partition(), metadata.offset());
            })
            .whenComplete((published, e) -> {
                if (e != null) {
                    meterRegistry.counter(METRIC, "result", "failure").increment();
                    log.error("OrderConfirmed 발행 실패 [eventId = {}, orderId = {}, topic = {}]", event.eventId(), event.orderId(), topic, e);
                    return;
                }
                meterRegistry.counter(METRIC, "result", "success").increment();
                log.info("OrderConfirmed 발행 [eventId = {}, orderId = {}, topic = {}, partition = {}, offset = {}]",
                    event.eventId(), event.orderId(), published.topic(), published.partition(), published.offset());
            });
    }
}
