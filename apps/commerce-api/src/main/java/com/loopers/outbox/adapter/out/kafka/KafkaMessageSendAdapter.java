package com.loopers.outbox.adapter.out.kafka;

import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.outbox.application.port.out.MessageSendPort;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * JSON 원문을 그대로 보낸다 (StringSerializer, ADR-W6-03). 성공 = acks=all로 ISR(최소 2대)에 기록됨 (ADR-W6-04).
 */
@Component
public class KafkaMessageSendAdapter implements MessageSendPort {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaMessageSendAdapter(@Qualifier(KafkaConfig.STRING_TEMPLATE) KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public CompletableFuture<SentPosition> send(String topic, String key, String payload) {
        return kafkaTemplate.send(topic, key, payload).thenApply(result -> {
            RecordMetadata metadata = result.getRecordMetadata();
            return new SentPosition(metadata.partition(), metadata.offset());
        });
    }
}
