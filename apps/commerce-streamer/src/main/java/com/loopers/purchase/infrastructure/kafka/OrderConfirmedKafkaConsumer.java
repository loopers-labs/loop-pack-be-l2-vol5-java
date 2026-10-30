package com.loopers.purchase.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.purchase.application.PurchaseActivityHandler;
import com.loopers.purchase.application.event.OrderConfirmedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OrderConfirmedKafkaConsumer {

    private final ObjectMapper objectMapper;
    private final PurchaseActivityHandler purchaseActivityHandler;

    public OrderConfirmedKafkaConsumer(
        ObjectMapper objectMapper,
        PurchaseActivityHandler purchaseActivityHandler
    ) {
        this.objectMapper = objectMapper;
        this.purchaseActivityHandler = purchaseActivityHandler;
    }

    @KafkaListener(
        id = "order-confirmed-consumer",
        topics = "${purchase-activity.kafka.topic}",
        groupId = "${purchase-activity.kafka.group-id}",
        containerFactory = OrderActivityKafkaConfiguration.LISTENER_CONTAINER_FACTORY,
        autoStartup = "${purchase-activity.kafka.listener.auto-startup:true}"
    )
    public void consume(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        OrderConfirmedEvent event = parse(record.value());
        if (event == null || event.orderId() == null || !event.orderId().toString().equals(record.key())) {
            throw new IllegalArgumentException("OrderConfirmed Kafka key must match orderId");
        }

        purchaseActivityHandler.consume(event);
        acknowledgment.acknowledge();
    }

    private OrderConfirmedEvent parse(byte[] payload) {
        try {
            return objectMapper.readValue(payload, OrderConfirmedEvent.class);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid OrderConfirmed JSON payload", exception);
        }
    }
}
