package com.loopers.purchaseactivity.interfaces;

import com.loopers.purchaseactivity.application.OrderConfirmedParser;
import com.loopers.purchaseactivity.application.PurchaseActivityHandler;
import com.loopers.purchaseactivity.config.PurchaseActivityKafkaConfiguration;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class OrderConfirmedListener {
    public static final String ID = "purchase-activity";

    private final OrderConfirmedParser parser;
    private final PurchaseActivityHandler handler;

    public OrderConfirmedListener(OrderConfirmedParser parser, PurchaseActivityHandler handler) {
        this.parser = parser;
        this.handler = handler;
    }

    @KafkaListener(id = ID, topics = "${order-events.topic:order-events}",
            groupId = "${purchase-activity.group-id:purchase-activity}",
            containerFactory = PurchaseActivityKafkaConfiguration.FACTORY,
            autoStartup = "${purchase-activity.auto-startup:true}")
    public void listen(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        var event = parser.parseAndValidate(record.key(), record.value());
        handler.consume(event);
        acknowledgment.acknowledge();
    }
}
