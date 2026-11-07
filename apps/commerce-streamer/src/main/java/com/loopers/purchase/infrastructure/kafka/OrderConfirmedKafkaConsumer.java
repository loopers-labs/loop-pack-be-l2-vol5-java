package com.loopers.purchase.infrastructure.kafka;

import com.loopers.purchase.application.PurchaseActivityHandler;
import com.loopers.purchase.application.event.OrderConfirmedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class OrderConfirmedKafkaConsumer {

    private final OrderConfirmedMessageParser messageParser;
    private final PurchaseActivityHandler purchaseActivityHandler;

    public OrderConfirmedKafkaConsumer(
        OrderConfirmedMessageParser messageParser,
        PurchaseActivityHandler purchaseActivityHandler
    ) {
        this.messageParser = messageParser;
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
        OrderConfirmedEvent event = messageParser.parse(record.value());
        purchaseActivityHandler.consume(event);
        acknowledgment.acknowledge();
    }
}
