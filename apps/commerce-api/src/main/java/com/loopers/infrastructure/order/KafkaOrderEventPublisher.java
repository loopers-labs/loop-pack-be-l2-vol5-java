package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderConfirmedEvent;
import com.loopers.application.order.OrderEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class KafkaOrderEventPublisher implements OrderEventPublisher {

    static final String TOPIC = "order-events";

    private final KafkaTemplate<Object, Object> kafkaTemplate;

    @Override
    public void publishOrderConfirmed(OrderConfirmedEvent event) {
        kafkaTemplate.send(TOPIC, String.valueOf(event.orderId()), event)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("OrderConfirmed 발행 실패: eventId={}, orderId={}, error={}",
                        event.eventId(), event.orderId(), ex.getMessage());
                } else {
                    var metadata = result.getRecordMetadata();
                    log.info("OrderConfirmed 발행 성공: eventId={}, orderId={}, topic={}, partition={}, offset={}",
                        event.eventId(), event.orderId(),
                        metadata.topic(), metadata.partition(), metadata.offset());
                }
            });
    }
}
