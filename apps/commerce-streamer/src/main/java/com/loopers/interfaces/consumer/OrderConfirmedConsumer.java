package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.PurchaseActivityHandler;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class OrderConfirmedConsumer {
    private static final Logger log = LoggerFactory.getLogger(OrderConfirmedConsumer.class);
    private final OrderConfirmedParser parser;
    private final PurchaseActivityHandler handler;

    public OrderConfirmedConsumer(OrderConfirmedParser parser, PurchaseActivityHandler handler) {
        this.parser = parser;
        this.handler = handler;
    }

    @KafkaListener(
        id = "purchase-activity-listener",
        topics = "${order.confirmed-event.topic}",
        groupId = "${order.confirmed-event.group-id}",
        autoStartup = "${order.confirmed-event.auto-startup:true}",
        containerFactory = "orderActivityKafkaListenerContainerFactory")
    public void listen(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        try {
            handler.consume(parser.parseAndValidate(record.key(), record.value()));
        } catch (RuntimeException e) {
            log.error("구매 활동 소비에 실패했습니다. topic={}, partition={}, offset={}",
                record.topic(), record.partition(), record.offset(), e);
            throw e;
        }
        acknowledgment.acknowledge();
    }
}
