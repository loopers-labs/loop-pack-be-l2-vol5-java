package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.activity.OrderConfirmedEventDto;
import com.loopers.application.activity.PurchaseActivityHandler;
import com.loopers.config.PurchaseActivityKafkaConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class PurchaseActivityKafkaConsumer {

    private final PurchaseActivityHandler handler;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "order-events",
        groupId = "purchase-activity",
        containerFactory = PurchaseActivityKafkaConfig.RECORD_LISTENER
    )
    public void listen(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        try {
            OrderConfirmedEventDto event = objectMapper.readValue(record.value(), OrderConfirmedEventDto.class);
            handler.consume(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("OrderConfirmed 소비 실패: key={}, offset={}, error={}",
                record.key(), record.offset(), e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
