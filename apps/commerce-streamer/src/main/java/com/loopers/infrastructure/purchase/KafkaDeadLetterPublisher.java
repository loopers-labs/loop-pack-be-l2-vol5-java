package com.loopers.infrastructure.purchase;

import com.loopers.application.purchase.DeadLetterPublisher;
import com.loopers.config.kafka.OrderActivityKafkaConfig;
import com.loopers.domain.purchase.PermanentEventException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 영구 오류 record 를 {원본 topic}.DLT 로 보낸다 (설계 D12). 운영은 order-events.DLT, 격리 테스트는 자기 topic 의 .DLT 다.
 * 앱은 DLT topic 을 만들지 않는다. 발행 성공은 ERROR 로그로 남긴다. 이번 주에는 DLT 를 읽는 쪽이 없어 이 로그가 유일한 알림이기 때문이다.
 */
@Slf4j
@Component
public class KafkaDeadLetterPublisher implements DeadLetterPublisher {

    public static final String DLT_SUFFIX = ".DLT";
    public static final String HEADER_EXCEPTION_CLASS = "dlt.exception.class";
    public static final String HEADER_EXCEPTION_MESSAGE = "dlt.exception.message";
    public static final String HEADER_ORIGINAL_TOPIC = "dlt.original.topic";
    public static final String HEADER_ORIGINAL_PARTITION = "dlt.original.partition";
    public static final String HEADER_ORIGINAL_OFFSET = "dlt.original.offset";
    public static final String HEADER_EXISTING_FINGERPRINT = "dlt.existing.fingerprint";
    public static final String HEADER_NEW_FINGERPRINT = "dlt.new.fingerprint";

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final long sendTimeoutMs;

    public KafkaDeadLetterPublisher(
        @Qualifier(OrderActivityKafkaConfig.DEAD_LETTER_KAFKA_TEMPLATE) KafkaTemplate<String, byte[]> kafkaTemplate,
        @Value("${purchase-activity.dead-letter.send-timeout-ms}") long sendTimeoutMs
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @Override
    public void publish(ConsumerRecord<String, byte[]> record, PermanentEventException cause) {
        String deadLetterTopic = record.topic() + DLT_SUFFIX;
        ProducerRecord<String, byte[]> deadLetter = new ProducerRecord<>(deadLetterTopic, record.key(), record.value());
        deadLetter.headers()
            .add(HEADER_EXCEPTION_CLASS, bytes(cause.getClass().getName()))
            .add(HEADER_EXCEPTION_MESSAGE, bytes(String.valueOf(cause.getMessage())))
            .add(HEADER_ORIGINAL_TOPIC, bytes(record.topic()))
            .add(HEADER_ORIGINAL_PARTITION, bytes(String.valueOf(record.partition())))
            .add(HEADER_ORIGINAL_OFFSET, bytes(String.valueOf(record.offset())));
        if (cause.isConflict()) {
            deadLetter.headers()
                .add(HEADER_EXISTING_FINGERPRINT, bytes(cause.getExistingFingerprint()))
                .add(HEADER_NEW_FINGERPRINT, bytes(cause.getNewFingerprint()));
        }

        try {
            RecordMetadata metadata = kafkaTemplate.send(deadLetter).get(sendTimeoutMs, TimeUnit.MILLISECONDS).getRecordMetadata();
            log.error("영구 오류 record 를 DLT 로 보냈습니다 (구매 활동 없음). dlt={}-{}@{}, original={}-{}@{}, key={}, cause={}: {}",
                metadata.topic(), metadata.partition(), metadata.offset(),
                record.topic(), record.partition(), record.offset(), record.key(), cause.getClass().getSimpleName(), cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DLT 발행 대기 중 인터럽트. topic=" + deadLetterTopic + ", key=" + record.key(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("DLT 발행 실패. topic=" + deadLetterTopic + ", key=" + record.key(), e);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
