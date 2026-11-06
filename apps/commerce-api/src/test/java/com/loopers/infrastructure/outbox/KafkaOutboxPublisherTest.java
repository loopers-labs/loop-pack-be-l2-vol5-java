package com.loopers.infrastructure.outbox;

import com.loopers.application.outbox.OutboxPublishException;
import com.loopers.domain.outbox.Outbox;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TimeoutException;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaOutboxPublisherTest {

    @DisplayName("전송이 실패하면 성공으로 넘어가지 않고 예외로 알린다.")
    @Test
    void throwsPublishException_whenSendFails() {
        // given
        @SuppressWarnings("unchecked")
        KafkaTemplate<Object, Object> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(String.class), any(), any()))
            .thenReturn(CompletableFuture.failedFuture(new NetworkException("broker down")));
        KafkaOutboxPublisher publisher = new KafkaOutboxPublisher(
            kafkaTemplate, "order-events", Duration.ofSeconds(1), CircuitBreaker.ofDefaults("outbox-publish-test")
        );
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
        KafkaOutboxPublisher publisher = new KafkaOutboxPublisher(
            kafkaTemplate, "order-events", Duration.ofMillis(100), CircuitBreaker.ofDefaults("outbox-publish-test")
        );
        Outbox outbox = Outbox.record(
            "order-confirmed:v1:42", 42L, "OrderConfirmed", "v1", "42", new byte[] {1, 2, 3},
            ZonedDateTime.parse("2026-11-04T10:00:00+09:00[Asia/Seoul]")
        );

        // when & then: 응답을 영원히 기다리지 않는다.
        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
            assertThrows(OutboxPublishException.class, () -> publisher.publish(outbox))
        );
    }

    @DisplayName("전송이 연속으로 실패해 회로가 열리면, 그 뒤의 전송은 broker 에 보내지 않고 바로 거절한다.")
    @Test
    void refusesWithoutSending_whenCircuitOpens() {
        // given: 브로커 쪽 실패만 세고, 최근 2회가 모두 실패하면 열려 1분 동안 열려 있는 회로 (운영 설정과 같은 기준)
        CircuitBreaker circuitBreaker = CircuitBreaker.of("outbox-publish-test", CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(2)
            .minimumNumberOfCalls(2)
            .failureRateThreshold(100)
            .waitDurationInOpenState(Duration.ofMinutes(1))
            .recordExceptions(BrokerUnavailableException.class)
            .build());
        @SuppressWarnings("unchecked")
        KafkaTemplate<Object, Object> kafkaTemplate = mock(KafkaTemplate.class);
        // 브로커 응답이 없어 producer 가 시간 초과로 실패한다.
        when(kafkaTemplate.send(any(String.class), any(), any()))
            .thenReturn(CompletableFuture.failedFuture(new TimeoutException("broker down")));
        KafkaOutboxPublisher publisher = new KafkaOutboxPublisher(kafkaTemplate, "order-events", Duration.ofSeconds(1), circuitBreaker);
        Outbox outbox = Outbox.record(
            "order-confirmed:v1:42", 42L, "OrderConfirmed", "v1", "42", new byte[] {1, 2, 3},
            ZonedDateTime.parse("2026-11-04T10:00:00+09:00[Asia/Seoul]")
        );

        // when: 두 번 연속 실패한다.
        assertThrows(OutboxPublishException.class, () -> publisher.publish(outbox));
        assertThrows(OutboxPublishException.class, () -> publisher.publish(outbox));

        // then: 전송할 수 없다고 알리고, 세 번째 전송은 broker 에 보내지 않고 거절한다. (실제 send 는 2번뿐)
        assertThat(publisher.canPublish()).isFalse();
        assertThrows(OutboxPublishException.class, () -> publisher.publish(outbox));
        verify(kafkaTemplate, times(2)).send(any(String.class), any(), any());
    }

    @DisplayName("메시지 하나의 문제(크기 초과)로 실패하면 회로를 열지 않는다. 다음 전송도 broker 로 보낸다.")
    @Test
    void keepsCircuitClosed_whenMessageIsRejected() {
        // given: 브로커 쪽 실패만 세고, 최근 2회가 모두 실패하면 여는 회로 (운영 설정과 같은 기준)
        CircuitBreaker circuitBreaker = CircuitBreaker.of("outbox-publish-test", CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(2)
            .minimumNumberOfCalls(2)
            .failureRateThreshold(100)
            .waitDurationInOpenState(Duration.ofMinutes(1))
            .recordExceptions(BrokerUnavailableException.class)
            .build());
        @SuppressWarnings("unchecked")
        KafkaTemplate<Object, Object> kafkaTemplate = mock(KafkaTemplate.class);
        // 브로커는 정상이지만 이 메시지가 너무 커서 거절된다.
        when(kafkaTemplate.send(any(String.class), any(), any()))
            .thenReturn(CompletableFuture.failedFuture(new RecordTooLargeException("record too large")));
        KafkaOutboxPublisher publisher = new KafkaOutboxPublisher(kafkaTemplate, "order-events", Duration.ofSeconds(1), circuitBreaker);
        Outbox outbox = Outbox.record(
            "order-confirmed:v1:42", 42L, "OrderConfirmed", "v1", "42", new byte[] {1, 2, 3},
            ZonedDateTime.parse("2026-11-04T10:00:00+09:00[Asia/Seoul]")
        );

        // when: 두 번 실패한다.
        assertThrows(OutboxPublishException.class, () -> publisher.publish(outbox));
        assertThrows(OutboxPublishException.class, () -> publisher.publish(outbox));

        // then: 회로는 닫혀 있어 전송할 수 있고, 세 번째 전송도 broker 로 보낸다. (실제 send 3번)
        assertThat(publisher.canPublish()).isTrue();
        assertThrows(OutboxPublishException.class, () -> publisher.publish(outbox));
        verify(kafkaTemplate, times(3)).send(any(String.class), any(), any());
    }
}
