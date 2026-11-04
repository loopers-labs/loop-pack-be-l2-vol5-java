package com.loopers.infrastructure.outbox;

import com.loopers.application.outbox.OutboxPublishException;
import com.loopers.application.outbox.OutboxPublisher;
import com.loopers.domain.outbox.Outbox;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbox 의 Kafka 전송. producer future 가 성공해야 반환하므로 반환은 브로커가 받았다는 뜻이다. DB 는 건드리지 않아,
 * 제한 시간 뒤에 늦게 끝나는 전송이 상태를 바꾸지 못한다.
 */
@Slf4j
@Component
public class KafkaOutboxPublisher implements OutboxPublisher {

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final String topic;
    private final Duration sendTimeout;

    public KafkaOutboxPublisher(
        KafkaTemplate<Object, Object> kafkaTemplate,
        @Value("${order-event.topic}") String topic,
        @Value("${outbox.relay.send-timeout}") Duration sendTimeout
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void publish(Outbox outbox) {
        try {
            RecordMetadata metadata = kafkaTemplate.send(topic, outbox.getMessageKey(), outbox.getPayload())
                .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .getRecordMetadata();
            log.info("Outbox 전송 성공. eventId={}, orderId={}, topic={}, partition={}, offset={}",
                outbox.getEventId(), outbox.getOrderId(), metadata.topic(), metadata.partition(), metadata.offset());
        } catch (ExecutionException e) {
            throw new OutboxPublishException("전송 실패: " + describe(e.getCause()), e);
        } catch (TimeoutException e) {
            throw new OutboxPublishException("브로커 확인 대기 시간 초과 (" + sendTimeout + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OutboxPublishException("전송 확인 대기 중 중단됨", e);
        } catch (RuntimeException e) {
            throw new OutboxPublishException("전송 요청 실패: " + describe(e), e);
        }
    }

    private static String describe(Throwable error) {
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }
}
