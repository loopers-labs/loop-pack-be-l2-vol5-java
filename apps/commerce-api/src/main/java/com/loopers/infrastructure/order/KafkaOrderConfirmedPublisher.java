package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderConfirmedPayload;
import com.loopers.application.order.OrderConfirmedPublisher;
import com.loopers.application.order.OrderConfirmedUnpublished;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * OrderConfirmed를 Kafka로 보낸다(docs/week6/decisions.md D-16, D-25). 발행 스레드에서만 불린다 — 고객 응답과 떼는 일은
 * application의 OrderConfirmedPublication이 이미 했다(D-17). 여기서는 자기 대기 시간에 상한(max.block.ms)을 두는 일만 맡는다.
 *
 * - key는 orderId의 문자열이다. payload의 orderId와 같다 — 같은 주문은 같은 partition으로 간다.
 * - 결과는 기다리지 않고 콜백으로 관찰한다(D-3). 성공이면 partition·offset, 실패면 orderId·eventId와 예외를 남긴다.
 *   실패를 아는 곳은 send를 호출한 이쪽뿐이다 — consumer는 "안 온 것"을 알 수 없다.
 * - 전송 실패는 미발행으로 기록하고 Counter(reason=send_failed)로 센다(D-26).
 */
@Slf4j
@Component
public class KafkaOrderConfirmedPublisher implements OrderConfirmedPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final OrderConfirmedUnpublished unpublished;

    public KafkaOrderConfirmedPublisher(
        @Qualifier(OrderEventKafkaConfig.KAFKA_TEMPLATE) KafkaTemplate<String, String> kafkaTemplate,
        @Value("${events.order-confirmed.topic:order-events}") String topic,
        OrderConfirmedUnpublished unpublished
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.unpublished = unpublished;
    }

    @Override
    public void publish(OrderConfirmedPayload payload) {
        String key = String.valueOf(payload.orderId());
        String value = OrderConfirmedJson.write(payload);
        kafkaTemplate.send(topic, key, value).whenComplete((result, failure) -> {
            if (failure != null) {
                unpublished.recordSendFailure(payload.orderId(), payload.eventId(), topic, failure);
                return;
            }
            logSent(payload, result);
        });
    }

    private static void logSent(OrderConfirmedPayload payload, SendResult<String, String> result) {
        log.info("주문 확정 이벤트 전송: orderId={}, eventId={}, topic={}, partition={}, offset={}",
            payload.orderId(), payload.eventId(), result.getRecordMetadata().topic(),
            result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
    }
}
