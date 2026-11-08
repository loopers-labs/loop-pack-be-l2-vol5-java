package com.loopers.infrastructure.order.outbox;

import com.loopers.application.time.ApplicationTime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderConfirmedOutboxRelayTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 8, 1, 0, 0, 0, ZoneOffset.UTC);

    // 결과를 기록하지 못하고 멈춘 시도도 대기 끝에 실패를 기록한 경우와 같은 간격 뒤에 다시 고른다.
    @DisplayName("시도를 시작할 때 다음 선택 시각을 결과 대기 예산에 재시도 간격을 더한 만큼 뒤로 둔다.")
    @Test
    void delaysNextSelectionByWaitBudgetPlusRetryInterval() {
        OrderConfirmedOutboxStateService stateService = mock(OrderConfirmedOutboxStateService.class);
        when(stateService.beginAttempt(anyString(), any(), any(), anyInt()))
            .thenReturn(OrderConfirmedOutboxStateService.BeginAttemptResult.skipped());
        OrderConfirmedOutboxRelay relay = new OrderConfirmedOutboxRelay(
            stateService,
            mock(OrderConfirmedKafkaSender.class),
            new OrderConfirmedOutboxFailureClassifier(),
            new ApplicationTime(Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)),
            new SimpleMeterRegistry(),
            10,
            3,
            Duration.ofSeconds(5),
            Duration.ofSeconds(1),
            Duration.ofSeconds(6)
        );

        relay.runEventOnce("order-confirmed:v1:1");

        verify(stateService).beginAttempt("order-confirmed:v1:1", NOW, Duration.ofSeconds(12), 3);
    }
}
