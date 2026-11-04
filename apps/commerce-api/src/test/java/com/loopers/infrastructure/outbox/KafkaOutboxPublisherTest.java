package com.loopers.infrastructure.outbox;

import com.loopers.application.outbox.OutboxPublishException;
import com.loopers.domain.outbox.Outbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KafkaOutboxPublisherTest {

    @DisplayName("전송이 실패하면 성공으로 넘어가지 않고 예외로 알린다.")
    @Test
    void throwsPublishException_whenSendFails() {
        // given
        @SuppressWarnings("unchecked")
        KafkaTemplate<Object, Object> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(String.class), any(), any()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        KafkaOutboxPublisher publisher = new KafkaOutboxPublisher(kafkaTemplate, "order-events", Duration.ofSeconds(1));
        Outbox outbox = Outbox.record(
            "order-confirmed:v1:42", 42L, "OrderConfirmed", "v1", "42", new byte[] {1, 2, 3},
            ZonedDateTime.parse("2026-11-04T10:00:00+09:00[Asia/Seoul]")
        );

        // when
        OutboxPublishException exception = assertThrows(OutboxPublishException.class, () -> publisher.publish(outbox));

        // then
        assertThat(exception.getMessage()).contains("broker down");
    }

    @DisplayName("broker 응답이 시간 안에 오지 않으면 기다리다 예외로 알린다.")
    @Test
    void throwsPublishException_whenNoAckWithinTimeout() {
        // given
        @SuppressWarnings("unchecked")
        KafkaTemplate<Object, Object> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(String.class), any(), any())).thenReturn(new CompletableFuture<>());
        KafkaOutboxPublisher publisher = new KafkaOutboxPublisher(kafkaTemplate, "order-events", Duration.ofMillis(100));
        Outbox outbox = Outbox.record(
            "order-confirmed:v1:42", 42L, "OrderConfirmed", "v1", "42", new byte[] {1, 2, 3},
            ZonedDateTime.parse("2026-11-04T10:00:00+09:00[Asia/Seoul]")
        );

        // when & then: 응답을 영원히 기다리지 않는다.
        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
            assertThrows(OutboxPublishException.class, () -> publisher.publish(outbox))
        );
    }
}
