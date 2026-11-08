package com.loopers.infrastructure.outbox;

import com.loopers.application.outbox.OutboxDeliveryTransactions;
import com.loopers.application.outbox.OutboxRelay;
import com.loopers.application.outbox.OutboxRelaySettings;
import com.loopers.application.outbox.OutboxRetryPolicy;
import com.loopers.domain.outbox.OutboxSender;
import com.loopers.interfaces.scheduler.OutboxRelayScheduler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class OutboxRelayConfiguration {
    @Bean
    public OutboxRelaySettings outboxRelaySettings(
            @Value("${commerce.outbox-relay.batch-size:10}") int batchSize,
            @Value("${commerce.outbox-relay.fixed-delay:1s}") String fixedDelay,
            @Value("${commerce.outbox-relay.send-budget:12s}") String sendBudget,
            @Value("${commerce.outbox-relay.retry-initial:5s}") String retryInitial,
            @Value("${commerce.outbox-relay.retry-max:60s}") String retryMax) {
        return new OutboxRelaySettings(batchSize, DurationStyle.detectAndParse(fixedDelay),
            DurationStyle.detectAndParse(sendBudget), DurationStyle.detectAndParse(retryInitial),
            DurationStyle.detectAndParse(retryMax));
    }

    @Bean
    public OutboxRetryPolicy outboxRetryPolicy(OutboxRelaySettings settings) {
        return new OutboxRetryPolicy(settings.retryInitial(), settings.retryMax());
    }

    @Bean
    public OutboxRelay outboxRelay(OutboxDeliveryTransactions transactions, OutboxSender sender,
            @Qualifier("outboxClock") Clock clock, OutboxRelaySettings settings) {
        return new OutboxRelay(transactions, sender, clock, settings, System::nanoTime);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "commerce.outbox-relay.enabled", havingValue = "true")
    @EnableScheduling
    static class AutomaticRelayConfiguration {
        @Bean
        ThreadPoolTaskScheduler outboxRelayTaskScheduler() {
            var scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("outbox-relay-");
            scheduler.setRemoveOnCancelPolicy(true);
            scheduler.setWaitForTasksToCompleteOnShutdown(false);
            scheduler.setAwaitTerminationSeconds(15);
            return scheduler;
        }

        @Bean
        OutboxRelayScheduler outboxRelayScheduler(OutboxRelay relay) {
            return new OutboxRelayScheduler(relay);
        }
    }
}
