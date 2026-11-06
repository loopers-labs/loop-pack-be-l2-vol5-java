package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderConfirmedPublisher;
import com.loopers.application.order.OrderConfirmedSendException;
import com.loopers.application.order.SentRecord;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Outbox에 저장된 OrderConfirmed를 Kafka로 보낸다(docs/week7/decisions.md D-9). relay가 부른다.
 *
 * - key·value는 받은 문자열 그대로다. JSON을 다시 만들지 않는다 — Kafka value와 Outbox payload가 바이트까지 같아야 한다
 *   (INV-702). 전용 String producer(OrderEventKafkaConfig)를 쓴다(6주차 D-25).
 * - broker 응답까지 기다린다. relay가 결과로 PUBLISHED·last_error를 정해야 하기 때문이다. 따로 대기 상한을 두지 않는다 —
 *   producer의 delivery.timeout.ms·max.block.ms가 반드시 성공이나 실패를 돌려준다(D-2).
 * - 성공이면 topic·partition·offset을 남기고 돌려준다. 실패는 짧은 메시지와 원인을 담은 예외로 올린다. payload는 담지 않는다.
 * - 최대 전송 시간은 이 template이 쓰는 producer의 실제 설정(max.block.ms + delivery.timeout.ms)에서 계산한다. relay가 임대
 *   시간을 여기서 얻는다(D-2) — 설정이 바뀌면 임대도 따라 바뀐다.
 */
@Slf4j
@Component
public class KafkaOrderConfirmedPublisher implements OrderConfirmedPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public KafkaOrderConfirmedPublisher(
        @Qualifier(OrderEventKafkaConfig.KAFKA_TEMPLATE) KafkaTemplate<String, String> kafkaTemplate,
        @Value("${events.order-confirmed.topic:order-events}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public SentRecord send(String messageKey, String payload) {
        try {
            RecordMetadata metadata = kafkaTemplate.send(topic, messageKey, payload).get().getRecordMetadata();
            log.info("주문 확정 이벤트 전송: key={}, topic={}, partition={}, offset={}",
                messageKey, metadata.topic(), metadata.partition(), metadata.offset());
            return new SentRecord(metadata.topic(), metadata.partition(), metadata.offset());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OrderConfirmedSendException(failureMessage(messageKey, "대기 중 인터럽트"), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new OrderConfirmedSendException(failureMessage(messageKey, cause.getClass().getSimpleName()), cause);
        } catch (RuntimeException e) {
            // send() 자체가 던지는 경우(예: max.block.ms 안에 metadata를 못 받음, 직렬화 실패)
            throw new OrderConfirmedSendException(failureMessage(messageKey, e.getClass().getSimpleName()), e);
        }
    }

    private String failureMessage(String messageKey, String reason) {
        return "주문 확정 이벤트 전송 실패: topic=" + topic + ", key=" + messageKey + ", reason=" + reason;
    }

    /**
     * max.block.ms(send()가 metadata·버퍼를 기다리는 상한) + delivery.timeout.ms(보낸 뒤 성공·실패가 확정되기까지의 상한).
     * 설정에 없는 값은 Kafka 기본값을 쓴다 — delivery.timeout.ms는 지금 따로 두지 않아 기본 120000ms다(D-2).
     */
    @Override
    public Duration maxSendTime() {
        Map<String, Object> config = kafkaTemplate.getProducerFactory().getConfigurationProperties();
        return Duration.ofMillis(millis(config, ProducerConfig.MAX_BLOCK_MS_CONFIG)
            + millis(config, ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG));
    }

    private static long millis(Map<String, Object> config, String name) {
        Object value = config.get(name);
        if (value == null) {
            value = ProducerConfig.configDef().defaultValues().get(name);
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value).trim());
    }
}
