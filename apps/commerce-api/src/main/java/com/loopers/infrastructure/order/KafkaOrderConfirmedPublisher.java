package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderConfirmedPublisher;
import com.loopers.kafka.event.OrderConfirmed;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class KafkaOrderConfirmedPublisher implements OrderConfirmedPublisher {
    private final KafkaTemplate<String, OrderConfirmed> kafka;
    private final String topic;
    private final Duration publishTimeout;

    public KafkaOrderConfirmedPublisher(
            @Qualifier("orderConfirmedKafkaTemplate") KafkaTemplate<String, OrderConfirmed> kafka,
            @Value("${order-events.topic:order-events}") String topic,
            @Value("${order-events.publish-timeout:5s}") Duration publishTimeout) {
        this.kafka = kafka;
        this.topic = topic;
        this.publishTimeout = publishTimeout;
    }

    @Override
    public PublishedRecord publish(OrderConfirmed event) {
        try {
            var metadata = kafka.send(topic, Long.toString(event.orderId()), event)
                    .get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .getRecordMetadata();
            return new PublishedRecord(metadata.topic(), metadata.partition(), metadata.offset());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("주문 이벤트 발행 확인 중 중단되었습니다.", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("주문 이벤트의 Kafka 기록 여부를 확인하지 못했습니다.", exception);
        }
    }
}
