package com.loopers.purchaseactivity.interfaces;

import com.loopers.purchaseactivity.application.OrderConfirmedParser;
import com.loopers.purchaseactivity.application.PurchaseActivityHandler;
import com.loopers.purchaseactivity.application.PurchaseActivityTransactions.RecordOutcome;
import com.loopers.purchaseactivity.config.PurchaseActivityKafkaConfiguration;

import java.util.Locale;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class OrderConfirmedListener {
    public static final String ID = "purchase-activity";
    private static final Logger log = LoggerFactory.getLogger(OrderConfirmedListener.class);

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
        RecordOutcome outcome;
        try {
            outcome = handler.consume(event);
        } catch (RuntimeException failure) {
            log.warn("step=purchase_activity orderId={} eventId={} outcome=failed partition={} offset={} errorType={}",
                    event.orderId(), event.eventId(), record.partition(), record.offset(), failure.getClass().getSimpleName());
            throw failure;
        }
        log.info("step=purchase_activity orderId={} eventId={} outcome={} partition={} offset={}",
                event.orderId(), event.eventId(), outcome.name().toLowerCase(Locale.ROOT), record.partition(), record.offset());
        acknowledgment.acknowledge();
    }
}
