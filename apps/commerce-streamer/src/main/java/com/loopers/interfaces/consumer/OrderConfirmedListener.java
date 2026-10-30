package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.PurchaseActivityHandler;
import com.loopers.config.PurchaseActivityListenerConfig;
import com.loopers.domain.purchase.ReceivedRecord;
import com.loopers.domain.purchase.RecordPosition;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
class OrderConfirmedListener {

    private final OrderEventPayloadReader reader;
    private final PurchaseActivityHandler handler;
    private final RetryTemplate retry;

    OrderConfirmedListener(OrderEventPayloadReader reader, PurchaseActivityHandler handler,
                           @Qualifier(PurchaseActivityListenerConfig.RETRY) RetryTemplate retry) {
        this.reader = reader;
        this.handler = handler;
        this.retry = retry;
    }

    @KafkaListener(
        id = PurchaseActivityListenerConfig.LISTENER_ID,
        topics = "${kafka.topics.order-events}",
        groupId = "${kafka.groups.purchase-activity}",
        containerFactory = PurchaseActivityListenerConfig.FACTORY
    )
    void onMessage(ConsumerRecord<String, byte[]> consumerRecord) {
        reader.read(consumerRecord.key(), consumerRecord.value())
            .ifPresent(reading -> retry.execute(context -> handler.handle(reading, received(consumerRecord))));
    }

    static ReceivedRecord received(ConsumerRecord<String, byte[]> consumerRecord) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : consumerRecord.headers()) {
            headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
        }
        return new ReceivedRecord(new RecordPosition(consumerRecord.topic(), consumerRecord.partition(), consumerRecord.offset()),
            consumerRecord.key(), headers, Instant.ofEpochMilli(consumerRecord.timestamp()), consumerRecord.value());
    }
}
