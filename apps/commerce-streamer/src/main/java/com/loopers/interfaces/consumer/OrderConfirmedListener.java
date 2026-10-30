package com.loopers.interfaces.consumer;

import com.loopers.application.activity.PurchaseActivityHandler;
import com.loopers.config.PurchaseActivityListenerConfig;
import com.loopers.domain.activity.PurchaseReading;
import com.loopers.domain.activity.ReceivedRecord;
import com.loopers.domain.activity.RecordPosition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
class OrderConfirmedListener {

    private static final int RETRIES = 2;
    private static final Duration BACKOFF = Duration.ofSeconds(1);

    private final OrderEventPayloadReader reader;
    private final PurchaseActivityHandler handler;

    @KafkaListener(
        id = PurchaseActivityListenerConfig.LISTENER_ID,
        topics = "${kafka.topics.order-events}",
        groupId = "${kafka.groups.purchase-activity}",
        containerFactory = PurchaseActivityListenerConfig.FACTORY
    )
    void onMessage(ConsumerRecord<String, byte[]> record) throws InterruptedException {
        OrderEventReading reading = reader.read(record.key(), record.value());
        switch (reading) {
            case OrderEventReading.NotHandled notHandled -> {
            }
            case OrderEventReading.Confirmed confirmed -> handle(confirmed.reading(), received(record));
        }
    }

    private void handle(PurchaseReading reading, ReceivedRecord record) throws InterruptedException {
        for (int attempt = 0; ; attempt++) {
            try {
                handler.handle(reading, record);
                return;
            } catch (RuntimeException e) {
                if (attempt >= RETRIES || !TransientFailures.isTransient(e)) {
                    throw e;
                }
                log.warn("일시 오류로 주문 이벤트를 {} 뒤 다시 처리한다: attempt={}, position={}",
                    BACKOFF, attempt + 1, record.position(), e);
                Thread.sleep(BACKOFF.toMillis());
            }
        }
    }

    private static ReceivedRecord received(ConsumerRecord<String, byte[]> record) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
        }
        return new ReceivedRecord(new RecordPosition(record.topic(), record.partition(), record.offset()),
            record.key(), headers, Instant.ofEpochMilli(record.timestamp()), record.value());
    }
}
