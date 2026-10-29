package com.loopers.infrastructure.order.messaging;

import com.loopers.domain.order.gateway.OrderEventPublisher;
import com.loopers.domain.order.vo.OrderConfirmed;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 결과를 기다리지 않고 콜백에서 기록만 함. 기다리면 broker 장애가 확정 응답 시간으로 번지고, timeout 은 실패가 아니라 미확인이라 (6주차 D-74).
 * 실패해도 다시 보내지 않음. 기록 · 재전송은 7주차 Outbox 가 맡음 (D-73)
 */
@Slf4j
@Component
public class KafkaOrderEventPublisher implements OrderEventPublisher {

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final String topic;

    public KafkaOrderEventPublisher(KafkaTemplate<Object, Object> kafkaTemplate, @Value("${order-event.topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void publish(OrderConfirmed event) {
        try {
            // key 는 주문 단위로 같은 partition 에 모음 (설계 2)
            kafkaTemplate.send(topic, String.valueOf(event.orderId()), OrderConfirmedMessage.from(event))
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        log.error("주문 확정 이벤트 발행 실패. eventId={} orderId={}", event.eventId(), event.orderId(), failure);
                        return;
                    }
                    RecordMetadata metadata = result.getRecordMetadata();
                    log.info("주문 확정 이벤트 발행. eventId={} orderId={} topic={} partition={} offset={}",
                        event.eventId(), event.orderId(), metadata.topic(), metadata.partition(), metadata.offset());
                });
        } catch (RuntimeException e) {
            // 직렬화 실패 등 send 호출 자체의 오류도 확정 응답으로 번지지 않게 함
            log.error("주문 확정 이벤트 발행 실패. eventId={} orderId={}", event.eventId(), event.orderId(), e);
        }
    }
}
