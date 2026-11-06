package com.loopers.infrastructure.order;

import com.loopers.application.order.OutboxMessageSender;
import com.loopers.application.order.OutboxRelayProperties;
import com.loopers.application.order.OutboxSendResult;
import com.loopers.config.kafka.OrderEventKafkaConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbox 행의 key·payload(JSON 문자열 → UTF-8 bytes)를 order-events 로 보낸다 (7주차 설계 D26·D27·D29·D33).
 * - W6 발행기와 같은 topic·KafkaTemplate(String key, byte[] value, acks=all) 을 쓴다
 * - 동기 대기(send-timeout) 뒤 결과를 세 가지로 분류한다. PUBLISHED(metadata 수신) / RETRY(실패 확정 또는 미확인) / BLOCKED(영구 오류)
 * - future 에 콜백을 달지 않는다. 대기를 넘긴 send 가 뒤에서 성공해도 상태를 건드릴 경로가 없다 (D25)
 * - detail 에는 예외 클래스·요약 메시지만 남긴다. payload·호스트:포트 같은 접속 정보는 지운다 (D33)
 */
@Slf4j
@Component
public class KafkaOutboxMessageSender implements OutboxMessageSender {

    /** last_error 컬럼 길이(1000)에 맞춘 상한. */
    static final int DETAIL_MAX_LENGTH = 1000;
    private static final String HOST_PORT_PATTERN = "[A-Za-z0-9.\\-]+:\\d{2,5}";

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final String topic;
    private final long sendTimeoutMs;

    @Autowired
    public KafkaOutboxMessageSender(
        @Qualifier(OrderEventKafkaConfig.ORDER_EVENT_KAFKA_TEMPLATE) KafkaTemplate<String, byte[]> kafkaTemplate,
        @Value("${order-events.topic}") String topic,
        OutboxRelayProperties properties
    ) {
        this(kafkaTemplate, topic, properties.sendTimeout().toMillis());
    }

    KafkaOutboxMessageSender(KafkaTemplate<String, byte[]> kafkaTemplate, String topic, long sendTimeoutMs) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @Override
    public OutboxSendResult send(String messageKey, String payload) {
        CompletableFuture<SendResult<String, byte[]>> future;
        try {
            future = kafkaTemplate.send(topic, messageKey, payload.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            // producer 가 send 자체를 거부했다 (metadata 대기 초과, 직렬화 거부 등). 버퍼에 들어가지 않았다
            return classify("send 거부", e);
        }

        try {
            RecordMetadata metadata = future.get(sendTimeoutMs, TimeUnit.MILLISECONDS).getRecordMetadata();
            return OutboxSendResult.published(metadata.topic(), metadata.partition(), metadata.offset());
        } catch (ExecutionException e) {
            // producer 가 포기했다 (delivery.timeout.ms 소진, 영구 오류 등)
            return classify("실패 확정", e.getCause() == null ? e : e.getCause());
        } catch (TimeoutException e) {
            // record 는 버퍼에 남아 delivery.timeout.ms 동안 재시도되므로 실제로는 발행될 수 있다. 수신 0회로 단정하지 않는다
            return OutboxSendResult.retry(sanitize("결과 미확인: " + sendTimeoutMs + "ms 안에 broker 확인을 받지 못함"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OutboxSendResult.retry("결과 미확인: 대기 중 인터럽트");
        }
    }

    /**
     * 영구 오류(다시 보내도 같은 결과)만 BLOCKED, 나머지는 전부 RETRY (D29). UnknownTopicOrPartition 은 topic 생성·메타데이터 지연이 풀리면
     * 해결되므로 RETRY 다. 원인 사슬을 끝까지 따라가며 판정한다.
     */
    private OutboxSendResult classify(String phase, Throwable error) {
        String detail = sanitize(phase + ": " + describe(error));
        if (isPermanent(error)) {
            return OutboxSendResult.blocked(detail);
        }
        return OutboxSendResult.retry(detail);
    }

    private static boolean isPermanent(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof RecordTooLargeException || t instanceof SerializationException
                || t instanceof InvalidTopicException || t instanceof AuthorizationException) {
                return true;
            }
        }
        return false;
    }

    /** 가장 안쪽 원인의 클래스 이름과 메시지. 바깥 래퍼(KafkaProducerException 등)는 원인만 남긴다. */
    private static String describe(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : " - " + root.getMessage());
    }

    /** 호스트:포트 형태의 접속 정보를 지우고 컬럼 길이에 맞춰 자른다. payload 는 여기 들어오지 않는다. */
    static String sanitize(String text) {
        String cleaned = text.replaceAll(HOST_PORT_PATTERN, "<host:port>");
        return cleaned.length() <= DETAIL_MAX_LENGTH ? cleaned : cleaned.substring(0, DETAIL_MAX_LENGTH);
    }
}
