package com.loopers.infrastructure.order.event;

import com.loopers.application.order.event.OrderConfirmedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class OrderConfirmedKafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmedKafkaPublisher.class);

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final String topic;

    public OrderConfirmedKafkaPublisher(KafkaTemplate<Object, Object> kafkaTemplate,
                                       @Value("${order.confirmed-event.topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConfirmed(OrderConfirmedEvent event) {
        try {
            OrderConfirmedMessageV1 message = OrderConfirmedMessageV1.from(event);
            kafkaTemplate.send(topic, message.key(), message).whenComplete((result, failure) -> {
                if (failure != null) {
                    logFailure(event, failure);
                }
            });
        } catch (RuntimeException failure) {
            logFailure(event, failure);
        }
    }

    private void logFailure(OrderConfirmedEvent event, Throwable failure) {
        log.error("주문 확정 이벤트 전송에 실패했습니다. eventId={}, orderId={}",
            event.getEventId(), event.getOrderId(), failure);
    }
}
