package com.loopers.order.infrastructure.kafka;

import com.loopers.order.application.event.OrderConfirmedEvent;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class OrderConfirmedKafkaPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderConfirmedKafkaPublisher.class);

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final OrderConfirmedContract contract;

    public OrderConfirmedKafkaPublisher(
        KafkaTemplate<Object, Object> kafkaTemplate,
        OrderConfirmedContract contract
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.contract = contract;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(OrderConfirmedEvent event) {
        try {
            OrderConfirmedKafkaMessage message = OrderConfirmedKafkaMessage.from(event);
            kafkaTemplate.send(contract.topic(), event.orderId().toString(), message)
                .whenComplete((result, failure) -> logResult(event, result, failure));
        } catch (RuntimeException exception) {
            LOGGER.error("OrderConfirmed send failed: eventId={}", event.eventId(), exception);
        }
    }

    private void logResult(OrderConfirmedEvent event, SendResult<Object, Object> result, Throwable failure) {
        if (failure != null) {
            LOGGER.error("OrderConfirmed send failed: eventId={}", event.eventId(), failure);
            return;
        }
        RecordMetadata metadata = result.getRecordMetadata();
        LOGGER.info("OrderConfirmed sent: eventId={}, topic={}, partition={}, offset={}",
            event.eventId(), metadata.topic(), metadata.partition(), metadata.offset());
    }
}
