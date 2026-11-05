package com.loopers.infrastructure.order;

import com.loopers.config.OrderEventKafkaProperties;
import com.loopers.domain.order.OrderOutboxAttempt;
import com.loopers.domain.order.OrderOutboxReceipt;
import com.loopers.domain.order.OrderOutboxStoredMessage;
import com.loopers.support.error.CoreException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * O5 sender 계약을 template 대역으로 통제한다(설계 W7.3-5). 호출 인자·Future 완료 경계·오류 전파·metadata 변환만 보며,
 * 대역 기록이므로 실제 broker 성공·wire 근거가 아니다(실제 wire 는 OrderOutboxKafkaWireIntegrationTest).
 */
@DisplayName("Kafka Outbox sender 는 저장 key·payload 를 그대로 보내고, 완료 전 결과를 만들지 않으며 오류를 삼키지 않는다.")
class KafkaOrderOutboxSenderTest {

    private static final String TOPIC = "order-events-unit";
    /** 재직렬화되면 바뀌는 공백·속성 순서를 일부러 둔 원문. sender 는 해석하지 않는다. */
    private static final String PAYLOAD = "{ \"orderId\":42, \"eventType\":\"OrderConfirmed\" }";
    private static final OrderOutboxAttempt ATTEMPT = new OrderOutboxAttempt(9L, 3L, 2,
        new OrderOutboxStoredMessage("order-confirmed:42", 42L, "OrderConfirmed", 1, "42", PAYLOAD));

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    private final KafkaOrderOutboxSender sender = new KafkaOrderOutboxSender(template, new OrderEventKafkaProperties(TOPIC, Map.of(), 15));

    @DisplayName("설정 topic 에 저장 key·payload 를 partition 없는 overload 로 한 번 보내고, Future 완료 전에는 결과가 없다.")
    @Test
    void sendsStoredValuesAndWaitsForCompletion() {
        CompletableFuture<SendResult<String, String>> pending = new CompletableFuture<>();
        when(template.send(TOPIC, "42", PAYLOAD)).thenReturn(pending);

        CompletionStage<OrderOutboxReceipt> stage = sender.send(ATTEMPT);

        verify(template).send(TOPIC, "42", PAYLOAD);
        verifyNoMoreInteractions(template);
        assertThat(stage.toCompletableFuture()).as("broker 완료 전 성공으로 보지 않음").isNotDone();

        pending.complete(result(new RecordMetadata(new TopicPartition(TOPIC, 1), 7L, 0, 0L, 2, 50)));
        assertThat(stage.toCompletableFuture().join()).isEqualTo(new OrderOutboxReceipt(TOPIC, 1, 7L));
    }

    @DisplayName("send 호출의 동기 예외는 stage 로 감싸지 않고 호출자에게 그대로 던진다.")
    @Test
    void propagatesSynchronousFailure() {
        TimeoutException blocked = new TimeoutException("metadata wait");
        when(template.send(anyString(), anyString(), anyString())).thenThrow(blocked);

        assertThatThrownBy(() -> sender.send(ATTEMPT)).isSameAs(blocked);
    }

    @DisplayName("비동기 실패는 같은 원인의 예외 완료로 남고 성공 receipt 로 바뀌지 않는다.")
    @Test
    void propagatesAsynchronousFailure() {
        CompletableFuture<SendResult<String, String>> pending = new CompletableFuture<>();
        when(template.send(anyString(), anyString(), anyString())).thenReturn(pending);
        CompletionStage<OrderOutboxReceipt> stage = sender.send(ATTEMPT);
        TimeoutException expired = new TimeoutException("delivery");

        pending.completeExceptionally(expired);

        assertThatThrownBy(() -> stage.toCompletableFuture().get())
            .isInstanceOf(ExecutionException.class).hasCause(expired);
    }

    @DisplayName("offset 이 없는 metadata 는 broker 확인 위치로 바꾸지 않고 예외 완료로 남긴다.")
    @Test
    void rejectsMetadataWithoutOffset() {
        when(template.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(
            result(new RecordMetadata(new TopicPartition(TOPIC, 0), -1L, 0, 0L, 2, 50))));

        CompletableFuture<OrderOutboxReceipt> future = sender.send(ATTEMPT).toCompletableFuture();

        assertAll(
            () -> assertThat(future).isCompletedExceptionally(),
            () -> assertThatThrownBy(future::get).hasCauseInstanceOf(CoreException.class)
        );
    }

    @SuppressWarnings("unchecked")
    private static SendResult<String, String> result(RecordMetadata metadata) {
        return new SendResult<>(mock(ProducerRecord.class), metadata);
    }
}
