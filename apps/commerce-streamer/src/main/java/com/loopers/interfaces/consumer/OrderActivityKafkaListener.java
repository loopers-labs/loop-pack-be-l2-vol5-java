package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.PurchaseActivityHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderActivityKafkaListener {
    public static final String LISTENER_ID = "orderPurchaseActivity";
    private final OrderConfirmedEventParser parser;
    private final PurchaseActivityHandler handler;

    @KafkaListener(id = LISTENER_ID, topics = "${commerce.purchase-activity.topic:order-events}",
        groupId = "${commerce.purchase-activity.group-id:purchase-activity}",
        containerFactory = "orderActivityKafkaListenerContainerFactory",
        autoStartup = "${commerce.purchase-activity.auto-startup:true}")
    public void listen(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        try {
            var event = parser.parseAndValidate(record.key(), record.value());
            var result = handler.consume(event);
            // The handler's separate database transaction has committed (or a duplicate was verified).
            ack.acknowledge();
            log.info("purchase_activity_completed topic={} partition={} offset={} orderId={} result={}",
                record.topic(), record.partition(), record.offset(), event.orderId(), result);
        } catch (RuntimeException failure) {
            // Never mark a failed record complete, or include the raw event/exception body in application logs.
            log.error("purchase_activity_processing_failed topic={} partition={} offset={} errorType={}",
                record.topic(), record.partition(), record.offset(), failure.getClass().getSimpleName());
            throw failure;
        }
    }
}
