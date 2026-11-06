package com.loopers.infrastructure.outbox;

import com.loopers.application.outbox.OutboxDeliveryTransactions;
import com.loopers.application.outbox.OutboxRelay;
import com.loopers.application.outbox.OutboxRelaySettings;
import com.loopers.domain.outbox.OutboxSender;
import com.loopers.interfaces.scheduler.OutboxRelayScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OutboxRelayConfigurationTest {
    private final OutboxDeliveryTransactions transactions = mock(OutboxDeliveryTransactions.class);
    private final OutboxSender sender = mock(OutboxSender.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(OutboxRelayConfiguration.class)
        .withBean(OutboxDeliveryTransactions.class, () -> transactions)
        .withBean(OutboxSender.class, () -> sender)
        .withBean("outboxClock", Clock.class, Clock::systemUTC);

    @Test
    void defaultsKeepAutomaticSendingOffWithoutRemovingTheInternalRelay() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(OutboxRelay.class)
                .doesNotHaveBean(OutboxRelayScheduler.class).doesNotHaveBean("outboxRelayTaskScheduler");
            assertThat(context.getBean(OutboxRelaySettings.class)).isEqualTo(new OutboxRelaySettings(10,
                Duration.ofSeconds(1), Duration.ofSeconds(12), Duration.ofSeconds(5), Duration.ofSeconds(60)));
            verifyNoInteractions(transactions, sender);
        });
    }

    @Test
    void explicitEnablementCreatesOneDedicatedSchedulerAndActuallyRunsIt() {
        when(transactions.findDueIds(any(), anyInt())).thenReturn(List.of());
        runner.withPropertyValues("commerce.outbox-relay.enabled=true", "commerce.outbox-relay.fixed-delay=20ms")
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(OutboxRelayScheduler.class);
                var scheduler = context.getBean("outboxRelayTaskScheduler", ThreadPoolTaskScheduler.class);
                assertThat(scheduler.getPoolSize()).isEqualTo(1);
                assertThat(scheduler.getThreadNamePrefix()).isEqualTo("outbox-relay-");
                await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(transactions, atLeastOnce()).findDueIds(any(), anyInt()));
                verifyNoInteractions(sender);
            });
    }

    @Test
    void invalidTimingsAreRejectedBeforeStartingAWorker() {
        assertThatThrownBy(() -> new OutboxRelaySettings(0, Duration.ofSeconds(1), Duration.ofSeconds(12),
            Duration.ofSeconds(5), Duration.ofSeconds(60))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxRelaySettings(10, Duration.ZERO, Duration.ofSeconds(12),
            Duration.ofSeconds(5), Duration.ofSeconds(60))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxRelaySettings(10, Duration.ofSeconds(1), Duration.ZERO,
            Duration.ofSeconds(5), Duration.ofSeconds(60))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxRelaySettings(10, Duration.ofSeconds(1), Duration.ofSeconds(12),
            Duration.ofSeconds(5), Duration.ofSeconds(4))).isInstanceOf(IllegalArgumentException.class);
    }
}
