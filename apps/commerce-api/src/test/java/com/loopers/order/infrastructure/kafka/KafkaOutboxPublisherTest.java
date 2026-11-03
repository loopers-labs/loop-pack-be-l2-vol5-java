package com.loopers.order.infrastructure.kafka;

import com.loopers.order.application.outbox.PublishResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
}
