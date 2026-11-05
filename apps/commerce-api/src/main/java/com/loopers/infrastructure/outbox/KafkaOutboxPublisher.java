package com.loopers.infrastructure.outbox;

import com.loopers.application.outbox.OutboxPublishException;
import com.loopers.application.outbox.OutboxPublisher;
import com.loopers.domain.outbox.Outbox;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p>전송은 회로를 거친다. 브로커 장애로 실패가 이어지면 회로가 열려, 그동안의 전송은 브로커를 두드리지 않고 바로 거절된다.
 */
@Slf4j
@Component
public class KafkaOutboxPublisher implements OutboxPublisher {

    /** 회로 인스턴스 이름. 설정은 {@code resilience4j.circuitbreaker.instances.outboxPublish} 다. */
    public static final String CIRCUIT_BREAKER = "outboxPublish";

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final String topic;
    private final Duration sendTimeout;
    private final CircuitBreaker circuitBreaker;

    @Autowired
    public KafkaOutboxPublisher(
        KafkaTemplate<Object, Object> kafkaTemplate,
        @Value("${order-event.topic}") String topic,
        @Value("${outbox.relay.send-timeout}") Duration sendTimeout,
        CircuitBreakerRegistry circuitBreakerRegistry
    ) {
        this(kafkaTemplate, topic, sendTimeout, circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER));
    }

    KafkaOutboxPublisher(
        KafkaTemplate<Object, Object> kafkaTemplate,
        String topic,
        Duration sendTimeout,
        CircuitBreaker circuitBreaker
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.sendTimeout = sendTimeout;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public void publish(Outbox outbox) {
        RecordMetadata metadata;
        try {
            metadata = circuitBreaker.executeSupplier(() -> send(outbox));
        } catch (CallNotPermittedException e) {
            throw new OutboxPublishException("회로 열림: 브로커 장애로 전송을 멈춘 상태", e);
        }
        log.info("Outbox 전송 성공. eventId={}, orderId={}, topic={}, partition={}, offset={}",
            outbox.getEventId(), outbox.getOrderId(), metadata.topic(), metadata.partition(), metadata.offset());
    }

    @Override
    public boolean canPublish() {
        return circuitBreaker.getState() != CircuitBreaker.State.OPEN;
    }

    private RecordMetadata send(Outbox outbox) {
        try {
            return kafkaTemplate.send(topic, outbox.getMessageKey(), outbox.getPayload())
                .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .getRecordMetadata();
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
