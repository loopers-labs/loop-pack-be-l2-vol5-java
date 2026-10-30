package com.loopers.infrastructure.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.order.OrderConfirmed;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
class OrderEventKafkaPublisher {

    private static final String METRIC = "order.event.publish";
    private static final String TYPE = "OrderConfirmed";

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final String topic;

    OrderEventKafkaPublisher(KafkaTemplate<String, byte[]> kafkaTemplate, ObjectMapper objectMapper,
                             MeterRegistry meterRegistry, @Value("${kafka.topics.order-events}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.topic = topic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void publish(OrderConfirmed event) {
        String eventId = event.eventId();
        try {
            byte[] value = OrderConfirmedMessage.from(event).toBytes(objectMapper);
            kafkaTemplate.send(topic, String.valueOf(event.orderId()), value)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        published(eventId, result.getRecordMetadata());
                    } else {
                        failed(eventId, ex);
                    }
                });
        } catch (JsonProcessingException | RuntimeException e) {
            failed(eventId, e);
        }
    }

    private void published(String eventId, RecordMetadata metadata) {
        log.info("주문 이벤트를 보냈다: eventId={}, topic={}, partition={}, offset={}",
            eventId, metadata.topic(), metadata.partition(), metadata.offset());
        count("success", "none");
    }

    private void failed(String eventId, Throwable ex) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
        log.error("주문 이벤트를 보내지 못했다. eventId 로 다시 보내야 한다: eventId={}, topic={}", eventId, topic, ex);
        count("failure", cause.getClass().getSimpleName());
    }

    private void count(String result, String cause) {
        meterRegistry.counter(METRIC, "type", TYPE, "result", result, "cause", cause).increment();
    }
}
