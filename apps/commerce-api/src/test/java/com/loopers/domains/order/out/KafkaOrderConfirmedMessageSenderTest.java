package com.loopers.domains.order.out;

import com.loopers.domains.order.app.port.SendOutcome;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// broker 확인을 기다리는 중 인터럽트를 받으면 예외 분류 대상이 아니다. 인터럽트 상태를 되살리고, 행 탓 실패로 세지 않는다(계획 2절 분류 표).
// 결과를 별도 종류로 둘지 broker 수준으로 둘지는 Green에서 정한다. 이 테스트는 "행 수준이 아님"과 "플래그 되살림"만 본다.
class KafkaOrderConfirmedMessageSenderTest {

    @Test
    @DisplayName("broker 확인 대기 중 인터럽트가 걸리면 인터럽트 플래그를 되살리고 결과를 행 수준 실패로 돌려주지 않는다")
    @SuppressWarnings("unchecked")
    void interruptedWaitRestoresFlagAndIsNotRowFailure() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send("order-events", "1", "{}")).thenReturn(new CompletableFuture<SendResult<String, String>>());
        KafkaOrderConfirmedMessageSender sender = new KafkaOrderConfirmedMessageSender(kafkaTemplate, "order-events",
            Duration.ofSeconds(35));

        Thread.currentThread().interrupt();
        try {
            SendOutcome outcome = sender.send("1", "{}");

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(outcome).isNotInstanceOf(SendOutcome.RowFailure.class);
        } finally {
            Thread.interrupted();
        }
    }
}
