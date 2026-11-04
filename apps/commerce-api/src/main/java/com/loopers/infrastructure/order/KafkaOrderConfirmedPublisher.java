package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderConfirmationPublisher;
import com.loopers.domain.order.OrderConfirmed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "commerce.order-events.enabled", havingValue = "true")
@Slf4j
public class KafkaOrderConfirmedPublisher implements OrderConfirmationPublisher {
    private final KafkaTemplate<Object, Object> kafka;
    private final String topic;

    public KafkaOrderConfirmedPublisher(KafkaTemplate<Object, Object> kafka,
                                       @Value("${commerce.order-events.topic:order-events}") String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    @Override
    public void publish(OrderConfirmed event) {
        kafka.send(topic, event.orderId().toString(), OrderConfirmedMessage.from(event))
            .whenComplete((result, failure) -> {
                if (failure != null) {
                    log.error("Order event publication failed: eventId={}, orderId={}, topic={}",
                        event.eventId(), event.orderId(), topic, failure);
                } else {
                    var metadata = result.getRecordMetadata();
                    log.info("Order event published: eventId={}, orderId={}, topic={}, partition={}, offset={}",
                        event.eventId(), event.orderId(), metadata.topic(), metadata.partition(), metadata.offset());
                }
            });
    }
}
