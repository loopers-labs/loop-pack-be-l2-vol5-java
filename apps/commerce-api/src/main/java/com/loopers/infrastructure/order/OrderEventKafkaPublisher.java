package com.loopers.infrastructure.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.dto.OrderConfirmedInfo;
import com.loopers.application.order.event.OrderEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 주문 확정 이벤트의 Kafka 구현. 직렬화·key(orderId)·전송 결과 로그를 여기서 숨긴다. 공용 template 의 값 직렬화기가
 * bytes 를 그대로 보내므로 계약 JSON 은 여기서 앱의 {@link ObjectMapper} 로 만든다.
 *
 * <p>send 는 비동기이므로 성공한 record 의 위치와 실패 원인을 콜백에서 남긴다. 동기 예외도 호출자에게 던지지 않는다.
 */
@Slf4j
@Component
public class OrderEventKafkaPublisher implements OrderEventPublisher {

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public OrderEventKafkaPublisher(
        KafkaTemplate<Object, Object> kafkaTemplate,
        ObjectMapper objectMapper,
        @Value("${order-event.topic}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Override
    public void publish(OrderConfirmedInfo event) {
        try {
            byte[] value = objectMapper.writeValueAsBytes(OrderConfirmedMessage.from(event));
            kafkaTemplate.send(topic, String.valueOf(event.orderId()), value)
                .whenComplete((result, error) -> logResult(event, result == null ? null : result.getRecordMetadata(), error));
        } catch (JsonProcessingException | RuntimeException e) {
            log.error("주문 확정 이벤트 전송 요청 실패. eventId={}", event.eventId(), e);
        }
    }

    private void logResult(OrderConfirmedInfo event, RecordMetadata metadata, Throwable error) {
        if (error != null) {
            log.error("주문 확정 이벤트 전송 결과 실패. eventId={}", event.eventId(), error);
            return;
        }
        log.info("주문 확정 이벤트 전송 성공. eventId={}, topic={}, partition={}, offset={}",
            event.eventId(), metadata.topic(), metadata.partition(), metadata.offset());
    }
}
