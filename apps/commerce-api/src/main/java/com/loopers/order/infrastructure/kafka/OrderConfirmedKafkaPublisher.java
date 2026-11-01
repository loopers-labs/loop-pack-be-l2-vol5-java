package com.loopers.order.infrastructure.kafka;

import com.loopers.order.application.event.OrderConfirmedEvent;
import com.loopers.order.infrastructure.kafka.contract.orderconfirmed.Item;
import com.loopers.order.infrastructure.kafka.contract.orderconfirmed.OrderConfirmedV1Schema;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

@Component
public class OrderConfirmedKafkaPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderConfirmedKafkaPublisher.class);

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final String topic;

    public OrderConfirmedKafkaPublisher(
        KafkaTemplate<Object, Object> kafkaTemplate,
        @Value("${order-confirmed.kafka.topic:order-events}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(OrderConfirmedEvent event) {
        try {
            OrderConfirmedV1Schema message = toContractMessage(event);
            kafkaTemplate.send(topic, event.orderId().toString(), message)
                .whenComplete((result, failure) -> logResult(event, result, failure));
        } catch (RuntimeException exception) {
            LOGGER.error("OrderConfirmed send failed: eventId={}", event.eventId(), exception);
        }
    }

    private OrderConfirmedV1Schema toContractMessage(OrderConfirmedEvent event) {
        OrderConfirmedV1Schema message = new OrderConfirmedV1Schema();
        message.setEventType("OrderConfirmed");
        message.setSchemaVersion(1L);
        message.setEventId(event.eventId());
        message.setOrderId(event.orderId());
        message.setConfirmedAt(event.confirmedAt().toString());
        List<Item> items = event.items().stream().map(item -> {
            Item contractItem = new Item();
            contractItem.setProductId(item.productId());
            contractItem.setQuantity((long) item.quantity());
            return contractItem;
        }).toList();
        message.setItems(items);
        return message;
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
