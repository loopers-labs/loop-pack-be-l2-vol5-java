package com.loopers.order.infrastructure.kafka;

import com.loopers.order.application.outbox.PublishResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
@DisplayName("Kafka Outbox publisher 결과 변환")
class KafkaOutboxPublisherTest {

    private static final String TOPIC = "order-events";
    private static final String MESSAGE_KEY = "42";
    private static final String PAYLOAD = "{\"eventId\":\"order-confirmed:42\"}";

    private final KafkaTemplate<Object, Object> kafkaTemplate = mock(KafkaTemplate.class);

    @Test
    @DisplayName("Kafka 비동기 전송이 완료되기 전에는 발행 결과를 확정하지 않고, broker 성공 응답 후 SUCCESS를 반환한다")
    void completesPublishResultOnlyAfterKafkaSendCompletes() throws Exception {
        CompletableFuture<SendResult<Object, Object>> kafkaFuture = new CompletableFuture<>();
        when(kafkaTemplate.send(TOPIC, MESSAGE_KEY, PAYLOAD)).thenReturn(kafkaFuture);
        KafkaOutboxPublisher adapter = new KafkaOutboxPublisher(kafkaTemplate, TOPIC);

        CompletableFuture<PublishResult> publishResult = adapter.publish(MESSAGE_KEY, PAYLOAD);

        assertThat(publishResult).isNotCompleted();

        kafkaFuture.complete(mock(SendResult.class));

        assertThat(publishResult.get(2, TimeUnit.SECONDS)).isEqualTo(PublishResult.SUCCESS);
    }

    @Test
    @DisplayName("Kafka Future가 실패하면 실제 Publisher가 Relay에 반환하는 Future를 FAILED로 완료한다")
    void completesPublisherFutureAsFailed_whenKafkaFutureFails() throws Exception {
        CompletableFuture<SendResult<Object, Object>> kafkaFuture = new CompletableFuture<>();
        when(kafkaTemplate.send(TOPIC, MESSAGE_KEY, PAYLOAD)).thenReturn(kafkaFuture);
        KafkaOutboxPublisher adapter = new KafkaOutboxPublisher(kafkaTemplate, TOPIC);

        CompletableFuture<PublishResult> publishResult = adapter.publish(MESSAGE_KEY, PAYLOAD);
        kafkaFuture.completeExceptionally(new IllegalStateException("broker rejected message"));

        assertThat(publishResult.get(2, TimeUnit.SECONDS))
            .isEqualTo(PublishResult.failed(IllegalStateException.class.getSimpleName()));
    }

    @Test
    @DisplayName("같은 이벤트를 재발행하면 Kafka에 동일한 messageKey와 eventId가 포함된 payload를 전달한다")
    void republishesWithSameMessageKeyAndEventId() {
        when(kafkaTemplate.send(TOPIC, MESSAGE_KEY, PAYLOAD))
            .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        KafkaOutboxPublisher adapter = new KafkaOutboxPublisher(kafkaTemplate, TOPIC);

        adapter.publish(MESSAGE_KEY, PAYLOAD);
        adapter.publish(MESSAGE_KEY, PAYLOAD);

        ArgumentCaptor<String> messageKeyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(2)).send(eq(TOPIC), messageKeyCaptor.capture(), payloadCaptor.capture());

        assertThat(messageKeyCaptor.getAllValues()).containsExactly(MESSAGE_KEY, MESSAGE_KEY);
        assertThat(payloadCaptor.getAllValues()).containsExactly(PAYLOAD, PAYLOAD);
        assertThat(payloadCaptor.getAllValues().get(0)).contains("\"eventId\":\"order-confirmed:42\"");
    }
}
