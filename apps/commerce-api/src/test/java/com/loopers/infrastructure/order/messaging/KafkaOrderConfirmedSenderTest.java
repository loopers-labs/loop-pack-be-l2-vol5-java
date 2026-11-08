package com.loopers.infrastructure.order.messaging;

import com.loopers.domain.order.vo.OrderConfirmedSendResult;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** future 의 끝맺음별 분류 (7주차 D-105, D-106). 실제 producer 설정은 KafkaOrderConfirmedSenderConfigTest */
class KafkaOrderConfirmedSenderTest {

    private static final String TOPIC = "order-events";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final KafkaOrderConfirmedSender sender = new KafkaOrderConfirmedSender(kafkaTemplate, TOPIC, Duration.ofMillis(100));

    @DisplayName("broker 가 확인하면 topic · partition · offset 을 담은 Acked 를 돌려준다.")
    @Test
    void returnsAcked_whenBrokerAcknowledges() {
        RecordMetadata metadata = new RecordMetadata(new TopicPartition(TOPIC, 2), 7L, 0, 0L, 2, 2);
        when(kafkaTemplate.send(TOPIC, "42", "{}"))
            .thenReturn(CompletableFuture.completedFuture(new SendResult<>(new ProducerRecord<>(TOPIC, "42", "{}"), metadata)));

        assertThat(sender.send("42", "{}")).isEqualTo(new OrderConfirmedSendResult.Acked(TOPIC, 2, 7L));
    }

    @DisplayName("로컬 대기가 끝날 때까지 결과가 없으면 수신 여부를 모르는 Transient 다. (D-105)")
    @Test
    void returnsTransient_whenLocalWaitTimesOut() {
        when(kafkaTemplate.send(TOPIC, "42", "{}")).thenReturn(new CompletableFuture<>());

        assertThat(sender.send("42", "{}")).isInstanceOf(OrderConfirmedSendResult.Transient.class);
    }

    @DisplayName("spring-kafka 가 감싼 원인까지 보고, 나열한 영구 오류만 Permanent 로 분류한다.")
    @Test
    void classifiesWrappedCause() {
        for (Throwable permanent : Set.of(
            new RecordTooLargeException("too large"),
            new SerializationException("bad"),
            new InvalidTopicException("bad topic"),
            new TopicAuthorizationException(Set.of(TOPIC))
        )) {
            when(kafkaTemplate.send(TOPIC, "42", "{}")).thenReturn(CompletableFuture.failedFuture(wrap(permanent)));

            assertThat(sender.send("42", "{}")).as(permanent.getClass().getSimpleName())
                .isInstanceOf(OrderConfirmedSendResult.Permanent.class);
        }
    }

    @DisplayName("Kafka 의 재시도 가능 오류와 처음 보는 오류는 Transient 다. (D-106)")
    @Test
    void classifiesRetriableAndUnknownAsTransient() {
        assertAll(
            () -> assertThat(KafkaOrderConfirmedSender.classify(wrap(new TimeoutException("metadata"))))
                .isInstanceOf(OrderConfirmedSendResult.Transient.class),
            () -> assertThat(KafkaOrderConfirmedSender.classify(wrap(new NotEnoughReplicasException("isr"))))
                .isInstanceOf(OrderConfirmedSendResult.Transient.class),
            () -> assertThat(KafkaOrderConfirmedSender.classify(new IllegalStateException("처음 보는 오류")))
                .isInstanceOf(OrderConfirmedSendResult.Transient.class)
        );
    }

    @DisplayName("send 호출 자체가 던진 예외도 분류해 돌려주고 밖으로 던지지 않는다.")
    @Test
    void classifiesSynchronousFailure() {
        when(kafkaTemplate.send(TOPIC, "42", "{}")).thenThrow(new SerializationException("bad"));

        assertThat(sender.send("42", "{}")).isInstanceOf(OrderConfirmedSendResult.Permanent.class);
    }

    @DisplayName("오류 기록에는 원인 예외의 타입 · 메시지만 담는다.")
    @Test
    void describesRootCause() {
        OrderConfirmedSendResult result = KafkaOrderConfirmedSender.classify(wrap(new TimeoutException("metadata 없음")));

        assertThat(result).isEqualTo(new OrderConfirmedSendResult.Transient("org.apache.kafka.common.errors.TimeoutException: metadata 없음"));
    }

    private static KafkaProducerException wrap(Throwable cause) {
        return new KafkaProducerException(new ProducerRecord<>(TOPIC, "42", "{}"), "Failed to send", cause);
    }
}
