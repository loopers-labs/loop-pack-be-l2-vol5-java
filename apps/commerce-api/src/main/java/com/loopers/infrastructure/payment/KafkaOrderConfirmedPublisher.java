package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.OrderConfirmedPublishException;
import com.loopers.application.payment.OrderConfirmedPublisher;
import com.loopers.config.kafka.OrderEventKafkaConfig;
import com.loopers.domain.order.OrderConfirmedEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * order-events 로 OrderConfirmed 를 보낸다 (6주차 설계 D6·D7).
 * - key = orderId 문자열, value = ObjectMapper 로 만든 JSON byte[]
 * - broker 저장 확인까지 동기로 기다리고(상한 send-timeout-ms) 결과를 세 가지로 구분한다
 *   성공: metadata 수신, INFO 로그 / 실패 확정(FAILED): producer 가 포기해 future 가 예외로 끝남 /
 *   미확인(UNKNOWN): 상한 안에 future 가 끝나지 않음. record 는 버퍼에 남아 delivery.timeout.ms 동안 재시도되므로 실제로는 발행될 수 있다
 * - 실패·미확인은 outcome 을 담은 OrderConfirmedPublishException 으로 올린다. 주문 상태를 되돌리거나 PG 를 재호출하지 않는 것은 호출자의 책임이다
 */
@Slf4j
@Component
public class KafkaOrderConfirmedPublisher implements OrderConfirmedPublisher {

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final String topic;
    private final long sendTimeoutMs;

    public KafkaOrderConfirmedPublisher(
        ObjectMapper objectMapper,
        @Qualifier(OrderEventKafkaConfig.ORDER_EVENT_KAFKA_TEMPLATE) KafkaTemplate<String, byte[]> kafkaTemplate,
        @Value("${order-events.topic}") String topic,
        @Value("${order-events.send-timeout-ms}") long sendTimeoutMs
    ) {
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @Override
    public void publish(OrderConfirmedEvent event) {
        byte[] value;
        try {
            value = objectMapper.writeValueAsBytes(event);
        } catch (JsonProcessingException e) {
            throw failed(event, "직렬화 실패", e);
        }

        CompletableFuture<SendResult<String, byte[]>> future;
        try {
            future = kafkaTemplate.send(topic, event.key(), value);
        } catch (RuntimeException e) {
            // producer 가 send 자체를 거부해 버퍼에 들어가지 않았다
            throw failed(event, "send 거부, topic=" + topic, e);
        }

        try {
            RecordMetadata metadata = future.get(sendTimeoutMs, TimeUnit.MILLISECONDS).getRecordMetadata();
            log.info("OrderConfirmed 발행 성공. outcome=SUCCESS, eventId={}, orderId={}, topic={}, partition={}, offset={}",
                event.eventId(), event.orderId(), metadata.topic(), metadata.partition(), metadata.offset());
        } catch (ExecutionException e) {
            // producer 가 포기했다 (metadata 대기 max.block.ms 초과, delivery.timeout.ms 소진 등)
            throw failed(event, "producer 포기, topic=" + topic, e.getCause());
        } catch (TimeoutException e) {
            throw unknown(event, sendTimeoutMs + "ms 안에 결과를 받지 못함. producer 가 delivery.timeout.ms 동안 재시도 중, topic=" + topic, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unknown(event, "대기 중 인터럽트, topic=" + topic, e);
        }
    }

    private static OrderConfirmedPublishException failed(OrderConfirmedEvent event, String detail, Throwable cause) {
        return new OrderConfirmedPublishException(
            OrderConfirmedPublishException.Outcome.FAILED, event.eventId(), event.orderId(), detail, cause);
    }

    private static OrderConfirmedPublishException unknown(OrderConfirmedEvent event, String detail, Throwable cause) {
        return new OrderConfirmedPublishException(
            OrderConfirmedPublishException.Outcome.UNKNOWN, event.eventId(), event.orderId(), detail, cause);
    }
}
