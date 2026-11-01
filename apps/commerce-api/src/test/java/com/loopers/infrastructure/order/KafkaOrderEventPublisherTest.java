package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.OrderEventKafkaProperties;
import com.loopers.domain.order.OrderConfirmed;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 발행 adapter 의 변환·send 호출·결과 로그를 template 대역으로 확인한다(P5 의 adapter 오류 부분).
 * 대역의 호출 기록이므로 실제 broker 의 record·metadata 근거가 아니다.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Kafka 발행 adapter 는 v1 wire 값으로 partition 없이 보내고, 오류는 단계별 안전 로그로 남긴 뒤 정상 반환한다.")
class KafkaOrderEventPublisherTest {

    private static final String TOPIC = "order-events-unit";
    private static final OrderConfirmed EVENT = new OrderConfirmed(42L,
        ZonedDateTime.of(2026, 10, 28, 7, 0, 0, 123_456_000, ZoneOffset.UTC),
        List.of(new OrderConfirmed.Item(7L, 2L), new OrderConfirmed.Item(8L, 1L)));

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
    private final KafkaOrderEventPublisher publisher =
        new KafkaOrderEventPublisher(template, new OrderEventKafkaProperties(TOPIC, Map.of(), 15));

    private static String failureLine(CapturedOutput output) {
        return output.getOut().lines().filter(line -> line.contains("ORDER_CONFIRMED_SEND_FAILURE")).findFirst().orElse("");
    }

    @DisplayName("주문 ID 문자열 key 와 v1 wire 값으로 설정 topic 에 보내고, 명시 partition 이 있는 overload 를 쓰지 않는다.")
    @Test
    void sendsWireValueWithOrderKey() {
        when(template.send(anyString(), anyString(), any())).thenReturn(new CompletableFuture<>());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);

        publisher.publish(EVENT);

        verify(template).send(eq(TOPIC), eq("42"), payload.capture());
        verifyNoMoreInteractions(template);
        JsonNode json = new ObjectMapper().valueToTree(payload.getValue());
        assertAll(
            () -> assertThat(payload.getValue()).isInstanceOf(KafkaOrderEventPublisher.OrderConfirmedV1.class),
            () -> assertThat(json.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("eventType", "schemaVersion", "eventId", "orderId", "confirmedAt", "items"),
            () -> assertThat(json.path("eventType").asText()).isEqualTo("OrderConfirmed"),
            () -> assertThat(json.path("schemaVersion").isInt()).isTrue(),
            () -> assertThat(json.path("schemaVersion").asInt()).isEqualTo(1),
            () -> assertThat(json.path("eventId").asText()).isEqualTo("order-confirmed:42"),
            () -> assertThat(json.path("orderId").isIntegralNumber()).isTrue(),
            () -> assertThat(json.path("orderId").asLong()).isEqualTo(42L),
            () -> assertThat(json.path("confirmedAt").asText()).isEqualTo("2026-10-28T07:00:00.123456Z"),
            () -> assertThat(json.path("items").toString()).isEqualTo("[{\"productId\":7,\"quantity\":2},{\"productId\":8,\"quantity\":1}]")
        );
    }

    @DisplayName("send 호출이 예외를 던지면 SEND_CALL 로그만 남기고 예외를 밖으로 내보내지 않는다.")
    @Test
    void logsSendCallFailure(CapturedOutput output) {
        when(template.send(anyString(), anyString(), any()))
            .thenThrow(new IllegalStateException("민감한-원문", new IllegalArgumentException("원인-원문")));

        assertThatCode(() -> publisher.publish(EVENT)).doesNotThrowAnyException();

        assertAll(
            () -> assertThat(failureLine(output)).contains("WARN", "stage=SEND_CALL", "eventId=order-confirmed:42", "orderId=42",
                "topic=" + TOPIC, "exceptionType=java.lang.IllegalStateException",
                "causeType=java.lang.IllegalArgumentException"),
            () -> assertThat(output.getAll()).doesNotContain("민감한-원문", "원인-원문", "productId")
        );
    }

    @DisplayName("Future 가 실패로 끝나면 SEND_COMPLETION 로그를 남긴다. 실패를 broker 미기록으로 판정하지 않는다.")
    @Test
    void logsCompletionFailure(CapturedOutput output) {
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        when(template.send(anyString(), anyString(), any())).thenReturn(future);

        publisher.publish(EVENT);
        assertThat(failureLine(output)).as("완료 전에는 결과 로그 없음").isEmpty();
        future.completeExceptionally(new TimeoutException("민감한-원문"));

        assertAll(
            () -> assertThat(failureLine(output)).contains("stage=SEND_COMPLETION", "eventId=order-confirmed:42",
                "exceptionType=org.apache.kafka.common.errors.TimeoutException", "causeType=none"),
            () -> assertThat(output.getAll()).doesNotContain("민감한-원문", "ORDER_CONFIRMED_SEND_SUCCESS")
        );
    }

    @DisplayName("Future 가 성공하면 broker 가 돌려준 topic·partition·offset 을 성공 로그로 남긴다.")
    @Test
    void logsSuccessMetadata(CapturedOutput output) {
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        when(template.send(anyString(), anyString(), any())).thenReturn(future);

        publisher.publish(EVENT);
        future.complete(new SendResult<>(new ProducerRecord<>(TOPIC, "42", "payload"),
            new RecordMetadata(new TopicPartition(TOPIC, 1), 7L, 0, 0L, 0, 0)));

        assertAll(
            () -> assertThat(output.getOut()).contains("ORDER_CONFIRMED_SEND_SUCCESS eventId=order-confirmed:42 orderId=42 topic="
                + TOPIC + " partition=1 offset=7"),
            () -> assertThat(failureLine(output)).isEmpty()
        );
    }
}
