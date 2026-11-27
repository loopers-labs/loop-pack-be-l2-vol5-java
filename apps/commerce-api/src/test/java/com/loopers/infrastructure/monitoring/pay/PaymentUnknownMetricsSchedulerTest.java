package com.loopers.infrastructure.monitoring.pay;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfiguration;

class PaymentUnknownMetricsSchedulerTest {
    @Test
    @DisplayName("Spring이 15초 고정 지연으로 등록한 작업을 실행하면 미확인 주문 집계가 갱신된다")
    void registersRefreshWithFifteenSecondDelay() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        PaymentUnknownMetrics metrics = mock(PaymentUnknownMetrics.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TaskScheduler.class, () -> scheduler);
            context.registerBean(PaymentUnknownMetrics.class, () -> metrics);
            context.register(SchedulingConfiguration.class, PaymentUnknownMetricsScheduler.class);
            context.refresh();

            var task = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).scheduleWithFixedDelay(task.capture(), any(Instant.class), eq(Duration.ofSeconds(15)));
            task.getValue().run();
            verify(metrics).refresh();
        }
    }
}
