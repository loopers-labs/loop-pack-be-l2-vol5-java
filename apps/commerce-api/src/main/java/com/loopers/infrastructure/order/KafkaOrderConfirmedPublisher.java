package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderConfirmedPayload;
import com.loopers.application.order.port.OrderConfirmedPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// send 결과를 기다리지 않는다(설계 27절 D80). 이미 commit된 확정이므로 실패해도 주문을 되돌리지 않고 로그·지표로 드러내며,
// 놓친 발행은 3b 대사가 재발행한다. 동기 예외(직렬화·producer 종료 등)도 같은 실패로 다뤄 호출 경로로 퍼뜨리지 않는다.
@Slf4j
@Component
public class KafkaOrderConfirmedPublisher implements OrderConfirmedPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final Counter publishFailures;
    private final Duration republishTimeout;

    public KafkaOrderConfirmedPublisher(KafkaTemplate<String, String> kafkaTemplate,
        @Value("${commerce.kafka.topics.order-events}") String topic, MeterRegistry meterRegistry,
        @Value("${commerce.kafka.producer.republish-timeout}") Duration republishTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.publishFailures = Counter.builder("commerce.order.events.publish.failed")
            .tag("eventType", OrderConfirmedPayload.EVENT_TYPE)
            .register(meterRegistry);
        this.republishTimeout = republishTimeout;
    }

    @Override
    public void send(OrderConfirmedPayload payload) {
        try {
            kafkaTemplate.send(topic, payload.key(), OrderConfirmedMessageSerializer.serialize(payload))
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        fail(payload, failure);
                        return;
                    }
                    RecordMetadata metadata = result.getRecordMetadata();
                    log.info("주문 확정 이벤트 발행. eventId={}, topic={}, partition={}, offset={}",
                        payload.eventId(), metadata.topic(), metadata.partition(), metadata.offset());
                });
        } catch (RuntimeException e) {
            fail(payload, e);
        }
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

    private void fail(OrderConfirmedPayload payload, Throwable failure) {
        publishFailures.increment();
        log.error("주문 확정 이벤트 발행 실패. eventId={}, orderId={}, topic={}, causeType={}",
            payload.eventId(), payload.orderId(), topic, failure.getClass().getSimpleName(), failure);
    }
}
