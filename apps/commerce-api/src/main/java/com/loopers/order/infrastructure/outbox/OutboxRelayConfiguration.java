package com.loopers.order.infrastructure.outbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class OutboxRelayConfiguration {

    @Bean
    Clock outboxClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConfigurationProperties(prefix = "outbox.relay")
    OutboxRelayProperties outboxRelayProperties() {
        return new OutboxRelayProperties();
    }
}
