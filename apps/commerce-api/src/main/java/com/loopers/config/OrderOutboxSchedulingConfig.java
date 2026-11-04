package com.loopers.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@Profile("!outbox-resume & !outbox-recover")
@ConditionalOnProperty(name = "order-events.relay.enabled", havingValue = "true")
public class OrderOutboxSchedulingConfig {
    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("scheduling-");
        return scheduler;
    }

    @Bean
    ThreadPoolTaskScheduler orderOutboxTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("order-outbox-");
        return scheduler;
    }
}
