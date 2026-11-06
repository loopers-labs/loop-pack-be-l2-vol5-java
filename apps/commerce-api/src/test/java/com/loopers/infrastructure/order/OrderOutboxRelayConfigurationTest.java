package com.loopers.infrastructure.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.loopers.application.order.OutboxDeliveryStore;
import com.loopers.application.order.OutboxKafkaSender;
import com.loopers.application.order.RecoverOrderConfirmedOutbox;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OrderOutboxRelayConfigurationTest {
    @Test
    void 과거_주문_복구용_앱은_스케줄러를_켜지_않고_복구만_요청한다() {
        var recovery = mock(RecoverOrderConfirmedOutbox.class);
        var sender = mock(OutboxKafkaSender.class);

        new ApplicationContextRunner()
                .withUserConfiguration(OrderOutboxRelayConfiguration.class)
                .withBean(OrderOutboxRepository.class, () -> mock(OrderOutboxRepository.class))
                .withBean(OutboxKafkaSender.class, () -> sender)
                .withBean(RecoverOrderConfirmedOutbox.class, () -> recovery)
                .withPropertyValues("spring.profiles.active=local", "order-events.outbox.enabled=true",
                        "order-events.outbox.recover-order-id=100")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(OrderOutboxRelayConfiguration.Scheduling.class);
                    var recover = context.getBean("recoverOrderOutbox", ApplicationRunner.class);
                    assertThatCode(() -> recover.run(new DefaultApplicationArguments(new String[0])))
                            .doesNotThrowAnyException();
                    verify(recovery).recover(100L);
                    verifyNoInteractions(sender);
                });
    }

    @Test
    void 수동_재개용_앱은_스케줄러를_켜지_않고_blocked를_pending으로_변경한다() {
        var store = mock(OutboxDeliveryStore.class);
        var sender = mock(OutboxKafkaSender.class);
        var repository = mock(OrderOutboxRepository.class);
        when(store.resume(eq("event-1"), any(Instant.class))).thenReturn(true);

        new ApplicationContextRunner()
                .withUserConfiguration(OrderOutboxRelayConfiguration.class)
                .withBean(OutboxDeliveryStore.class, () -> store, definition -> definition.setPrimary(true))
                .withBean(OutboxKafkaSender.class, () -> sender)
                .withBean(OrderOutboxRepository.class, () -> repository)
                .withPropertyValues("spring.profiles.active=local", "order-events.outbox.enabled=true",
                        "order-events.outbox.resume-event-id=event-1")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(OrderOutboxRelayConfiguration.Scheduling.class);
                    var resume = context.getBean("resumeOrderOutbox", ApplicationRunner.class);
                    assertThatCode(() -> resume.run(new DefaultApplicationArguments(new String[0])))
                            .doesNotThrowAnyException();
                    verify(store).resume(eq("event-1"), any(Instant.class));
                    verifyNoInteractions(sender);
                });
    }
}
