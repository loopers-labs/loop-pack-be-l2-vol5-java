package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderConfirmationPublisher;
import com.loopers.domain.order.OrderOutboxDelivery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 저장된 payload 문자열을 해석하지 않고 그대로 보낸다. 값 직렬화는 commerce-api의 StringSerializer 설정을 따른다.
 * 읽을 수 없는 payload는 consumer가 DLT로 격리한다.
 */
@Component
@ConditionalOnProperty(name = "commerce.order-events.enabled", havingValue = "true")
@Slf4j
public class KafkaOrderConfirmedPublisher implements OrderConfirmationPublisher {
    private final KafkaTemplate<Object, Object> kafka;
    private final String topic;
    private final Duration sendWait;

    public KafkaOrderConfirmedPublisher(KafkaTemplate<Object, Object> kafka,
                                        @Value("${commerce.order-events.topic:order-events}") String topic,
                                        @Value("${commerce.order-events.relay.send-wait:130s}") Duration sendWait) {
        this.kafka = kafka;
        this.topic = topic;
        this.sendWait = sendWait;
    }

    @Override
    public void publish(OrderOutboxDelivery delivery) {
        try {
            SendResult<Object, Object> result = kafka.send(topic, delivery.messageKey(), delivery.payload())
                .get(sendWait.toMillis(), TimeUnit.MILLISECONDS);
            var metadata = result.getRecordMetadata();
            log.info("Order event published: eventId={}, topic={}, partition={}, offset={}",
                delivery.eventId(), metadata.topic(), metadata.partition(), metadata.offset());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failed(delivery, exception);
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            // send() 호출에서 바로 던진 실패(크기 초과·metadata 대기 초과 등)도 같은 방식으로 원인을 남긴다.
            throw failed(delivery, exception);
        }
    }

    /** 감싼 예외(ExecutionException, KafkaProducerException 등)를 벗겨 가장 안쪽 원인을 오류로 남긴다. */
    private IllegalStateException failed(OrderOutboxDelivery delivery, Exception exception) {
        Throwable cause = exception;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return new IllegalStateException("broker 확인 실패: eventId=%s, %s: %s".formatted(
            delivery.eventId(), cause.getClass().getSimpleName(), cause.getMessage()), cause);
    }
}
