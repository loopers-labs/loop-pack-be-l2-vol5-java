package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.application.order.RecoverOrderConfirmedOutbox;
import com.loopers.application.order.OutboxDeliveryStore;
import com.loopers.application.order.OutboxKafkaSender;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;

@Configuration(proxyBeanMethods = false)
public class OrderOutboxRelayConfiguration {
    @Bean
    public Clock outboxRelayClock() {
        return Clock.systemUTC();
    }

    @Bean
    public OrderOutboxRelay orderConfirmedRelay(OutboxDeliveryStore store, OutboxKafkaSender sender,
            @Qualifier("outboxRelayClock") Clock clock) {
        return new OrderOutboxRelay(store, sender, clock,
                bound -> ThreadLocalRandom.current().nextLong(bound));
    }

    @Bean
    @Profile("local")
    @ConditionalOnProperty(name = "order-events.outbox.resume-event-id")
    public ApplicationRunner resumeOrderOutbox(OrderOutboxRelay relay,
            @Value("${order-events.outbox.resume-event-id}") String eventId) {
        return args -> relay.resume(eventId);
    }

    @Bean
    @Profile("local")
    @ConditionalOnProperty(name = "order-events.outbox.recover-order-id")
    public ApplicationRunner recoverOrderOutbox(RecoverOrderConfirmedOutbox recovery,
            @Value("${order-events.outbox.recover-order-id}") long orderId) {
        return args -> recovery.recover(orderId);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "order-events.outbox.enabled", havingValue = "true")
    @Conditional(NotManualRecovery.class)
    static class Scheduling {
        private final OrderOutboxRelay relay;
        private final OrderOutboxRepository repository;

        Scheduling(OrderOutboxRelay relay, OrderOutboxRepository repository) {
            this.relay = relay;
            this.repository = repository;
        }

        @Scheduled(fixedDelayString = "${order-events.outbox.poll-interval:1000}")
        public void poll() {
            relay.runOnce();
        }

        @Scheduled(fixedDelayString = "${order-events.outbox.cleanup-interval:3600000}")
        public void cleanupPublished() {
            repository.deletePublishedBefore(Clock.systemUTC().instant().minus(7, ChronoUnit.DAYS));
        }
    }

    static class NotManualRecovery implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var environment = context.getEnvironment();
            return !environment.acceptsProfiles(Profiles.of("local"))
                    || (!environment.containsProperty("order-events.outbox.resume-event-id")
                    && !environment.containsProperty("order-events.outbox.recover-order-id"));
        }
    }
}
