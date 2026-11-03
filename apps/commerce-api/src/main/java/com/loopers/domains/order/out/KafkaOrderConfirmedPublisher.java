package com.loopers.domains.order.out;

import com.loopers.domains.order.app.port.OrderConfirmedPayload;
import com.loopers.domains.order.app.port.OrderConfirmedPublisher;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// 재발행 명령 경로 전용 publisher(설계 27절). 최초 발행은 Outbox relay가 한다(28절 D95).
@Slf4j
@Component
public class KafkaOrderConfirmedPublisher implements OrderConfirmedPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final Duration republishTimeout;

    public KafkaOrderConfirmedPublisher(KafkaTemplate<String, String> kafkaTemplate,
        @Value("${commerce.kafka.topics.order-events}") String topic,
        @Value("${commerce.kafka.producer.republish-timeout}") Duration republishTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.republishTimeout = republishTimeout;
    }

    // 재발행은 사용자 요청 경로가 아니라 결과를 기다린다. 실패는 예외로 돌려 명령을 ack하지 않고 재시도·정지하게 한다.
    @Override
    public void republish(OrderConfirmedPayload payload) {
        try {
            RecordMetadata metadata = kafkaTemplate.send(topic, payload.key(), OrderConfirmedMessageSerializer.serialize(payload))
                .get(republishTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .getRecordMetadata();
            log.info("주문 확정 이벤트 재발행. eventId={}, topic={}, partition={}, offset={}",
                payload.eventId(), metadata.topic(), metadata.partition(), metadata.offset());
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("주문 확정 이벤트 재발행 실패. eventId=" + payload.eventId(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("주문 확정 이벤트 재발행 대기 중 중단. eventId=" + payload.eventId(), e);
        }
    }
}
