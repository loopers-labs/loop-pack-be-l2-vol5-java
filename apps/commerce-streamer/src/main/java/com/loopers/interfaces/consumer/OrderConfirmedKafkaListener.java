package com.loopers.interfaces.consumer;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "commerce.order-events.consumer", name = "enabled", havingValue = "true")
public class OrderConfirmedKafkaListener {
    public static final String ID = "order-confirmed-purchase-activity";
    private final OrderConfirmedRecordAdapter adapter;

    @KafkaListener(id = ID, topics = "${commerce.order-events.topic:order-events}", groupId = "purchase-activity",
        containerFactory = OrderConfirmedKafkaConfiguration.FACTORY,
        autoStartup = "${commerce.order-events.consumer.auto-startup:true}")
    public void listen(ConsumerRecord<String, byte[]> record) {
        adapter.handle(record);
    }
}
