package com.loopers.infrastructure.order;

import com.loopers.config.OrderEventKafkaProperties;
import com.loopers.config.OrderOutboxKafkaConfig;
import com.loopers.domain.order.OrderOutboxAttempt;
import com.loopers.domain.order.OrderOutboxReceipt;
import com.loopers.domain.order.OrderOutboxSender;
import com.loopers.domain.order.OrderOutboxStoredMessage;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletionStage;

/**
 * Outbox 저장 key·payload 를 전용 String/String template 으로 설정 topic 에 그대로 보낸다(설계 W7.3-5). 재직렬화·partition 지정·header 추가가 없다.
 *
 * <p>template.send 의 동기 예외(metadata·buffer 대기 초과 등)는 그대로 던지고, 비동기 실패는 반환 stage 의 예외 완료로 남긴다.
 * 완료 callback 은 실제 metadata 를 receipt 로 바꾸기만 하며 대기·재시도·DB 상태 변경·로그 출력을 하지 않는다.
 */
@Component
public class KafkaOrderOutboxSender implements OrderOutboxSender {

    private final KafkaTemplate<String, String> template;
    private final String topic;

    public KafkaOrderOutboxSender(
        @Qualifier(OrderOutboxKafkaConfig.ORDER_OUTBOX_KAFKA_TEMPLATE) KafkaTemplate<String, String> template,
        OrderEventKafkaProperties properties) {
        this.template = template;
        this.topic = properties.topic();
    }

    @Override
    public CompletionStage<OrderOutboxReceipt> send(OrderOutboxAttempt attempt) {
        OrderOutboxStoredMessage message = attempt.message();
        return template.send(topic, message.messageKey(), message.payload()).thenApply(KafkaOrderOutboxSender::receipt);
    }

    private static OrderOutboxReceipt receipt(SendResult<String, String> result) {
        RecordMetadata metadata = result.getRecordMetadata();
        return new OrderOutboxReceipt(metadata.topic(), metadata.partition(), metadata.offset());
    }
}
